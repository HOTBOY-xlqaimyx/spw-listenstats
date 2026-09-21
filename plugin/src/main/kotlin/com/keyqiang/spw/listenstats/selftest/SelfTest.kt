package com.keyqiang.spw.listenstats.selftest

import com.keyqiang.spw.listenstats.ConfigValues
import com.keyqiang.spw.listenstats.ConfigKeys
import com.keyqiang.spw.listenstats.Diagnostics
import com.keyqiang.spw.listenstats.describeMedia
import com.keyqiang.spw.listenstats.EngineConfig
import com.keyqiang.spw.listenstats.JsonParser
import com.keyqiang.spw.listenstats.Jv
import com.keyqiang.spw.listenstats.Log
import com.keyqiang.spw.listenstats.UNATTRIBUTED_PATH
import com.keyqiang.spw.listenstats.MediaInfo
import com.keyqiang.spw.listenstats.PlayerState
import com.keyqiang.spw.listenstats.ReportConfig
import com.keyqiang.spw.listenstats.ReportMeta
import com.keyqiang.spw.listenstats.ReportExport
import com.keyqiang.spw.listenstats.Reporter
import com.keyqiang.spw.listenstats.SessionLog
import com.keyqiang.spw.listenstats.SessionRecord
import com.keyqiang.spw.listenstats.StatsEngine
import com.keyqiang.spw.listenstats.StatsStore
import com.keyqiang.spw.listenstats.asArrayOrEmpty
import com.keyqiang.spw.listenstats.asBool
import com.keyqiang.spw.listenstats.asLong
import com.keyqiang.spw.listenstats.asString
import com.keyqiang.spw.listenstats.encode
import com.keyqiang.spw.listenstats.get
import com.keyqiang.spw.listenstats.jArr
import com.keyqiang.spw.listenstats.jBool
import com.keyqiang.spw.listenstats.jNum
import com.keyqiang.spw.listenstats.jObj
import com.keyqiang.spw.listenstats.jStr
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * 纯逻辑自测：不依赖 SPW 宿主，验证 JSON / 存储 / 统计引擎 / HTTP 上报四条链路。
 * 运行：./gradlew selftest（失败时退出码 1）
 */
private val failures = mutableListOf<String>()
private var checks = 0

private fun check(name: String, condition: Boolean, detail: String = "") {
    checks++
    if (!condition) failures += "$name${if (detail.isEmpty()) "" else "（$detail）"}"
}

private fun <T> eq(name: String, expected: T, actual: T) {
    check(name, expected == actual, "期望 $expected，实际 $actual")
}

private fun tempDir(tag: String): File {
    val dir = File(System.getProperty("java.io.tmpdir"), "listenstats-selftest-$tag-${System.nanoTime()}")
    dir.mkdirs()
    return dir
}

private fun newStore(tag: String): Pair<StatsStore, File> {
    val dir = tempDir(tag)
    return StatsStore(File(dir, "listenstats.json"), File(dir, "report-queue.json")) to dir
}

private class FakeClock(var now: Long = 1_760_000_000_000L) {
    fun advance(millis: Long) {
        now += millis
    }
}

private fun media(title: String, path: String) =
    MediaInfo(title = title, artist = "Artist", album = "Album", albumArtist = "AA", path = path)

// ---------------------------------------------------------------- JSON

private fun testJson() {
    val value = jObj(
        "s" to jStr("引号\" 反斜杠\\ 换行\n 制表\t 中文🎵"),
        "n" to jNum(1758000000000L),
        "b" to jBool(true),
        "arr" to jArr(listOf(jNum(1L), jStr("x"), jObj("k" to jStr("v")))),
        "nested" to jObj("a" to jObj("b" to jNum(3L)))
    )
    val text = value.encode()
    val parsed = JsonParser.parse(text)

    eq("JSON 字符串转义往返", "引号\" 反斜杠\\ 换行\n 制表\t 中文🎵", parsed["s"].asString())
    eq("JSON 长整数往返", 1758000000000L, parsed["n"].asLong())
    eq("JSON 布尔往返", true, parsed["b"].asBool())
    eq("JSON 数组长度", 3, parsed["arr"]?.let { (it as Jv.A).items.size } ?: -1)
    eq("JSON 数组元素", "x", parsed["arr"]?.let { (it as Jv.A).items[1] }.asString())
    eq("JSON 嵌套对象", 3L, parsed["nested"]?.get("a")?.get("b").asLong())
    check("JSON 编码不产生裸换行", !text.contains('\n'))

    // 二次解析结果必须稳定
    eq("JSON 二次编码稳定", text, JsonParser.parse(text).encode())

    // 空对象 / 空数组
    eq("JSON 空对象", 0, (JsonParser.parse("{}") as Jv.O).fields.size)
    eq("JSON 空数组", 0, (JsonParser.parse("[]") as Jv.A).items.size)

    // 非法输入必须抛错
    for (bad in listOf("{", "{\"a\":}", "[1,]", "tru", "{\"a\" 1}", "\"未闭合")) {
        val threw = runCatching { JsonParser.parse(bad) }.isFailure
        check("JSON 非法输入被拒绝: $bad", threw)
    }
}

// ---------------------------------------------------------------- 存储

private fun testStoreRoundTrip() {
    val (store, dir) = newStore("store")
    val base = 1_760_000_000_000L
    store.applySession(session("id-1", media("A", "C:\\m\\a.flac"), base, base + 60_000, 60, "ended"), enqueue = true)
    store.applySession(session("id-2", media("B", "C:\\m\\b.flac"), base + 120_000, base + 180_000, 60, "paused"), enqueue = true)
    store.applySession(session("id-3", media("A", "C:\\m\\a.flac"), base + 240_000, base + 270_000, 30, "ended"), enqueue = true)
    store.save { }

    val reloaded = StatsStore(File(dir, "listenstats.json"), File(dir, "report-queue.json"))
    reloaded.load { }
    eq("存储：总时长", 150L, reloaded.totals.listenedSeconds)
    eq("存储：会话数", 3L, reloaded.totals.sessions)
    eq("存储：曲目数", 2, reloaded.tracks.size)
    eq("存储：曲目 A 时长", 90L, reloaded.tracks["C:\\m\\a.flac"]?.listenedSeconds)
    eq("存储：曲目 A 次数", 2L, reloaded.tracks["C:\\m\\a.flac"]?.sessions)
    eq("存储：队列长度", 3, reloaded.queue.size)
    eq("存储：队列首条 id", "id-1", reloaded.queue.first().id)
    eq("存储：按天累计", 150L, reloaded.daily.values.sum())
    eq("存储：标题保留", "A", reloaded.tracks["C:\\m\\a.flac"]?.title)

    // 损坏文件不应让插件崩溃
    File(dir, "listenstats.json").writeText("{ 这不是 JSON")
    val broken = StatsStore(File(dir, "listenstats.json"), File(dir, "report-queue.json"))
    val warnings = mutableListOf<String>()
    broken.load { warnings += it }
    eq("存储：损坏文件后从零开始", 0L, broken.totals.listenedSeconds)
    check("存储：损坏文件有告警", warnings.isNotEmpty())
    check("存储：损坏文件已备份", File(dir, "listenstats.json.corrupt").isFile)
}

