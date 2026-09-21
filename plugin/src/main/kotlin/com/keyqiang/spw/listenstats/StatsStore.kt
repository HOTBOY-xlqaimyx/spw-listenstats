package com.keyqiang.spw.listenstats

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId

/**
 * 统计数据的持久化。
 *
 * - `listenstats.json`：累计统计（总量 / 按天 / 按曲目）
 * - `report-queue.json`：待上报的会话队列
 *
 * 写入一律「临时文件 + 原子替换」，避免崩溃留下半截 JSON。
 */
internal class StatsStore(
    private val statsFile: File,
    private val queueFile: File
) {
    val totals = Totals()
    val daily = LinkedHashMap<String, Long>()
    val tracks = LinkedHashMap<String, TrackStat>()
    val queue = ArrayDeque<SessionRecord>()

    var dirty: Boolean = false

    /**
     * 「摘要快照」的发送记账。
     *
     * 为什么需要它：会话队列为空时，上报器原本**什么都不发** ——
     * 结果是「装了新版本、但还没听完一首歌」的机器上，服务端永远拿不到新的
     * `summary`（例如 0.5.0 的 `trackStats`），报告页就一直显示旧数据。
     * 现在：**版本号变了**或**本地统计变了**但还没发出去，就算队列为空也会补发一次纯摘要。
     */
    var summarySentSignature: String = ""
    var summarySentVersion: String = ""

    // ---------------- 读 ----------------

    fun load(warn: (String) -> Unit = {}) {
        loadStats(warn)
        loadQueue(warn)
        dirty = false
    }

    private fun loadStats(warn: (String) -> Unit) {
        if (!statsFile.isFile) return
        val text = runCatching { statsFile.readText(StandardCharsets.UTF_8) }
            .onFailure { warn("读取 ${statsFile.name} 失败：${it.message}") }
            .getOrNull() ?: return
        val root = runCatching { JsonParser.parse(text) }
            .onFailure {
                warn("解析 ${statsFile.name} 失败：${it.message}，将备份并重新开始")
                runCatching {
                    statsFile.copyTo(File(statsFile.parentFile, statsFile.name + ".corrupt"), overwrite = true)
                }
            }
            .getOrNull() ?: return

        totals.listenedSeconds = root["totals"]?.get("listenedSeconds").asLong()
        totals.sessions = root["totals"]?.get("sessions").asLong()
        totals.firstPlayedAt = root["totals"]?.get("firstPlayedAt").asLong()
        totals.lastPlayedAt = root["totals"]?.get("lastPlayedAt").asLong()
        totals.unattributedSeconds = root["totals"]?.get("unattributedSeconds").asLong()

        root["daily"]?.asObjectOrNull()?.fields?.forEach { (day, value) ->
            daily[day] = value.asLong(0)
        }

        root["tracks"]?.asObjectOrNull()?.fields?.forEach { (path, value) ->
            val stat = TrackStat(
                path = path,
                title = value["title"].asString(),
                artist = value["artist"].asString(),
                album = value["album"].asString(),
                albumArtist = value["albumArtist"].asString()
            )
            stat.listenedSeconds = value["listenedSeconds"].asLong()
            stat.sessions = value["sessions"].asLong()
            stat.firstPlayedAt = value["firstPlayedAt"].asLong()
            stat.lastPlayedAt = value["lastPlayedAt"].asLong()
            tracks[path] = stat
        }

        summarySentSignature = root["reportState"]?.get("summarySignature").asString()
        summarySentVersion = root["reportState"]?.get("sentVersion").asString()
    }

    private fun loadQueue(warn: (String) -> Unit) {
        if (!queueFile.isFile) return
        val text = runCatching { queueFile.readText(StandardCharsets.UTF_8) }
            .onFailure { warn("读取 ${queueFile.name} 失败：${it.message}") }
            .getOrNull() ?: return
        val root = runCatching { JsonParser.parse(text) }
            .onFailure { warn("解析 ${queueFile.name} 失败：${it.message}") }
            .getOrNull() ?: return
        for (item in root.asArrayOrEmpty()) {
            val media = MediaInfo(
                title = item["title"].asString(),
                artist = item["artist"].asString(),
                album = item["album"].asString(),
                albumArtist = item["albumArtist"].asString(),
                path = item["path"].asString()
            )
            queue.addLast(
                SessionRecord(
                    id = item["id"].asString(),
                    media = media,
                    startedAt = item["startedAt"].asLong(),
                    endedAt = item["endedAt"].asLong(),
                    listenedSeconds = item["listenedSeconds"].asLong(),
                    endedReason = item["endedReason"].asString("unknown"),
                    continued = item["continued"].asBool(false)
                )
            )
        }
    }

    // ---------------- 写 ----------------

    fun save(warn: (String) -> Unit = {}) {
        prune()
        writeAtomically(statsFile, statsToJson().encode(), warn)
        writeAtomically(queueFile, queueToJson().encode(), warn)
        dirty = false
    }

    fun toJson(): Jv.O = statsToJson()

    fun summary(): Jv.O = jObj(
        "listenedSeconds" to jNum(totals.listenedSeconds),
        "sessions" to jNum(totals.sessions),
        "tracks" to jNum(tracks.size.toLong()),
        "firstPlayedAt" to jNum(totals.firstPlayedAt),
        "lastPlayedAt" to jNum(totals.lastPlayedAt),
        "daily" to dailyToJson(),
        // 「未归因」时长（宿主没给曲目信息的那段）：计入总量，但不进曲目榜
        "unattributedSeconds" to jNum(totals.unattributedSeconds),
        // 「逐曲目聚合」是**全量历史**（含开启上报之前的收听）：报告页的曲目榜/艺人榜靠它。
        // 对 v0.4.0 及以前的接收端是安全增量 —— 它们读到自己不认识的字段会直接忽略。
        "trackStatsTotal" to jNum(tracks.size.toLong()),
        "trackStats" to trackStatsToJson()
    )

    /**
     * 逐曲目聚合（给服务端做榜单用）。
     *
     * 按收听时长降序，最多 [MAX_REPORT_TRACKS] 条：这是**快照**而非本地存档，
     * 用上限兜住「曲库极大仍每轮上报」时的请求体积。
     */
    fun trackStatsToJson(): Jv.A {
        val top = tracks.values.sortedByDescending { it.listenedSeconds }.take(MAX_REPORT_TRACKS)
        return jArr(
            top.map { stat ->
                jObj(
                    "title" to jStr(stat.title),
                    "artist" to jStr(stat.artist),
                    "album" to jStr(stat.album),
                    "albumArtist" to jStr(stat.albumArtist),
                    "path" to jStr(stat.path),
                    "listenedSeconds" to jNum(stat.listenedSeconds),
                    "sessions" to jNum(stat.sessions),
                    "firstPlayedAt" to jNum(stat.firstPlayedAt),
                    "lastPlayedAt" to jNum(stat.lastPlayedAt)
                )
            }
        )
    }

    fun dailyToJson(): Jv.O {
        val fields = LinkedHashMap<String, Jv>()
        for ((day, seconds) in daily) fields[day] = jNum(seconds)
        return Jv.O(fields)
    }

    private fun statsToJson(): Jv.O {
        val trackFields = LinkedHashMap<String, Jv>()
        for ((path, stat) in tracks) {
            trackFields[path] = jObj(
                "title" to jStr(stat.title),
                "artist" to jStr(stat.artist),
                "album" to jStr(stat.album),
                "albumArtist" to jStr(stat.albumArtist),
                "listenedSeconds" to jNum(stat.listenedSeconds),
                "sessions" to jNum(stat.sessions),
                "firstPlayedAt" to jNum(stat.firstPlayedAt),
                "lastPlayedAt" to jNum(stat.lastPlayedAt)
            )
        }
        return jObj(
            "schema" to jNum(SCHEMA),
            "updatedAt" to jNum(System.currentTimeMillis()),
            "totals" to jObj(
                "listenedSeconds" to jNum(totals.listenedSeconds),
                "sessions" to jNum(totals.sessions),
                "firstPlayedAt" to jNum(totals.firstPlayedAt),
                "lastPlayedAt" to jNum(totals.lastPlayedAt),
                "unattributedSeconds" to jNum(totals.unattributedSeconds)
            ),
            "daily" to dailyToJson(),
            "tracks" to Jv.O(trackFields),
            "reportState" to jObj(
                "summarySignature" to jStr(summarySentSignature),
                "sentVersion" to jStr(summarySentVersion)
            )
        )
    }

    // ---------------- 摘要快照记账 ----------------

    /** 本地统计的廉价指纹：任一字段变了就说明服务端手上的摘要过期了。 */
    fun summarySignature(): String =
        totals.listenedSeconds.toString() + "|" + totals.sessions + "|" + tracks.size + "|" +
            daily.size + "|" + totals.lastPlayedAt

    /** 是否该主动推一次纯摘要（版本升级、或本地统计自上次上报后又有变化）。 */
    fun summaryNeedsSend(pluginVersion: String): Boolean =
        pluginVersion != summarySentVersion || summarySignature() != summarySentSignature

    /** 上报成功后记账（下次就不重复发了）。 */
    fun markSummarySent(pluginVersion: String) {
        summarySentVersion = pluginVersion
        summarySentSignature = summarySignature()
        dirty = true
    }

    fun queueToJson(): Jv.A = jArr(
        queue.map { record ->
            jObj(
                "id" to jStr(record.id),
                "path" to jStr(record.media.path),
                "title" to jStr(record.media.title),
                "artist" to jStr(record.media.artist),
                "album" to jStr(record.media.album),
                "albumArtist" to jStr(record.media.albumArtist),
                "startedAt" to jNum(record.startedAt),
                "endedAt" to jNum(record.endedAt),
                "listenedSeconds" to jNum(record.listenedSeconds),
                "endedReason" to jStr(record.endedReason),
                "continued" to jBool(record.continued)
            )
        }
    )

    private fun writeAtomically(target: File, text: String, warn: (String) -> Unit) {
        runCatching {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(text, StandardCharsets.UTF_8)
            try {
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Throwable) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure { warn("写入 ${target.name} 失败：${it.message}") }
    }

    // ---------------- 业务 ----------------

    /** 入库一个已结束的会话；[enqueue] 为 true 时同时进入待上报队列。 */
    fun applySession(record: SessionRecord, enqueue: Boolean) {
        totals.listenedSeconds += record.listenedSeconds
        totals.sessions += 1
        if (totals.firstPlayedAt == 0L || record.startedAt < totals.firstPlayedAt) {
            totals.firstPlayedAt = record.startedAt
        }
        if (record.endedAt > totals.lastPlayedAt) totals.lastPlayedAt = record.endedAt

        val day = dateKey(record.endedAt)
        daily[day] = (daily[day] ?: 0L) + record.listenedSeconds

        // 「未归因」只记账、不建曲目条目（否则曲目榜里会多出一条巨大的「未知曲目」）
        if (record.media.path == UNATTRIBUTED_PATH) {
            totals.unattributedSeconds += record.listenedSeconds
            dirty = true
            if (enqueue) {
                queue.addLast(record)
                while (queue.size > MAX_QUEUE) queue.removeFirst()
            }
            return
        }

        val stat = tracks.getOrPut(record.media.path) {
            TrackStat(
                path = record.media.path,
                title = record.media.title,
                artist = record.media.artist,
                album = record.media.album,
                albumArtist = record.media.albumArtist
            )
        }
        // 元数据可能被用户改过标签，以最新一次为准
        stat.title = record.media.title
        stat.artist = record.media.artist
        stat.album = record.media.album
        stat.albumArtist = record.media.albumArtist
        stat.listenedSeconds += record.listenedSeconds
        stat.sessions += 1
        if (stat.firstPlayedAt == 0L || record.startedAt < stat.firstPlayedAt) {
            stat.firstPlayedAt = record.startedAt
        }
        if (record.endedAt > stat.lastPlayedAt) stat.lastPlayedAt = record.endedAt

        if (enqueue) {
            queue.addLast(record)
            while (queue.size > MAX_QUEUE) queue.removeFirst()
        }
        dirty = true
    }

    fun todaySeconds(now: Long = System.currentTimeMillis()): Long = daily[dateKey(now)] ?: 0L

    fun removeQueued(ids: Set<String>) {
        if (ids.isEmpty()) return
        queue.removeAll { it.id in ids }
        dirty = true
    }

    /** 曲目表上限保护：超出后按最后播放时间淘汰。 */
    private fun prune() {
        if (tracks.size <= MAX_TRACKS) return
        val keep = tracks.entries
            .sortedByDescending { it.value.lastPlayedAt }
            .take(MAX_TRACKS)
            .map { it.key }
            .toHashSet()
        tracks.keys.retainAll(keep)
    }

    companion object {
        const val SCHEMA = 1
        const val MAX_TRACKS = 5000
        const val MAX_QUEUE = 2000

        /** 单次上报里逐曲目聚合的条数上限（按收听时长取前 N）。 */
        const val MAX_REPORT_TRACKS = 2000

        fun dateKey(epochMillis: Long): String =
            Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    }
}
