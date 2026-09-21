package com.keyqiang.spw.listenstats

/** 曲目元数据（从宿主的 MediaItem 拷贝而来，不持有宿主对象）。 */
internal data class MediaInfo(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val path: String
)

/**
 * 一行式媒体描述（**排障用**，不含任何敏感信息）。
 *
 * 空字段显式写「(空)」而不是留白：这样从日志一眼能分清
 * 「文件标签里就没有 albumArtist」和「宿主没把 albumArtist 传过来」——2026-09-22 待查项。
 */
internal fun describeMedia(media: MediaInfo): String =
    "title=" + media.title +
        " artist=" + media.artist.ifBlank { "(空)" } +
        " album=" + media.album.ifBlank { "(空)" } +
        " albumArtist=" + media.albumArtist.ifBlank { "(空)" } +
        " path=" + media.path

/**
 * 「未归因」哨兵路径：宿主没给出曲目信息的那段时长（例如插件加载时已经在播的那首歌）。
 *
 * 它**不进曲目表**，只累加到 `totals.unattributedSeconds` —— 总量与每日照常完整，
 * 但曲目榜/艺人榜不会被一条「未知曲目」污染。
 */
internal const val UNATTRIBUTED_PATH = "<unknown>"

/** 播放器状态（对应 PlaybackExtensionPoint.State）。 */
internal enum class PlayerState { Idle, Buffering, Ready, Ended }

/** 一次收听会话（已结束，可入库/可上报）。 */
internal data class SessionRecord(
    val id: String,
    val media: MediaInfo,
    val startedAt: Long,
    val endedAt: Long,
    val listenedSeconds: Long,
    val endedReason: String,
    val continued: Boolean
)

internal class Totals {
    var listenedSeconds: Long = 0
    var sessions: Long = 0
    var firstPlayedAt: Long = 0
    var lastPlayedAt: Long = 0

    /** 其中「宿主没给出曲目信息」的那部分时长（已含在 [listenedSeconds] 里）。 */
    var unattributedSeconds: Long = 0
}

internal class TrackStat(
    var path: String,
    var title: String,
    var artist: String,
    var album: String,
    var albumArtist: String
) {
    var listenedSeconds: Long = 0
    var sessions: Long = 0
    var firstPlayedAt: Long = 0
    var lastPlayedAt: Long = 0
}

/** 引擎运行时配置（每次 tick 现读，改配置立即生效）。 */
internal class EngineConfig(
    val enabled: Boolean,
    val idleGapSeconds: Long,
    val reportEnabled: Boolean,
    /**
     * 宿主未给出曲目信息时，是否把这段时间计入总量（记为「未归因」，默认**是**）。
     *
     * 默认开的原因：这段是真实在听的时间，丢掉会让总量/每日对不上；
     * 而记成独立字段又不会污染曲目榜。想丢弃就把它关掉。
     */
    val countUnknownTrack: Boolean = DEFAULT_COUNT_UNKNOWN
) {
    companion object {
        const val DEFAULT_COUNT_UNKNOWN = true
    }

    /** 一行式描述，用于诊断日志（不含敏感信息）。 */
    fun describe(): String =
        "统计=" + (if (enabled) "开" else "关") +
            " 空闲阈值=" + idleGapSeconds + "s" +
            " 上报开关=" + reportEnabled +
            " 未归因时长=" + (if (countUnknownTrack) "计入" else "丢弃")
}

/** 上报配置。 */
internal class ReportConfig(
    val enabled: Boolean,
    val url: String,
    val token: String,
    val mode: String,
    val intervalMinutes: Long,
    /** 插件启动时若队列有积压，是否立刻补发一次（默认是）。 */
    val onStartup: Boolean = true,
    /** 退出时是否发一次（插件停用 / JVM 退出钩子，默认是）。 */
    val onExit: Boolean = true,
    /**
     * 报告页地址（可选）：配置页「打开报告页」按钮用它在浏览器里打开。
     *
     * 纯本地便利功能：**不参与上报**，也不要求上报开着 —— 它只是省得你记 URL。
     */
    val pageUrl: String = ""
) {
    /**
     * 一行式描述，用于诊断日志。
     *
     * ⚠️ **只输出 scheme://host:port 与路径长度，绝不含路径/查询串/令牌**——
     * 用户会把日志整份发出来排障，不能因为排障而泄露上报令牌或内网路径。
     */
    fun describe(): String {
        val target = if (url.isBlank()) {
            "(未填)"
        } else {
            runCatching {
                val uri = java.net.URI(url)
                val port = if (uri.port > 0) ":" + uri.port else ""
                uri.scheme + "://" + uri.host + port + "/…(路径" + (uri.path?.length ?: 0) + "字符)"
            }.getOrElse { "(地址无法解析)" }
        }
        val tokenInfo = if (token.isEmpty()) "无" else "有(" + token.length + "字符)"
        return "上报=" + (if (enabled) "开" else "关") +
            " 报告页=" + (if (pageUrl.isBlank()) "未填" else "已填(" + pageUrl.length + "字符)") +
            " 目标=" + target +
            " 令牌=" + tokenInfo +
            " 模式=" + mode +
            " 间隔=" + intervalMinutes + "min" +
            " 启动上报=" + onStartup +
            " 退出上报=" + onExit
    }
}

/**
 * 配置值的宽容解析。
 *
 * 宿主存的类型不确定（Boolean / Int / Float / String 都可能），而且改成**档位选择**后
 * 有可能存的是显示文本（例如 `"10 分钟"`）。这里统一兜住，避免因为一个畸形值回退到默认。
 */
internal object ConfigValues {

    fun parseBool(value: Any?, default: Boolean): Boolean = when (value) {
        is Boolean -> value
        is String -> value.equals("true", ignoreCase = true)
        is Number -> value.toInt() != 0
        else -> default
    }

    /** 支持 `4.36278`、`"10"`、`"10 分钟"`、`"60 秒（推荐）"`；解析不出就用 default。 */
    fun parseNumber(value: Any?, default: Double): Double = when (value) {
        is Number -> value.toDouble()
        is String -> value.trim()
            .takeWhile { it.isDigit() || it == '.' || it == '-' }
            .toDoubleOrNull() ?: default
        else -> default
    }
}