private fun session(
    id: String,
    media: MediaInfo,
    startedAt: Long,
    endedAt: Long,
    seconds: Long,
    reason: String,
    continued: Boolean = false
) = SessionRecord(id, media, startedAt, endedAt, seconds, reason, continued)

// ---------------------------------------------------------------- 引擎

private fun engineCase(
    tag: String,
    reportEnabled: Boolean = false,
    enabled: Boolean = true,
    idleGapSeconds: Long = 60,
    countUnknownTrack: Boolean = false,
    body: (StatsStore, StatsEngine, FakeClock) -> Unit
) {
    val (store, _) = newStore(tag)
    val clock = FakeClock()
    val engine = StatsEngine(
        store = store,
        config = { EngineConfig(enabled, idleGapSeconds, reportEnabled, countUnknownTrack) },
        log = { },
        clock = { clock.now }
    )
    body(store, engine, clock)
}

private fun testEngineNormalSession() = engineCase("normal") { store, engine, clock ->
    engine.onMediaItem(media("T1", "C:\\m\\1.flac"))
    engine.onPosition(0)
    var position = 0L
    repeat(60) {
        clock.advance(1000)
        position += 1000
        engine.onPosition(position)
    }
    engine.close("test")
    eq("引擎：正常会话时长", 60L, store.totals.listenedSeconds)
    eq("引擎：正常会话次数", 1L, store.totals.sessions)
    eq("引擎：曲目时长", 60L, store.tracks["C:\\m\\1.flac"]?.listenedSeconds)
}

private fun testEngineSeekNotCounted() = engineCase("seek") { store, engine, clock ->
    engine.onMediaItem(media("T1", "C:\\m\\1.flac"))
    engine.onPosition(0)
    var position = 0L
    repeat(10) {
        clock.advance(1000)
        position += 1000
        engine.onPosition(position)
    }
    // 用户拖到 10 分钟处
    engine.onSeekTo(600_000)
    position = 600_000
    engine.onPosition(position)
    repeat(5) {
        clock.advance(1000)
        position += 1000
        engine.onPosition(position)
    }
    engine.close("test")
    eq("引擎：seek 不计入时长", 15L, store.totals.listenedSeconds)
}

private fun testEnginePauseClosesSession() = engineCase("pause") { store, engine, clock ->
    engine.onMediaItem(media("T1", "C:\\m\\1.flac"))
    engine.onPosition(0)
    var position = 0L
    repeat(20) {
        clock.advance(1000)
        position += 1000
        engine.onPosition(position)
    }
    engine.onPlayState(false)
    eq("引擎：暂停即结算", 1L, store.totals.sessions)
    eq("引擎：暂停结算时长", 20L, store.totals.listenedSeconds)
    engine.onPlayState(true)
    repeat(10) {
        clock.advance(1000)
        position += 1000
        engine.onPosition(position)
    }
    engine.close("test")
    eq("引擎：继续播放开新会话", 2L, store.totals.sessions)
    // 恢复播放后的第一个 tick 只用来开启会话，不累加；所以是 20 + 9
    eq("引擎：两次会话总时长", 29L, store.totals.listenedSeconds)
}

private fun testEngineIdleGapClosesSession() = engineCase("idle", idleGapSeconds = 60) { store, engine, clock ->
    engine.onMediaItem(media("T1", "C:\\m\\1.flac"))
    engine.onPosition(0)
    var position = 0L
    repeat(30) {
        clock.advance(1000)
        position += 1000
        engine.onPosition(position)
    }
    // 模拟暂停：tick 停止 2 分钟，位置不动
    clock.advance(120_000)
    engine.onPosition(position)
    // 恢复播放
    repeat(10) {
        clock.advance(1000)
        position += 1000
        engine.onPosition(position)
    }
    engine.close("test")
    eq("引擎：空闲阈值切分会话", 2L, store.totals.sessions)
    eq("引擎：空闲切分后总时长", 40L, store.totals.listenedSeconds)
}

private fun testEngineTrackChange() = engineCase("track") { store, engine, clock ->
    engine.onMediaItem(media("T1", "C:\\m\\1.flac"))
    engine.onPosition(0)
    var position = 0L
    repeat(30) {
        clock.advance(1000)
        position += 1000
        engine.onPosition(position)
    }
    // 切歌：宿主只会通过歌词回调告知新曲目
    engine.onMediaItem(media("T2", "C:\\m\\2.flac"))
    position = 0
    engine.onPosition(0)
    repeat(20) {
        clock.advance(1000)
        position += 1000
        engine.onPosition(position)
    }
    engine.close("test")
    eq("引擎：切歌分成两个会话", 2L, store.totals.sessions)
    eq("引擎：切歌后总时长", 50L, store.totals.listenedSeconds)
    eq("引擎：曲目 1 时长", 30L, store.tracks["C:\\m\\1.flac"]?.listenedSeconds)
    eq("引擎：曲目 2 时长", 20L, store.tracks["C:\\m\\2.flac"]?.listenedSeconds)
}

private fun testEngineShortSessionDropped() = engineCase("short") { store, engine, clock ->
    engine.onMediaItem(media("T1", "C:\\m\\1.flac"))
    engine.onPosition(0)
    var position = 0L
    repeat(3) {
        clock.advance(1000)
        position += 1000
        engine.onPosition(position)
    }
    engine.onPlayState(false)
    eq("引擎：过短会话不入库", 0L, store.totals.sessions)
    eq("引擎：过短会话不记时长", 0L, store.totals.listenedSeconds)
}

private fun testEngineCheckpoint() = engineCase("checkpoint") { store, engine, clock ->
    engine.onMediaItem(media("T1", "C:\\m\\long.flac"))
    engine.onPosition(0)
    var position = 0L
    repeat(310) {
        clock.advance(1000)
        position += 1000
        engine.onPosition(position)
    }
    engine.close("test")
    eq("引擎：长会话被 checkpoint 分段", 2L, store.totals.sessions)
    eq("引擎：分段后总时长不变", 310L, store.totals.listenedSeconds)
    eq("引擎：分段不重复计入曲目", 310L, store.tracks["C:\\m\\long.flac"]?.listenedSeconds)
}

