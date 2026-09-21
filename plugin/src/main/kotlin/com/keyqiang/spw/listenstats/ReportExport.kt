package com.keyqiang.spw.listenstats

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * **本地报告**：把「报告页 HTML」和「当前统计」拼成**一个自包含 HTML 文件**。
 *
 * 定位（2026-09-22 明确）：**本地是默认形态，NAS/服务端是可选扩展**。
 * 所以它不是"离线备份"，而是插件一直维护的那份报告：会话结算后自动刷新（覆盖同一份），
 * 双击就能看，`file://` 打开也不受 CORS 限制，**完全不依赖接收端**。
 *
 * 口径与接收端 `spw-receiver` 的聚合**保持一致**（同一份页面认同一套字段）：
 * - `tracks`：本地逐曲目聚合，按时长降序（≤2000 条）
 * - `artists`：按 `/`、`、`、`;`、`；` 拆分，多艺人各记全额（合计可能大于总时长）
 * - `unattributedSeconds`：宿主没给出曲目信息的那段（计入总量，不进榜单）
 */
internal object ReportExport {

    /** 报告页模板在插件包里的资源路径（`src/main/resources/web/report.html`）。 */
    const val RESOURCE = "web/report.html"

    const val MARKER = "<script id=\"spw-inline-data\" type=\"application/json\">"

    /** 本地报告固定文件名（自动刷新时覆盖这一份，永远是最新的）。 */
    const val FILE_NAME = "ListenStats-本地报告.html"

    /** 本地报告默认目录名（放在「我的文档」下）。 */
    const val DIR_NAME = "SPW听歌统计"

    /** 本地报告里展示的「最近播放」条数上限（会话历史本身保留 2000 条）。 */
    const val MAX_RECENT_SESSIONS = 300

    /** 读取插件内置的报告页模板；读不到返回 null（由调用方提示）。 */
    fun loadTemplate(): String? = runCatching {
        ReportExport::class.java.classLoader?.getResourceAsStream(RESOURCE)
            ?.use { it.readBytes().toString(StandardCharsets.UTF_8) }
    }.getOrNull()

    /**
     * 把数据注入模板。
     *
     * ⚠️ JSON 里的 `</` 必须转义成 `<\/`：否则一旦曲目名里带 `</script>`，内联脚本会被提前闭合
     * （页面直接白屏，而且是用户数据触发的，很难排查）。
     */
    fun inject(template: String, dataJson: String): String {
        val start = template.indexOf(MARKER)
        require(start >= 0) { "报告页模板缺少数据占位标记" }
        val bodyStart = start + MARKER.length
        val end = template.indexOf("</script>", bodyStart)
        require(end >= 0) { "报告页模板的数据块没有闭合" }
        val safe = dataJson.replace("</", "<\\/")
        return template.substring(0, bodyStart) + safe + template.substring(end)
    }

