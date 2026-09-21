package com.keyqiang.spw.listenstats

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 本地**会话历史**（逐条明细），用于本地报告的「最近播放」时间线。
 *
 * 为什么不塞进 `listenstats.json`：那是累计统计（很小、要频繁原子替换），
 * 而逐条明细是**追加型**的流水，混在一起会让每次落盘都变重。
 *
 * - 格式：`sessions.jsonl`，一行一条（追加写）
 * - 上限 [MAX] 条：超出后丢最旧的，并整份重写一次（2000 行 ≈ 400 KB，重写代价可忽略）
 * - 与上报队列无关：**队列发完就删**，这里才是本地的历史留档
 */
internal class SessionLog(
    private val file: File,
    private val max: Int = MAX
) {
    private val items = ArrayDeque<SessionRecord>()

    val size: Int get() = items.size

    fun load(warn: (String) -> Unit = {}) {
        items.clear()
        if (!file.isFile) return
        val lines = runCatching { file.readLines(StandardCharsets.UTF_8) }
            .onFailure { warn("读取 ${file.name} 失败：${it.message}") }
            .getOrNull() ?: return

        // ⚠️ 逐行容错：写入中断会留下半行 JSON，**只跳过那一行**，
        // 不能让一行坏数据把后面所有历史都吞掉（0.9.3 及以前就是这个 bug）。
        var broken = 0
        for (line in lines) {
            val text = line.trim()
            if (text.isEmpty()) continue
            val record = runCatching { parseLine(text) }.getOrNull()
            if (record == null) {
                broken++
                continue
            }
            items.addLast(record)
        }
        if (broken > 0) warn("${file.name} 有 $broken 行无法解析，已跳过（其余 ${items.size} 条正常载入）")
        while (items.size > max) items.removeFirst()
    }

    private fun parseLine(text: String): SessionRecord {
        val root = JsonParser.parse(text)
        return SessionRecord(
            id = root["id"].asString(),
            media = MediaInfo(
                title = root["title"].asString(),
                artist = root["artist"].asString(),
                album = root["album"].asString(),
                albumArtist = root["albumArtist"].asString(),
                path = root["path"].asString()
            ),
            startedAt = root["startedAt"].asLong(),
            endedAt = root["endedAt"].asLong(),
            listenedSeconds = root["listenedSeconds"].asLong(),
            endedReason = root["endedReason"].asString("unknown"),
            continued = root["continued"].asBool(false)
        )
    }

    /**
     * 追加一条并落盘。
     *
     * - 正常路径只**追加一行**（≈250 B），不重写整个文件 —— 历史到 2000 条时整份重写是 500 KB，
     *   每结算一首歌写一次在慢盘上可感知（0.9.3 及以前就是这么干的）。
     * - 只有超过上限需要裁剪时，才整份重写一次（最旧的被丢掉）。
     */
    fun append(record: SessionRecord, warn: (String) -> Unit = {}) {
        items.addLast(record)
        val overflow = items.size > max
        while (items.size > max) items.removeFirst()

        if (overflow) {
            write(warn)
            warn("会话历史超过 $max 条，已丢弃最旧的记录（${file.name}）")
            return
        }
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(recordToJson(record).encode() + "\n", StandardCharsets.UTF_8)
            runCatching { file.setReadable(true, false) }
        }.onFailure {
            warn("追加 ${file.name} 失败：${it.message}")
            write(warn)   // 追加失败就退回整份重写，尽量别丢这条
        }
    }

    /** 最近 [limit] 条，倒序（最新在前）。 */
    fun recent(limit: Int = max): List<SessionRecord> =
        items.toList().asReversed().take(limit)

    private fun write(warn: (String) -> Unit) {
        runCatching {
            file.parentFile?.mkdirs()
            val sb = StringBuilder()
            for (record in items) {
                sb.append(recordToJson(record).encode()).append('\n')
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(sb.toString(), StandardCharsets.UTF_8)
            try {
                Files.move(
                    tmp.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Throwable) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            runCatching { file.setReadable(true, false) }
        }.onFailure { warn("写入 ${file.name} 失败：${it.message}") }
    }

    private fun recordToJson(record: SessionRecord): Jv.O = jObj(
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

    companion object {
        /** 滚动保留的会话条数上限（≈ 几百小时的收听明细，文件约 400 KB）。 */
        const val MAX = 2000
    }
}