private fun testEngineLoopWrap() = engineCase("loop") { store, engine, clock ->
    engine.onMediaItem(media("T1", "C:\\m\\1.flac"))
    engine.onPosition(0)
    var position = 0L
    repeat(5) {
        clock.advance(1000)
        position += 1000
        engine.onPosition(position)
    }
    // 单曲循环：位置回绕到 0，不能重复计数，也不应算成新会话
    position = 0
    engine.onPosition(position)
    repeat(5) {
        clock.advance(1000)
        position += 1000
        engine.onPosition(position)
    }
    engine.close("test")
    eq("引擎：单曲循环不重复计数", 10L, store.totals.listenedSeconds)
    eq("引擎：单曲循环仍是 1 个会话", 1L, store.totals.sessions)
}

private fun testEngineSpeedBound() = engineCase("speed") { store, engine, clock ->
    // 2 倍速播放：位置每 tick 推进 2000ms，真实只过 1 秒 → 只能计 1 秒
    engine.onMediaItem(media("T1", "C:\\m\\fast.flac"))
    engine.onPosition(0)
    var position = 0L
    repeat(60) {
        clock.advance(1000)
        position += 2000
        engine.onPosition(position)
    }
    engine.close("test")
    eq("引擎：倍速播放按真实耗时计（不是内容时长）", 60L, store.totals.listenedSeconds)
}

private fun testEngineBurstBound() = engineCase("burst") { store, engine, clock ->
    // 宿主异常：200 次 tick 挤在 10 秒真实时间里补发，内容位置累计 200 秒
    engine.onMediaItem(media("T1", "C:\\m\\burst.flac"))
    engine.onPosition(0)
    var position = 0L
    repeat(200) {
        clock.advance(50)
        position += 1000
        engine.onPosition(position)
    }
    engine.close("test")
    eq("引擎：异常 tick 风暴不虚增时长（按真实 10 秒计）", 10L, store.totals.listenedSeconds)
}

private fun testEngineAttributionLateMediaInfo() = engineCase("attrib", countUnknownTrack = true) { store, engine, clock ->
    // 典型场景：插件加载时那首歌已经在播 —— 位置回调先来、曲目信息迟到。
    // 这段无主时长应当**归因给这首曲目**，而不是丢进「未归因」。
    engine.onPosition(0)
    var position = 0L
    repeat(30) {
        clock.advance(1000)
        position += 1000
        engine.onPosition(position)
    }
    engine.onMediaItem(media("T1", "C:\\m\\1.flac"))
    repeat(10) {
        clock.advance(1000)
        position += 1000
        engine.onPosition(position)
    }
    engine.close("test")
    eq("迟到归因：无主 30s + 后续 10s 全算给真实曲目", 40L, store.tracks["C:\\m\\1.flac"]?.listenedSeconds)
    eq("迟到归因：未归因为 0", 0L, store.totals.unattributedSeconds)
    eq("迟到归因：总量 40s", 40L, store.totals.listenedSeconds)
    check("迟到归因：曲目表里没有未归因哨兵", store.tracks.keys.none { it == UNATTRIBUTED_PATH })
}

private fun testEngineUnattributedWhenTrackChanged() = engineCase("unattrib", countUnknownTrack = true) { store, engine, clock ->
    // 位置回跳 = 期间很可能已经换歌 → 迟到的曲目信息**不能**认领前面那段，只能记「未归因」
    engine.onPosition(0)
    var position = 0L
    repeat(30) {
        clock.advance(1000)
        position += 1000
        engine.onPosition(position)
    }
    clock.advance(1000)
    engine.onPosition(0) // 位置回跳
    position = 0
    repeat(10) {
        clock.advance(1000)
        position += 1000
        engine.onPosition(position)
    }
    engine.onMediaItem(media("T1", "C:\\m\\1.flac"))
    repeat(6) {
        clock.advance(1000)
        position += 1000
        engine.onPosition(position)
    }
    engine.close("test")
    eq("未归因：换歌后的无主时长记入未归因", 40L, store.totals.unattributedSeconds)
    // 新曲目从「曲目信息到达」之后才计时（第一个 tick 只开会话），且要过 5 秒最短会话门槛
    eq("未归因：迟到曲目信息只认领之后的时长", 5L, store.tracks["C:\\m\\1.flac"]?.listenedSeconds)
    check("未归因：曲目表不含哨兵", store.tracks.keys.none { it == UNATTRIBUTED_PATH })
    eq("未归因：总量不丢（40 + 5）", 45L, store.totals.listenedSeconds)
}

private fun testUnattributedDefaultsAndReporting() {
    check("配置：未归因时长默认计入", EngineConfig(true, 60, false).countUnknownTrack)
    check("诊断：描述写明未归因口径", EngineConfig(true, 60, false).describe().contains("未归因时长=计入"))

    val (store, dir) = newStore("unattrib-store")
    val base = 1_760_000_000_000L
    store.applySession(
        session("u1", MediaInfo("未归因", "", "", "", UNATTRIBUTED_PATH), base, base + 120_000, 120, "idle"),
        enqueue = true
    )
    eq("未归因：不建曲目条目", 0, store.tracks.size)
    eq("未归因：单独字段累加", 120L, store.totals.unattributedSeconds)
    eq("未归因：总量包含它", 120L, store.totals.listenedSeconds)
    eq("未归因：按天也包含它", 120L, store.daily.values.sum())
    eq("未归因：会话仍进上报队列", 1, store.queue.size)

    val reporter = Reporter(store, { ReportMeta("id", "0.7.0", "1.18.5", "Steam") })
    val body = JsonParser.parse(reporter.buildBody(emptyList()))
    eq("未归因：上报摘要带该字段", 120L, body["summary"]?.get("unattributedSeconds").asLong())
    eq("未归因：trackStats 里没有哨兵", 0, body["summary"]?.get("trackStats").asArrayOrEmpty().size)

    store.save { }
    val reloaded = StatsStore(File(dir, "listenstats.json"), File(dir, "report-queue.json"))
    reloaded.load { }
    eq("未归因：落盘后可恢复", 120L, reloaded.totals.unattributedSeconds)
}