    /** 按接收端的字段口径生成报告数据（[sessions] 为本地会话历史，用于「最近播放」）。 */
    fun buildData(
        store: StatsStore,
        meta: ReportMeta,
        now: Long,
        sessions: List<SessionRecord> = emptyList()
    ): Jv.O {
        val totals = store.totals

        val tracks = store.tracks.values
            .sortedByDescending { it.listenedSeconds }
            .take(StatsStore.MAX_REPORT_TRACKS)
            .map { stat ->
                jObj(
                    "title" to jStr(stat.title.ifBlank { "未知曲目" }),
                    "artist" to jStr(stat.artist),
                    "album" to jStr(stat.album),
                    "albumArtist" to jStr(stat.albumArtist),
                    "path" to jStr(stat.path),
                    "seconds" to jNum(stat.listenedSeconds),
                    "sessions" to jNum(stat.sessions),
                    "firstPlayedAt" to jNum(stat.firstPlayedAt),
                    "lastPlayedAt" to jNum(stat.lastPlayedAt)
                )
            }

        val agg = LinkedHashMap<String, ArtistAgg>()
        for (stat in store.tracks.values) {
            val names = splitArtists(stat.artist)
                .ifEmpty { splitArtists(stat.albumArtist) }
                .ifEmpty { listOf("未知艺人") }
            for (name in names.toHashSet()) {
                val item = agg.getOrPut(name) { ArtistAgg(name) }
                item.seconds += stat.listenedSeconds
                item.tracks += 1
                item.sessions += stat.sessions
            }
        }
        val artists = agg.values
            .sortedWith(compareByDescending<ArtistAgg> { it.seconds }.thenBy { it.name })
            .map { a ->
                jObj(
                    "name" to jStr(a.name),
                    "seconds" to jNum(a.seconds),
                    "tracks" to jNum(a.tracks.toLong()),
                    "sessions" to jNum(a.sessions)
                )
            }

        val days = store.daily.keys.sorted()
        val daily = days.map { day -> jObj("date" to jStr(day), "seconds" to jNum(store.daily[day] ?: 0L)) }

        return jObj(
            "generatedAt" to jStr(Log.iso(now)),
            "version" to jStr("offline"),
            "source" to jStr("插件本地统计（离线导出，非 NAS 上报）"),
            "offline" to jBool(true),
            "batches" to jNum(0L),
            "pluginVersion" to jStr(meta.pluginVersion),
            "spwVersion" to jStr(meta.spwVersion),
            "channel" to jStr(meta.channel),
            "trackSource" to jStr("trackStats"),
            "range" to jObj(
                "from" to jStr(days.firstOrNull() ?: ""),
                "to" to jStr(days.lastOrNull() ?: ""),
                "days" to jNum(days.size.toLong()),
                "firstPlayedAt" to jNum(totals.firstPlayedAt),
                "lastPlayedAt" to jNum(totals.lastPlayedAt)
            ),
            "totals" to jObj(
                "listenedSeconds" to jNum(totals.listenedSeconds),
                "sessions" to jNum(totals.sessions),
                "tracks" to jNum(store.tracks.size.toLong()),
                "unattributedSeconds" to jNum(totals.unattributedSeconds)
            ),
            "coverage" to jObj(
                "sessionsReported" to jNum(totals.sessions),
                "sessionsTotal" to jNum(totals.sessions),
                "tracksReported" to jNum(store.tracks.size.toLong()),
                "tracksTotal" to jNum(store.tracks.size.toLong()),
                "full" to jBool(true)
            ),
            "daily" to jArr(daily),
            "artists" to jArr(artists),
            "tracks" to jArr(tracks),
            "recentSessions" to jArr(
                sessions.take(MAX_RECENT_SESSIONS).map { s ->
                    jObj(
                        "title" to jStr(s.media.title.ifBlank { "未知曲目" }),
                        "artist" to jStr(s.media.artist),
                        "album" to jStr(s.media.album),
                        "seconds" to jNum(s.listenedSeconds),
                        "startedAt" to jNum(s.startedAt),
                        "endedAt" to jNum(s.endedAt),
                        "startedAtIso" to jStr(Log.iso(s.startedAt)),
                        "endedReason" to jStr(s.endedReason)
                    )
                }
            )
        )
    }

    /**
     * 本地报告目录：配置了就用配置的；没配就按「好找」的顺序探测
     * （我的文档 → OneDrive 文档 → 桌面 → 用户主目录），并在其下建 [DIR_NAME] 子目录。
     */
    fun resolveDir(configured: String, home: String = System.getProperty("user.home") ?: "."): File {
        val custom = configured.trim()
        if (custom.isNotEmpty()) return File(custom)
        val root = File(home)
        val candidates = listOf(
            File(root, "Documents"),
            File(root, "OneDrive" + File.separator + "Documents"),
            File(root, "Desktop"),
            root
        )
        val base = candidates.firstOrNull { it.isDirectory } ?: root
        return File(base, DIR_NAME)
    }

    /** 生成/刷新本地报告（覆盖同一个文件），返回文件。 */
    fun export(
        store: StatsStore,
        meta: ReportMeta,
        dir: File,
        now: Long,
        sessions: List<SessionRecord> = emptyList()
    ): File {
        val template = loadTemplate() ?: error("插件包内缺少报告页模板（$RESOURCE）")
        val data = buildData(store, meta, now, sessions).encode()
        val html = inject(template, data)
        dir.mkdirs()
        val file = File(dir, FILE_NAME)
        file.writeText(html, StandardCharsets.UTF_8)
        runCatching { file.setReadable(true, false) }
        return file
    }

    private class ArtistAgg(val name: String) {
        var seconds: Long = 0
        var tracks: Int = 0
        var sessions: Long = 0
    }

    /** 与接收端一致的多艺人拆分符（刻意不拆 `&`："Simon & Garfunkel" 是同一个艺人）。 */
    private val ARTIST_SEPS = charArrayOf('/', '、', ';', '；')

    private fun splitArtists(value: String): List<String> =
        value.split(*ARTIST_SEPS).map { it.trim() }.filter { it.isNotEmpty() }
}
