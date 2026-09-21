package com.keyqiang.spw.listenstats

import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

internal class ReportMeta(
    val pluginId: String,
    val pluginVersion: String,
    val spwVersion: String,
    val channel: String
)

internal class FlushResult(
    val success: Boolean,
    val count: Int,
    val message: String
)

/**
 * 上报器：把「待上报队列」批量 POST 到用户自己的服务端。
 *
 * 协议见 DESIGN.md 第 3 节 `spw.listenstats.v1`：
 * 只有 2xx 才出队，失败保留到下一轮重试；调用方负责放到后台线程。
 *
 * ⚠️ 这里**刻意使用 `HttpURLConnection`（java.base）而不是 `java.net.http.HttpClient`**：
 * 宿主是 jpackage/jlink 出来的裁剪运行时，模块清单里不一定有 `java.net.http`
 * —— 2026-09-17 在真实 Linux 版（Salt Player 1.18.0，JDK 25）上实测：
 * 只有 `java.base java.desktop java.xml java.sql jdk.crypto.ec ...`，
 * 用 HttpClient 会直接 `NoClassDefFoundError: java/net/http/HttpClient` 把插件初始化打挂。
 */
internal class Reporter(
    private val store: StatsStore,
    private val meta: () -> ReportMeta,
    private val log: (String) -> Unit = {}
) {

    fun flush(
        config: ReportConfig,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
        force: Boolean = false
    ): FlushResult {
        if (!config.enabled) return FlushResult(false, 0, "上报未启用")
        if (config.url.isBlank()) return FlushResult(false, 0, "未填上报地址")

        val batch = store.queue.take(BATCH_SIZE)
        val summaryOnly = batch.isEmpty()
        var connection: HttpURLConnection? = null
        return try {
            val info = meta()
            // 队列为空 ≠ 无事可做：版本升级或本地统计变化后，服务端手上的摘要已经过期，
            // 这时要发一份「只有 summary、sessions 为空」的快照，否则报告页会一直显示旧数据。
            if (summaryOnly && !force && !store.summaryNeedsSend(info.pluginVersion)) {
                return FlushResult(false, 0, "没有待上报数据")
            }

            val body = buildBody(batch, info)
            connection = (URL(config.url.trim()).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                instanceFollowRedirects = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("User-Agent", "SPW-ListenStats/${info.pluginVersion}")
                if (config.token.isNotBlank()) {
                    setRequestProperty("X-SPW-Stats-Token", config.token.trim())
                }
            }

            val payload = body.toByteArray(StandardCharsets.UTF_8)
            connection.outputStream.use { out: OutputStream -> out.write(payload) }

            val code = connection.responseCode
            val responseText = readResponse(connection, code)
            if (code in 200..299) {
                store.removeQueued(batch.map { it.id }.toSet())
                store.markSummarySent(info.pluginVersion)
                store.save(log)
                val what = if (summaryOnly) "摘要快照" else "${batch.size} 条会话"
                log("上报成功 $what（HTTP $code）")
                FlushResult(true, batch.size, "HTTP $code")
            } else {
                log("上报失败 HTTP $code：${responseText.take(200)}")
                FlushResult(false, 0, "HTTP $code")
            }
        } catch (t: Throwable) {
            log("上报异常：${t.message}")
            FlushResult(false, 0, t.message ?: t.javaClass.simpleName)
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun readResponse(connection: HttpURLConnection, code: Int): String = runCatching {
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
    }.getOrDefault("")

    fun buildBody(batch: List<SessionRecord>, info: ReportMeta = meta()): String {
        val now = System.currentTimeMillis()
        val sessions = batch.map { record ->
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
        return jObj(
            "schema" to jStr(SCHEMA),
            "pluginId" to jStr(info.pluginId),
            "pluginVersion" to jStr(info.pluginVersion),
            "spwVersion" to jStr(info.spwVersion),
            "channel" to jStr(info.channel),
            "sentAt" to jNum(now),
            "sentAtIso" to jStr(Log.iso(now)),
            // 服务端据此区分「纯摘要补发」与「带会话的批次」（只影响日志可读性，不影响处理）
            "summaryOnly" to jBool(batch.isEmpty()),
            "sessions" to jArr(sessions),
            "summary" to store.summary()
        ).encode()
    }

    companion object {
        const val SCHEMA = "spw.listenstats.v1"
        const val BATCH_SIZE = 200
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 15_000

        /** 退出路径专用：短超时，保证关软件时不会被网络拖住。 */
        const val EXIT_CONNECT_TIMEOUT_MS = 3_000
        const val EXIT_READ_TIMEOUT_MS = 5_000
    }
}