private fun testEngineUnknownTrackDisabled() = engineCase("unknown-off", countUnknownTrack = false) { store, engine, clock ->
    // 显式关闭：这段时长直接丢弃（与 0.6.x 的默认行为一致）
    engine.onPosition(0)
    var position = 0L
    repeat(30) {
        clock.advance(1000)
        position += 1000
        engine.onPosition(position)
    }
    engine.close("test")
    eq("未归因开关关闭时：不记任何时长", 0L, store.totals.listenedSeconds)
    eq("未归因开关关闭时：未归因字段也是 0", 0L, store.totals.unattributedSeconds)
    check("未归因开关关闭时：不产生曲目条目", store.tracks.isEmpty())
}

private fun testEngineImmediateFlushHook() {
    // 启用上报：会话入队 → 触发一次钩子（「每曲结束时立即上报」的基础）
    var fired = 0
    val (store, _) = newStore("hook-on")
    val clock = FakeClock()
    val engine = StatsEngine(
        store = store,
        config = { EngineConfig(true, 60, true) },
        clock = { clock.now },
        onSessionEnqueued = { fired++ }
    )
    engine.onMediaItem(media("T1", "C:\\m\\1.flac"))
    engine.onPosition(0)
    var position = 0L
    repeat(30) {
        clock.advance(1000)
        position += 1000
        engine.onPosition(position)
    }
    engine.close("test")
    eq("立即上报：会话入队触发一次钩子", 1, fired)
    eq("立即上报：会话确实进了队列", 1, store.queue.size)

    // 未启用上报：不入队、也不触发
    var firedOff = 0
    val (store2, _) = newStore("hook-off")
    val engine2 = StatsEngine(
        store = store2,
        config = { EngineConfig(true, 60, false) },
        clock = { clock.now },
        onSessionEnqueued = { firedOff++ }
    )
    engine2.onMediaItem(media("T1", "C:\\m\\1.flac"))
    engine2.onPosition(0)
    position = 0L
    repeat(30) {
        clock.advance(1000)
        position += 1000
        engine2.onPosition(position)
    }
    engine2.close("test")
    eq("立即上报：未启用上报时不触发钩子", 0, firedOff)
    eq("立即上报：未启用上报时队列为空", 0, store2.queue.size)

    // 钩子抛异常不能影响会话入库（钩子运行在宿主回调线程上）
    val (store3, _) = newStore("hook-throw")
    val engine3 = StatsEngine(
        store = store3,
        config = { EngineConfig(true, 60, true) },
        clock = { clock.now },
        onSessionEnqueued = { throw IllegalStateException("boom") }
    )
    engine3.onMediaItem(media("T1", "C:\\m\\1.flac"))
    engine3.onPosition(0)
    position = 0L
    repeat(30) {
        clock.advance(1000)
        position += 1000
        engine3.onPosition(position)
    }
    engine3.close("test")
    eq("立即上报：钩子抛异常也不影响入库", 30L, store3.totals.listenedSeconds)
}

private fun testEngineDisabled() = engineCase("disabled", enabled = false) { store, engine, clock ->
    engine.onMediaItem(media("T1", "C:\\m\\1.flac"))
    engine.onPosition(0)
    var position = 0L
    repeat(60) {
        clock.advance(1000)
        position += 1000
        engine.onPosition(position)
    }
    engine.close("test")
    eq("引擎：关闭统计后不记录", 0L, store.totals.listenedSeconds)
}

private fun testEngineEnqueue() = engineCase("enqueue", reportEnabled = true) { store, engine, clock ->
    engine.onMediaItem(media("T1", "C:\\m\\1.flac"))
    engine.onPosition(0)
    var position = 0L
    repeat(30) {
        clock.advance(1000)
        position += 1000
        engine.onPosition(position)
    }
    engine.onPlayerState(PlayerState.Ended)
    eq("引擎：启用上报后进入队列", 1, store.queue.size)
    eq("引擎：队列记录时长", 30L, store.queue.first().listenedSeconds)
    eq("引擎：队列结束原因", "ended", store.queue.first().endedReason)
}

// ---------------------------------------------------------------- 上报

private class CaptureServer(private val status: Int) {
    val bodies = mutableListOf<String>()
    val tokens = mutableListOf<String?>()
    val hits = AtomicInteger(0)
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val url: String
        get() = "http://127.0.0.1:${server.address.port}/listen"

