package com.keyqiang.spw.listenstats

import java.util.UUID

/**
 * 统计引擎：把宿主零散的回调拼成「可计数的收听会话」。
 *
 * 设计依据见 DESIGN.md：
 * - 宿主**没有切歌回调**，曲目身份来自 `onBeforeLoadLyrics/onAfterLoadLyrics` 给的 MediaItem.path
 * - 时长只认「位置差」，跳转 / 回绕一律不计数
 * - 暂停或长时间无 tick 用墙钟空闲阈值收尾
 * - 长会话按 [checkpointSeconds] 分段落盘，避免崩溃丢太多
 *
 * 本类刻意不引用任何 SPW 类型，便于自测。
 */
internal class StatsEngine(
    private val store: StatsStore,
    private val config: () -> EngineConfig,
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val minSessionSeconds: Long = 5,
    private val checkpointSeconds: Long = 300,
    /**
     * 会话进入「待上报队列」后的钩子（用于「每曲结束时立即上报」）。
     *
     * ⚠️ 运行在**宿主的回调线程**上：实现方必须立即返回，不能做网络/磁盘操作。
     */
    private val onSessionEnqueued: () -> Unit = {},
    /**
     * 会话**结算完成**后的钩子（无论是否上报）：用于写本地会话历史 / 刷新本地报告。
     *
     * ⚠️ 同样运行在宿主回调线程：实现方只能做内存操作 + 打标记。
     */
    private val onSessionClosed: (SessionRecord) -> Unit = {}
) {
    private var currentTrack: MediaInfo? = null
    private var sessionOpen = false
    private var sessionTrack: MediaInfo? = null
    private var sessionStartedAt = 0L
    private var sessionMillis = 0L
    private var sessionContinued = false
    private var lastPosition = -1L
    private var lastTickAt = 0L
    private var ticks = 0L

    /** 本轮「有位置回调但没有曲目信息」是否已经提示过（每段只提示一次，避免刷屏）。 */
    private var missingTrackLogged = false

    /** 当前会话是「占位会话」：宿主还没给曲目信息，先把时长挂起来，等曲目信息到了再归因。 */
    private var sessionPlaceholder = false

    /** 占位期间位置是否回跳过（回跳 = 很可能已经换歌 → 不能再把这段算给后面的曲目）。 */
    private var placeholderBackwardJump = false

    /** 宿主加载歌词前回调（IO 线程），这是曲目元数据的唯一来源。 */
    @Synchronized
    fun onMediaItem(media: MediaInfo) {
        val previous = currentTrack
        currentTrack = media
        missingTrackLogged = false
        if (previous?.path != media.path) {
            log("曲目变化：${media.title} — ${media.artist}")
        }
        // 宿主"迟到"的曲目信息：把占位会话升级成真实曲目，之前那段无主时长就此正确归因
        if (sessionOpen && sessionPlaceholder) {
            if (placeholderBackwardJump) {
                log("曲目信息迟到但期间位置回跳过（疑似已换歌），这段保持未归因：" + media.title)
            } else {
                sessionTrack = media
                sessionPlaceholder = false
                log(
                    "曲目信息迟到：此前 " + (sessionMillis / 1000) + "s 无主时长归因到 " +
                        media.title + " — " + media.artist
                )
            }
        }
        if (sessionOpen && sessionTrack?.path != media.path) closeLocked("trackChanged")
    }

    /** 宿主每秒回调的播放位置。 */
    @Synchronized
    fun onPosition(position: Long) {
        val cfg = config()
        val now = clock()
        if (!cfg.enabled) {
            lastPosition = position
            lastTickAt = now
            return
        }

        // 先记下上一次 tick 的墙钟时间，再更新（顺序反了会让 wallDelta 恒为 0）
        val previousTickAt = lastTickAt

        // 长时间没有 tick（暂停 / 睡眠 / 关闭播放）→ 结算旧会话
        if (sessionOpen && now - previousTickAt > cfg.idleGapSeconds * 1000L) closeLocked("idle")
        lastTickAt = now

        if (!sessionOpen) {
            if (position < 0) {
                lastPosition = position
                return
            }
            val track = currentTrack
            if (track == null) {
                // 宿主还没给出曲目信息（典型场景：插件加载时那首歌已经在播，
                // 宿主不会补调 onBeforeLoadLyrics）。默认不计，可选记入「未知曲目」桶。
                if (!cfg.countUnknownTrack) {
                    reportMissingTrack(position)
                    lastPosition = position
                    return
                }
                openLocked(UNATTRIBUTED_TRACK, now, continued = false)
                sessionPlaceholder = true
                placeholderBackwardJump = false
                lastPosition = position
                return
            }
            openLocked(track, now, continued = false)
            lastPosition = position
            return
        }

        val delta = position - lastPosition
        // 位置增量与实际墙钟耗时取小：
        // - 正常每秒一次 tick 时两者几乎相等，行为不变
        // - 倍速播放（位置跑得比真实时间快）按真实耗时计
        // - 宿主异常地在极短时间内补发大量 tick 时，不会把时长算成几十倍
        // 占位期间位置往回跳 → 很可能已经换歌，后面迟到的曲目信息不能拿来认领这段时长
        if (sessionPlaceholder && lastPosition >= 0 && delta < -MAX_TICK_DELTA_MS) {
            placeholderBackwardJump = true
        }
        if (lastPosition >= 0 && delta in 1..MAX_TICK_DELTA_MS) {
            val wallDelta = (now - previousTickAt).coerceAtLeast(0L)
            sessionMillis += minOf(delta, wallDelta)
        }
        lastPosition = position

        ticks++
        if (ticks % HEARTBEAT_TICKS == 0L) {
            log(
                "tick 心跳: 位置=" + (position / 1000) + "s 本会话=" + (sessionMillis / 1000) +
                    "s 曲目=" + (sessionTrack?.title ?: "无")
            )
        }

        if (sessionMillis >= checkpointSeconds * 1000L) {
            closeLocked("checkpoint")
            val track = currentTrack
            if (track != null) {
                openLocked(track, now, continued = true)
                lastPosition = position
            }
        }
    }

    /** 跳转回调：只重置基准，不计数。 */
    @Synchronized
    fun onSeekTo(position: Long) {
        lastPosition = position
        lastTickAt = clock()
    }

    @Synchronized
    fun onPlayState(isPlaying: Boolean) {
        if (!isPlaying) closeLocked("paused")
    }

    @Synchronized
    fun onPlayerState(state: PlayerState) {
        when (state) {
            PlayerState.Ended -> closeLocked("ended")
            PlayerState.Idle -> closeLocked("idleState")
            PlayerState.Buffering, PlayerState.Ready -> Unit
        }
    }

    /** 插件停止 / 宿主退出时结算。 */
    @Synchronized
    fun close(reason: String) {
        closeLocked(reason)
    }

    @Synchronized
    fun openSessionSeconds(): Long = if (sessionOpen) sessionMillis / 1000 else 0L

    @Synchronized
    fun describeCurrent(): String {
        val track = sessionTrack ?: currentTrack ?: return "当前没有曲目信息"
        val state = if (sessionOpen) "统计中 ${formatDuration(sessionMillis / 1000)}" else "未在统计"
        return "${track.title} — ${track.artist}（$state）"
    }

    // ---------------- 内部 ----------------

    private fun openLocked(track: MediaInfo, now: Long, continued: Boolean) {
        if (track.path != UNATTRIBUTED_PATH) sessionPlaceholder = false
        sessionOpen = true
        sessionTrack = track
        sessionStartedAt = now
        sessionMillis = 0
        sessionContinued = continued
        log(
            "开始统计: " + track.title + " — " + track.artist +
                (if (continued) "（续段）" else "（新会话）") + " path=" + track.path
        )
    }

    private fun closeLocked(reason: String) {
        if (!sessionOpen) return
        val track = sessionTrack
        val seconds = sessionMillis / 1000
        val startedAt = sessionStartedAt
        val continued = sessionContinued
        sessionOpen = false
        sessionTrack = null
        sessionMillis = 0

        if (track == null) return
        if (seconds < minSessionSeconds) {
            // 0 秒段是切歌瞬间的必然产物（宿主会先补一次旧曲目的位置回调），记下来只会刷屏；
            // 1~4 秒的真实短播仍要留痕，便于判断「跳歌被正确忽略」。
            if (seconds > 0) log("丢弃过短会话 ${seconds}s（$reason）：${track.title}")
            return
        }

        val record = SessionRecord(
            id = UUID.randomUUID().toString(),
            media = track,
            startedAt = startedAt,
            endedAt = clock(),
            listenedSeconds = seconds,
            endedReason = reason,
            continued = continued
        )
        val enqueue = config().reportEnabled
        store.applySession(record, enqueue = enqueue)
        if (track.path == UNATTRIBUTED_PATH) {
            log("记录未归因时长 ${seconds}s（$reason）：宿主未给出曲目信息")
        } else {
            log("记录会话 ${seconds}s（$reason）：${track.title} — ${track.artist}")
        }
        runCatching { onSessionClosed(record) }
        if (enqueue) {
            // 交给插件决定是否立刻上报（mode=session 时立即发，不等定时）
            runCatching { onSessionEnqueued() }
        }
    }

    private fun reportMissingTrack(position: Long) {
        if (missingTrackLogged) return
        missingTrackLogged = true
        log(
            "收到位置回调但尚无曲目信息（position=" + position + "ms）：该段不会入库，" +
                "直到宿主给出曲目（onBeforeLoadLyrics）。若想不丢这段时长，" +
                "可在配置里打开「统计无法归因的播放」（默认已开）。"
        )
    }

    companion object {
        /** 单次 tick 最多认可的毫秒增量：超过这个值视为跳转，不计入收听时长。 */
        const val MAX_TICK_DELTA_MS = 3000L

        private val UNATTRIBUTED_TRACK = MediaInfo(
            title = "未归因",
            artist = "",
            album = "",
            albumArtist = "",
            path = UNATTRIBUTED_PATH
        )

        /** 每 60 次位置回调（正常约 1 分钟）写一行心跳，便于远程判断回调是否还在来。 */
        private const val HEARTBEAT_TICKS = 60L

        fun formatDuration(seconds: Long): String {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            return when {
                h > 0 -> "%d 小时 %d 分".format(h, m)
                m > 0 -> "%d 分 %d 秒".format(m, s)
                else -> "%d 秒".format(s)
            }
        }
    }
}
