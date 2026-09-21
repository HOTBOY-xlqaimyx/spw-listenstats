package com.keyqiang.spw.listenstats

import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 极简文件日志（带单文件轮转），同时打印到标准输出方便在宿主控制台查看。 */
internal class Log(
    private val file: File,
    private val maxBytes: Long = 512L * 1024
) {
    private val lock = Any()

    fun info(message: String) = write("INFO", message)

    fun warn(message: String) = write("WARN", message)

    fun error(message: String, throwable: Throwable? = null) {
        write("ERROR", if (throwable == null) message else "$message: ${throwable.message}")
    }

    private fun write(level: String, message: String) {
        val line = "${timestamp()} [$level] $message"
        println("[ListenStats] $line")
        synchronized(lock) {
            runCatching {
                file.parentFile?.mkdirs()
                if (file.length() > maxBytes) {
                    val backup = File(file.parentFile, file.name + ".1")
                    if (backup.exists()) backup.delete()
                    file.renameTo(backup)
                }
                file.appendText(line + System.lineSeparator(), StandardCharsets.UTF_8)
            }
        }
    }

    companion object {
        private val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        fun timestamp(epochMillis: Long = System.currentTimeMillis()): String =
            FORMATTER.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

        fun iso(epochMillis: Long): String =
            Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toString()

        /** 无文件日志（自测或初始化失败时使用）。 */
        fun stdout(): Log = Log(File(System.getProperty("java.io.tmpdir"), "listenstats-fallback.log"))
    }
}