    fun start() {
        server.createContext("/listen") { exchange ->
            hits.incrementAndGet()
            bodies += String(exchange.requestBody.readBytes(), Charsets.UTF_8)
            tokens += exchange.requestHeaders.getFirst("X-SPW-Stats-Token")
            val payload = "ok".toByteArray()
            exchange.sendResponseHeaders(status, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
    }

    fun stop() = server.stop(0)
}

private fun testReporterSuccess() {
    val (store, _) = newStore("report-ok")
    val clock = FakeClock()
    val engine = StatsEngine(store, { EngineConfig(true, 60, true) }, { }, { clock.now })
    engine.onMediaItem(media("T1", "C:\\m\\1.flac"))
    engine.onPosition(0)
    var position = 0L
    repeat(45) {
        clock.advance(1000)
        position += 1000
        engine.onPosition(position)
    }
    engine.close("ended")

    val server = CaptureServer(200)
    server.start()
    try {
        val reporter = Reporter(
            store = store,
            meta = { ReportMeta("com.keyqiang.spw.listenstats", "0.4.0", "1.17.0", "Steam") },
            log = { }
        )
        val result = reporter.flush(ReportConfig(true, server.url, "secret-token", "interval", 10))
        check("上报：2xx 判定成功", result.success, result.message)
        eq("上报：发送条数", 1, result.count)
        eq("上报：服务端收到 1 次", 1, server.hits.get())
        eq("上报：成功后队列清空", 0, store.queue.size)
        eq("上报：令牌头", "secret-token", server.tokens.first())

        val body = JsonParser.parse(server.bodies.first())
        eq("上报：协议版本", "spw.listenstats.v1", body["schema"].asString())
        eq("上报：插件版本", "0.4.0", body["pluginVersion"].asString())
        eq("上报：宿主版本", "1.17.0", body["spwVersion"].asString())
        eq("上报：渠道", "Steam", body["channel"].asString())
        eq("上报：会话条数", 1, body["sessions"]?.let { (it as Jv.A).items.size } ?: -1)
        eq("上报：会话时长", 45L, body["sessions"]?.let { (it as Jv.A).items[0] }?.get("listenedSeconds").asLong())
        eq("上报：摘要总时长", 45L, body["summary"]?.get("listenedSeconds").asLong())
    } finally {
        server.stop()
    }
}

private fun testReporterFailureKeepsQueue() {
    val (store, _) = newStore("report-fail")
    val clock = FakeClock()
    val engine = StatsEngine(store, { EngineConfig(true, 60, true) }, { }, { clock.now })
    engine.onMediaItem(media("T1", "C:\\m\\1.flac"))
    engine.onPosition(0)
    var position = 0L
    repeat(30) {
        clock.advance(1000)
        position += 1000
        engine.onPosition(position)
    }
    engine.close("ended")

    val server = CaptureServer(500)
    server.start()
    try {
        val reporter = Reporter(
            store = store,
            meta = { ReportMeta("id", "0.4.0", "1.17.0", "MS") },
            log = { }
        )
        val result = reporter.flush(ReportConfig(true, server.url, "", "interval", 10))
        check("上报：5xx 判定失败", !result.success, result.message)
        eq("上报：失败保留队列", 1, store.queue.size)
    } finally {
        server.stop()
    }

    // 地址为空 / 未启用时不得发请求
    val reporter = Reporter(store, { ReportMeta("id", "0.4.0", "1.17.0", "MS") }, { })
    check("上报：未启用不发请求", !reporter.flush(ReportConfig(false, "", "", "interval", 10)).success)
    check("上报：地址为空不发请求", !reporter.flush(ReportConfig(true, "  ", "", "interval", 10)).success)
}

private fun testReporterSummarySnapshot() {
    // 队列为空 ≠ 无事可做：版本首见 / 本地统计变化时都要补发一份纯摘要（否则报告页永远是旧数据）
    val (store, dir) = newStore("summary-snapshot")
    val base = 1_760_000_000_000L
    store.applySession(session("s1", media("A", "C:\\m\\a.flac"), base, base + 60_000, 60, "ended"), enqueue = false)
    eq("摘要：队列为空", 0, store.queue.size)

    val server = CaptureServer(200)
    server.start()
    try {
        val reporter = Reporter(store, { ReportMeta("id", "0.6.0", "1.18.5", "Steam") }, { })

        val first = reporter.flush(ReportConfig(true, server.url, "", "interval", 10))
        check("摘要：队列为空也要补发（版本首见）", first.success, first.message)
        eq("摘要：首发发送条数为 0", 0, first.count)
        val body = JsonParser.parse(server.bodies.first())
        eq("摘要：报文标记 summaryOnly", true, body["summaryOnly"].asBool())
        eq("摘要：报文里会话数组为空", 0, body["sessions"].asArrayOrEmpty().size)
        eq("摘要：带上全量逐曲目聚合", 1, body["summary"]?.get("trackStats").asArrayOrEmpty().size)

        val second = reporter.flush(ReportConfig(true, server.url, "", "interval", 10))
        check("摘要：没有变化时不重复发", !second.success, second.message)
        eq("摘要：服务端仍只收到 1 次", 1, server.hits.get())

        store.applySession(session("s2", media("B", "C:\\m\\b.flac"), base + 90_000, base + 180_000, 90, "ended"), enqueue = false)
        val third = reporter.flush(ReportConfig(true, server.url, "", "interval", 10))
        check("摘要：本地统计变化后自动补发", third.success, third.message)
        eq("摘要：服务端收到 2 次", 2, server.hits.get())

        val forced = reporter.flush(ReportConfig(true, server.url, "", "interval", 10), force = true)
        check("摘要：手动强制无条件发送", forced.success, forced.message)
        eq("摘要：服务端收到 3 次", 3, server.hits.get())

        val upgraded = Reporter(store, { ReportMeta("id", "0.7.0", "1.18.5", "Steam") }, { })
        val afterUpgrade = upgraded.flush(ReportConfig(true, server.url, "", "interval", 10))
        check("摘要：插件升级后补发一次", afterUpgrade.success, afterUpgrade.message)
        eq("摘要：服务端收到 4 次", 4, server.hits.get())
    } finally {
        server.stop()
    }

    // 记账要落盘：重启后不该因为「版本一样」再发一遍
    store.save { }
    val reloaded = StatsStore(File(dir, "listenstats.json"), File(dir, "report-queue.json"))
    reloaded.load { }
    check("摘要：发送记账已落盘", !reloaded.summaryNeedsSend("0.7.0"))
    check("摘要：换版本号仍需要补发", reloaded.summaryNeedsSend("0.8.0"))
}

private fun testQueueCap() {
    val (store, _) = newStore("cap")
    val base = 1_760_000_000_000L
    repeat(StatsStore.MAX_QUEUE + 50) { index ->
        store.applySession(
            session("id-$index", media("T$index", "C:\\m\\$index.flac"), base, base + index * 1000 + 1000, 10, "ended"),
            enqueue = true
        )
    }
    eq("队列：溢出后保持上限", StatsStore.MAX_QUEUE, store.queue.size)
    eq("队列：保留的是较新的记录", "id-50", store.queue.first().id)
}

private fun testStoreTrackPrune() {
    // 曲目表上限：超出后按「最后播放时间」淘汰最久没播的，总时长不受影响
    val (store, dir) = newStore("prune")
    val base = 1_760_000_000_000L
    repeat(StatsStore.MAX_TRACKS + 10) { index ->
        store.applySession(
            session(
                "id-$index",
                media("T$index", "C:\\m\\$index.flac"),
                base + index * 1000L,
                base + index * 1000L + 10_000L,
                10,
                "ended"
            ),
            enqueue = false
        )
    }
    store.save { }

    val reloaded = StatsStore(File(dir, "listenstats.json"), File(dir, "report-queue.json"))
    reloaded.load { }
    eq("存储：曲目表超上限后淘汰到 " + StatsStore.MAX_TRACKS, StatsStore.MAX_TRACKS, reloaded.tracks.size)
    check("存储：淘汰掉最久没播的那首", !reloaded.tracks.containsKey("C:\\m\\0.flac"))
    check("存储：保留最近播放的那首",
        reloaded.tracks.containsKey("C:\\m\\" + (StatsStore.MAX_TRACKS + 9) + ".flac"))
    eq("存储：淘汰曲目不影响总时长", (StatsStore.MAX_TRACKS + 10) * 10L, reloaded.totals.listenedSeconds)
}

private fun testOfflineReportExport() {
    val (store, dir) = newStore("offline-report")
    val base = 1_760_000_000_000L
    store.applySession(session("a", MediaInfo("A", "X/Y", "Al", "", "C:\\m\\a.flac"), base, base + 60_000, 60, "ended"), enqueue = false)
    store.applySession(session("b", MediaInfo("B", "X/Z", "Al", "", "C:\\m\\b.flac"), base + 90_000, base + 150_000, 60, "ended"), enqueue = false)
    store.applySession(
        session("u", MediaInfo("未归因", "", "", "", UNATTRIBUTED_PATH), base + 200_000, base + 260_000, 60, "idle"),
        enqueue = false
    )
    val meta = ReportMeta("com.keyqiang.spw.listenstats", "0.8.0", "1.18.5", "Steam")
    val d = ReportExport.buildData(store, meta, base + 300_000)

    eq("离线报告：总量含未归因", 180L, d["totals"]?.get("listenedSeconds").asLong())
    eq("离线报告：未归因单独成字段", 60L, d["totals"]?.get("unattributedSeconds").asLong())
    eq("离线报告：曲目数不含未归因", 2L, d["totals"]?.get("tracks").asLong())
    eq("离线报告：曲目榜 2 条", 2, d["tracks"].asArrayOrEmpty().size)
    val artists = d["artists"].asArrayOrEmpty()
    eq("离线报告：多艺人被拆分（X/Y 与 X/Z → X、Y、Z）", 3, artists.size)
    eq("离线报告：拆分后第一名是 X", "X", artists[0]["name"].asString())
    eq("离线报告：多人曲目给每位全额", 120L, artists[0]["seconds"].asLong())
    eq("离线报告：标记为离线快照", true, d["offline"].asBool())
    eq("离线报告：coverage 标全量", true, d["coverage"]?.get("full").asBool())
    eq("离线报告：日期范围取自 daily", store.daily.keys.first(), d["range"]?.get("from").asString())

    val tpl = ReportExport.loadTemplate()
    check("离线报告：插件包内置报告页模板", tpl != null && tpl.contains(ReportExport.MARKER))
    val tricky = "{\"title\":\"</script><b>x\"}"
    val html = ReportExport.inject(tpl!!, tricky)
    check("离线报告：内联数据里的 </script> 被转义", html.contains("<\\/script>") && !html.contains("</script><b>"))
    check("本地报告：注入不破坏页面结构", html.contains("<title>听歌统计</title>") && html.contains("报告页 v"))

    val file = ReportExport.export(store, meta, dir, base + 300_000)
    eq("本地报告：文件名固定（自动刷新覆盖同一份）", ReportExport.FILE_NAME, file.name)
    val text = file.readText()
    check("本地报告：产物自带数据", text.contains("\"listenedSeconds\":180") && text.contains(ReportExport.MARKER))
}

private fun testConfigKeysMatchPreferences() {
    // 防止「加了配置项忘了登记」：配置页声明的 key 必须与 ConfigKeys.ALL 完全一致
    val text = ConfigKeys::class.java.classLoader?.getResourceAsStream("preference_config.json")
        ?.use { it.readBytes().toString(Charsets.UTF_8) }
    check("配置清单：能读到插件内置的 preference_config.json", text != null)
    val root = JsonParser.parse(text!!)
    val declared = mutableListOf<String>()
    for (group in root["configs"].asArrayOrEmpty()) {
        for (pref in group["preferences"].asArrayOrEmpty()) {
            val key = pref["key"].asString()
            if (key.isNotEmpty()) declared += key
        }
    }
    val declaredSet = declared.toSet()
    val knownSet = ConfigKeys.ALL.toSet()
    eq("配置清单：配置页声明的键数量", ConfigKeys.ALL.size, declaredSet.size)
    eq("配置清单：没有「页面上有、代码没登记」的键", emptySet<String>(), declaredSet - knownSet)
    eq("配置清单：没有「代码里有、页面漏了」的键", emptySet<String>(), knownSet - declaredSet)
    check("配置清单：本地报告相关键在内", declaredSet.containsAll(listOf("local.enabled", "local.dir", "report.pageUrl")))
}

private fun testSessionLog() {
    val dir = tempDir("session-log")
    val base = 1_760_000_000_000L

    // ① 追加与裁剪（上限 3）
    val file = File(dir, "sessions.jsonl")
    val log = SessionLog(file, max = 3)
    log.load { }
    eq("会话历史：初始为空", 0, log.size)
    repeat(5) { i ->
        log.append(session("s$i", media("T$i", "C:\\m\\$i.flac"), base + i * 1000, base + i * 1000 + 30_000, 30, "ended"))
    }
    eq("会话历史：超出上限后只保留上限条数", 3, log.size)
    eq("会话历史：最新在前", "T4", log.recent()[0].media.title)
    eq("会话历史：最旧的被丢弃", "T2", log.recent().last().media.title)
    eq("会话历史：裁剪后文件里也只有上限条数", 3, file.readLines().count { it.isNotBlank() })

    // ② 落盘恢复
    val reloaded = SessionLog(file, max = 3)
    reloaded.load { }
    eq("会话历史：落盘后可恢复", 3, reloaded.size)
    eq("会话历史：恢复后内容正确", "T2", reloaded.recent().last().media.title)
    eq("会话历史：结束原因保留", "ended", reloaded.recent()[0].endedReason)
    eq("会话历史：路径保留", "C:\\m\\4.flac", reloaded.recent()[0].media.path)

    // ③ 未超上限时是**追加一行**，不是整份重写
    //    证明方式：手工往文件里塞一行（模拟外部/旧版本写的），再 append 一次 —— 它必须还在
    val appendFile = File(dir, "append.jsonl")
    val appendLog = SessionLog(appendFile, max = 10)
    appendLog.append(session("a1", media("A1", "p1.flac"), base, base + 30_000, 30, "ended"))
    appendLog.append(session("a2", media("A2", "p2.flac"), base + 1000, base + 31_000, 30, "ended"))
    appendFile.appendText(
        "{\"id\":\"manual\",\"title\":\"手工行\",\"artist\":\"\",\"album\":\"\",\"albumArtist\":\"\"," +
            "\"path\":\"p-manual.flac\",\"startedAt\":1,\"endedAt\":2,\"listenedSeconds\":3," +
            "\"endedReason\":\"ended\",\"continued\":false}\n"
    )
    appendLog.append(session("a3", media("A3", "p3.flac"), base + 2000, base + 32_000, 30, "ended"))
    val appendLines = appendFile.readLines().filter { it.isNotBlank() }
    check("会话历史：追加写不重写整个文件（外部行仍在）", appendLines.any { it.contains("manual") })
    eq("会话历史：追加后行数正确（3 + 1 手工）", 4, appendLines.size)

    // ④ 坏行容错：一行损坏只跳过它自己，后面的历史照常载入
    val brokenFile = File(dir, "broken.jsonl")
    brokenFile.writeText(
        "{\"id\":\"ok1\",\"title\":\"好行1\",\"artist\":\"\",\"album\":\"\",\"albumArtist\":\"\"," +
            "\"path\":\"p1\",\"startedAt\":1,\"endedAt\":2,\"listenedSeconds\":3,\"endedReason\":\"ended\",\"continued\":false}\n" +
            "{\"id\":\"bad\",\"title\":\"坏行（缺右括号）\"\n" +
            "{\"id\":\"ok2\",\"title\":\"好行2\",\"artist\":\"\",\"album\":\"\",\"albumArtist\":\"\"," +
            "\"path\":\"p2\",\"startedAt\":4,\"endedAt\":5,\"listenedSeconds\":6,\"endedReason\":\"ended\",\"continued\":false}\n"
    )
    val brokenLog = SessionLog(brokenFile, max = 10)
    val brokenWarnings = mutableListOf<String>()
    brokenLog.load { brokenWarnings += it }
    eq("会话历史：坏行只跳过自己", 2, brokenLog.size)
    eq("会话历史：坏行之后的历史仍载入", "好行2", brokenLog.recent()[0].media.title)
    check("会话历史：坏行有告警", brokenWarnings.any { it.contains("无法解析") })
}

private fun testLocalReportData() {
    val (store, _) = newStore("local-report-data")
    val base = 1_760_000_000_000L
    store.applySession(session("a", media("A", "Art"), base, base + 60_000, 60, "ended"), enqueue = false)
    val meta = ReportMeta("id", "0.9.0", "1.18.5", "Steam")
    val sessions = listOf(
        session("s1", media("A", "Art"), base, base + 60_000, 60, "ended"),
        session("s2", media("B", "Art2"), base + 90_000, base + 150_000, 60, "trackChanged")
    )
    val d = ReportExport.buildData(store, meta, base + 200_000, sessions)
    val recent = d["recentSessions"].asArrayOrEmpty()
    eq("本地报告：最近播放带逐条明细", 2, recent.size)
    eq("本地报告：明细含时长", 60L, recent[0]["seconds"].asLong())
    eq("本地报告：明细含结束原因", "ended", recent[0]["endedReason"].asString())
    check("本地报告：明细含可读时间", recent[0]["startedAtIso"].asString().length >= 16)
    check("本地报告：数据来源标注为本地", d["source"].asString().contains("本地"))
    eq("本地报告：仍带 offline 标记（页面据此隐藏二维码）", true, d["offline"].asBool())

    val dir = tempDir("local-report-dir")
    eq("本地报告目录：配置了就按配置走",
        dir.absolutePath, ReportExport.resolveDir(dir.absolutePath, dir.absolutePath).absolutePath)
    val auto = ReportExport.resolveDir("", dir.absolutePath)
    eq("本地报告目录：未配置时落到子目录", "SPW听歌统计", auto.name)
    check("本地报告目录：父目录真实存在", auto.parentFile?.isDirectory == true)

    val f1 = ReportExport.export(store, meta, dir, base, sessions)
    val f2 = ReportExport.export(store, meta, dir, base + 1000, sessions)
    eq("本地报告：固定文件名", ReportExport.FILE_NAME, f1.name)
    eq("本地报告：刷新覆盖同一份", f1.absolutePath, f2.absolutePath)
    check("本地报告：产物含逐条明细", f2.readText().contains("trackChanged"))
}

private fun testDescribeMedia() {
    // 排障用：空字段必须显式写「(空)」，否则分不清「文件没标签」和「宿主没传」
    val full = describeMedia(MediaInfo("T1", "A1", "Al1", "AA1", "C:\\m\\a.flac"))
    check("描述：字段齐全", full.contains("title=T1") && full.contains("artist=A1") &&
        full.contains("album=Al1") && full.contains("albumArtist=AA1") && full.contains("path=C:\\m\\a.flac"))
    val blank = describeMedia(MediaInfo("T1", "A1", "", "", "C:\\m\\a.flac"))
    check("描述：空 album/albumArtist 显式标注", blank.contains("album=(空)") && blank.contains("albumArtist=(空)"))
    val empty = describeMedia(MediaInfo("", "", "", "", ""))
    check("描述：全空也不抛异常", empty.contains("artist=(空)") && empty.contains("path="))
}

private fun testReportTrackStats() {
    // 上报体里的「逐曲目聚合」：报告页的曲目榜/艺人榜全靠它，必须是全量历史而不是本批会话
    val (store, _) = newStore("report-tracks")
    val base = 1_760_000_000_000L
    store.applySession(session("a", media("A", "C:\\m\\a.flac"), base, base + 60_000, 60, "ended"), enqueue = false)
    store.applySession(session("b", media("B", "C:\\m\\b.flac"), base + 60_000, base + 360_000, 300, "ended"), enqueue = false)
    store.applySession(session("c", media("C", "C:\\m\\c.flac"), base + 360_000, base + 480_000, 120, "ended"), enqueue = false)

    val reporter = Reporter(store, { ReportMeta("com.keyqiang.spw.listenstats", "0.5.0", "1.18.5", "Steam") })
    val root = JsonParser.parse(reporter.buildBody(emptyList()))
    val summary = root["summary"]
    val stats = summary?.get("trackStats").asArrayOrEmpty()

    eq("上报：schema 保持 v1（增量兼容）", "spw.listenstats.v1", root["schema"].asString())
    eq("上报：trackStatsTotal 为本地曲目数", 3L, summary?.get("trackStatsTotal").asLong())
    eq("上报：trackStats 条数", 3, stats.size)
    eq("上报：summary 仍保留曲目计数（旧接收端可读）", 3L, summary?.get("tracks").asLong())
    eq("上报：trackStats 按时长降序排第一", "B", stats[0]["title"].asString())
    eq("上报：trackStats 第一首时长", 300L, stats[0]["listenedSeconds"].asLong())
    eq("上报：trackStats 第一首次数", 1L, stats[0]["sessions"].asLong())
    eq("上报：trackStats 带路径（服务端去重用）", "C:\\m\\b.flac", stats[0]["path"].asString())
    eq("上报：trackStats 末位是最短的", "A", stats[2]["title"].asString())
    eq("上报：trackStats 带首次播放时间", base, stats[2]["firstPlayedAt"].asLong())

    // 上限：条数截断但仍如实告知总数，避免服务端把「榜上只有 2000 首」当成全部
    val (big, _) = newStore("report-tracks-cap")
    repeat(StatsStore.MAX_REPORT_TRACKS + 5) { index ->
        big.applySession(
            session(
                "big-$index",
                media("T$index", "C:\\m\\big$index.flac"),
                base + index * 1000L,
                base + index * 1000L + 5_000L,
                (index % 7).toLong() + 1,
                "ended"
            ),
            enqueue = false
        )
    }
    val capped = JsonParser.parse(
        Reporter(big, { ReportMeta("id", "0.5.0", "1.18.5", "Steam") }).buildBody(emptyList())
    )["summary"]
    eq("上报：超上限时截断到 MAX_REPORT_TRACKS",
        StatsStore.MAX_REPORT_TRACKS, capped?.get("trackStats").asArrayOrEmpty().size)
    eq("上报：截断时总数仍报全量",
        (StatsStore.MAX_REPORT_TRACKS + 5).toLong(), capped?.get("trackStatsTotal").asLong())
}

private fun testReportConfigFlags() {
    val on = ReportConfig(true, "http://192.168.0.104:8199/api/listen", "", "interval", 10)
    check("上报配置：默认启动/退出上报都开", on.describe().contains("启动上报=true") && on.describe().contains("退出上报=true"))
    val off = ReportConfig(true, "http://192.168.0.104:8199/api/listen", "", "interval", 10, onStartup = false, onExit = false)
    check("上报配置：可各自关闭", off.describe().contains("启动上报=false") && off.describe().contains("退出上报=false"))
    check("上报配置：描述仍不泄露路径", !off.describe().contains("/api/listen"))
}

private fun testConfigValueParsing() {
    // 滑块时代残留的畸形值
    eq("配置解析：滑块小数原样保留", 4.36278, ConfigValues.parseNumber(4.36278, 10.0))
    // 档位（entry_values 是字符串）
    eq("配置解析：字符串数字", 10.0, ConfigValues.parseNumber("10", 1.0))
    eq("配置解析：整数类型", 15.0, ConfigValues.parseNumber(15, 1.0))
    // 万一宿主存的是显示文本
    eq("配置解析：带单位文本", 10.0, ConfigValues.parseNumber("10 分钟（推荐）", 1.0))
    eq("配置解析：带后缀文本", 300.0, ConfigValues.parseNumber("300 秒", 60.0))
    // 兜底
    eq("配置解析：null 用默认", 7.0, ConfigValues.parseNumber(null, 7.0))
    eq("配置解析：无法解析用默认", 7.0, ConfigValues.parseNumber("abc", 7.0))
    eq("配置解析：空串用默认", 7.0, ConfigValues.parseNumber("", 7.0))
    // 布尔
    eq("配置解析：布尔真", true, ConfigValues.parseBool("true", false))
    eq("配置解析：布尔假", false, ConfigValues.parseBool("false", true))
    eq("配置解析：数字当布尔", true, ConfigValues.parseBool(1, false))
    eq("配置解析：null 用默认", true, ConfigValues.parseBool(null, true))
    eq("配置解析：未知类型用默认", false, ConfigValues.parseBool(emptyList<String>(), false))
}

private fun testDiagnostics() {
    val lines = mutableListOf<String>()
    val diag = Diagnostics { lines += it }
    diag.callback("onPositionUpdated")
    diag.callback("onPositionUpdated")
    diag.callback("onBeforeLoadLyrics", "title=X")
    eq("诊断：同一回调只在首次写日志", 2, lines.size)
    eq("诊断：回调计数", 2L, diag.count("onPositionUpdated"))
    eq("诊断：回调总计", 3L, diag.totalCallbacks())
    check("诊断：首次触发带上下文", lines.any { it.contains("onBeforeLoadLyrics") && it.contains("title=X") })
    diag.error("onSeekTo", IllegalStateException("boom"))
    eq("诊断：异常计数", 1L, diag.errorCount())
    check("诊断：摘要含计数与异常", diag.summary().contains("onPositionUpdated=2") && diag.summary().contains("异常=1"))
    check("诊断：无回调时摘要可读", Diagnostics { }.summary().contains("尚未收到"))
}

private fun testConfigDescribe() {
    val engine = EngineConfig(true, 60, false).describe()
    check("诊断：引擎描述含关键项", engine.contains("统计=开") && engine.contains("空闲阈值=60s"))
    val report = ReportConfig(true, "http://192.168.0.104:8181/api/listen?token=abc",
        "super-secret-token", "interval", 10).describe()
    check("诊断：上报描述不泄露令牌", !report.contains("super-secret-token") && report.contains("令牌=有(18字符)"))
    check("诊断：上报描述不泄露内网路径", !report.contains("/api/listen") && report.contains("192.168.0.104:8181"))
    check("诊断：空地址可描述", ReportConfig(false, "", "", "exit", 5).describe().contains("(未填)"))
    check("诊断：非法地址不抛异常", ReportConfig(true, "not a url", "", "interval", 5).describe().contains("无法解析"))
}
private fun testLogFormat() {
    check("日志：时间格式", Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""").matches(Log.timestamp(1_760_000_000_000L)))
    check("日志：ISO 带时区", Log.iso(1_760_000_000_000L).contains("+08:00") || Log.iso(1_760_000_000_000L).endsWith("Z"))
}

// ---------------------------------------------------------------- main

fun main() {
    testJson()
    testStoreRoundTrip()
    testEngineNormalSession()
    testEngineSeekNotCounted()
    testEnginePauseClosesSession()
    testEngineIdleGapClosesSession()
    testEngineTrackChange()
    testEngineShortSessionDropped()
    testEngineCheckpoint()
    testEngineLoopWrap()
    testEngineAttributionLateMediaInfo()
    testEngineUnattributedWhenTrackChanged()
    testUnattributedDefaultsAndReporting()
    testEngineUnknownTrackDisabled()
    testEngineImmediateFlushHook()
    testConfigValueParsing()
    testReportConfigFlags()
    testEngineSpeedBound()
    testEngineBurstBound()
    testEngineDisabled()
    testEngineEnqueue()
    testReporterSuccess()
    testReporterFailureKeepsQueue()
    testReporterSummarySnapshot()
    testQueueCap()
    testStoreTrackPrune()
    testReportTrackStats()
    testOfflineReportExport()
    testConfigKeysMatchPreferences()
    testSessionLog()
    testLocalReportData()
    testDescribeMedia()
    testDiagnostics()
    testConfigDescribe()
    testLogFormat()


    println("---- selftest: $checks 项检查 ----")
    if (failures.isEmpty()) {
        println("全部通过 ✅")
    } else {
        println("失败 ${failures.size} 项：")
        failures.forEach { println("  ✗ $it") }
        exitProcess(1)
    }
}
