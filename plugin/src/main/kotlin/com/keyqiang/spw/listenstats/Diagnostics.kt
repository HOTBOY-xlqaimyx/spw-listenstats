package com.keyqiang.spw.listenstats

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 现场诊断：回调计数、首次触发记录、异常计数。
 *
 * 目的很直接：**用户把 listenstats.log 整份发过来，就能判断问题出在哪一环**，例如
 * - 一行回调都没有 → 宿主没调用扩展点（或根本没在播放）
 * - 有 `onPositionUpdated` 但没有 `onBeforeLoadLyrics` → 拿不到曲目信息，不会入库
 * - 有回调但 `engine=null` → 插件没启动成功（看初始化日志）
 */
internal class Diagnostics(private val log: (String) -> Unit) {

    private val counts = ConcurrentHashMap<String, Long>()
    private val firstSeen = ConcurrentHashMap.newKeySet<String>()
    private val errors = AtomicLong(0)

    /** 记录一次回调；**首次**出现时写一行日志，之后只累加计数（避免刷爆日志）。 */
    fun callback(name: String, detail: String = "") {
        counts.merge(name, 1L, Long::plus)
        if (firstSeen.add(name)) {
            log("回调首次触发: $name${if (detail.isEmpty()) "" else "（$detail）"}")
        }
    }

    /** 扩展点内部异常：既计数也写进日志文件（以前只打到 stdout，用户发日志会漏掉）。 */
    fun error(context: String, throwable: Throwable) {
        errors.incrementAndGet()
        log("扩展点回调异常[$context]: ${throwable.javaClass.name}: ${throwable.message}")
    }

    fun count(name: String): Long = counts[name] ?: 0L

    fun hasAnyCallback(): Boolean = counts.isNotEmpty()

    fun totalCallbacks(): Long = counts.values.sum()

    fun errorCount(): Long = errors.get()

    /** 一行式计数摘要，便于在日志里横向比对。 */
    fun summary(): String {
        if (counts.isEmpty()) return "（尚未收到任何播放回调）"
        return counts.entries
            .sortedByDescending { it.value }
            .joinToString(" ") { "${it.key}=${it.value}" } + " 异常=${errors.get()}"
    }
}
