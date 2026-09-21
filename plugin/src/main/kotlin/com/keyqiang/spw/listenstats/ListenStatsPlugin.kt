@file:OptIn(UnstableSpwWorkshopApi::class)

package com.keyqiang.spw.listenstats

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import com.xuncorp.spw.workshop.api.config.ConfigManager
import java.awt.Desktop
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 听歌统计插件主类。
 *
 * 职责：装配统计引擎 / 存储 / 上报器，管理宿主生命周期与定时任务，
 * 暴露配置页按钮反射调用的静态入口，并输出**足以远程排障**的日志。
 *
 * 排障日志的约定（用户发一份 `listenstats.log` 过来即可定位）：
 * - `==== ListenStats 诊断信息（…）====` 起头的诊断块：插件/宿主/运行环境/路径/配置原始值/运行时探测/数据量
 * - `回调首次触发: xxx`：证明宿主确实在调用扩展点，并给出首次调用的上下文
 * - `回调统计: …`：每 5 分钟一行计数，看回调是否持续在来
 * - `开始统计/记录会话/丢弃过短会话`：统计引擎的会话生命周期
 * - `已落盘: …`：每次写盘后的数据量
 * - `上报…`：上报链路每一步（含 URL 主机、队列长度、HTTP 码）
 */
class ListenStatsPlugin(pluginContext: PluginContext) : SpwPlugin(pluginContext) {

    private var logger: Log = Log.stdout()
    private var configManager: ConfigManager? = null

    /** 本地会话历史（逐条明细，给本地报告的「最近播放」用）。 */
    private var sessionLog: SessionLog? = null

    /** 本地报告最近一次刷新时间（节流，避免每次结算都写盘）。 */
    private var lastLocalReportAt = 0L

    /** 本地报告最近一次产物路径（配置页「打开本地报告」用）。 */
    private var localReportFile: File? = null

    /** 统计有变化、本地报告还没跟上（由 tick 节流刷新）。 */
    @Volatile
    private var reportDirty = false
    private var configHelper: ConfigHelper? = null
    private var store: StatsStore? = null
    private var reporter: Reporter? = null
    private var engine: StatsEngine? = null
    private var scheduler: ScheduledExecutorService? = null
    private var diagnostics: Diagnostics? = null

    /** 插件数据目录（由宿主给出的配置文件路径反推，不硬编码 %APPDATA%）。 */
    private var dataDir: File? = null

    private val lastFlushAt = AtomicLong(0L)

    /** JVM 退出钩子（正常关窗退出时兜底上报）。 */
    @Volatile
    private var exitHook: Thread? = null
    private val tickCount = AtomicLong(0L)
    private val noCallbackWarned = AtomicLong(0L)

    override fun start() {
        instance = this
        try {
            initRuntime()
            logger.info("插件启动事件（宿主调用 start）")
            writeDiagnostics("启动")
            registerExitHook()
            scheduleStartupFlush()
            refreshLocalReport(force = true)
            toast(
                "听歌统计已启动，累计 ${StatsEngine.formatDuration(store?.totals?.listenedSeconds ?: 0L)}",
                ToastSuccess
            )
        } catch (t: Throwable) {
            println("[ListenStats] 初始化失败：$t")
            logger.error("初始化失败，插件将不工作", t)
        }
    }

    override fun stop() {
        shutdown("pluginStop", "听歌统计已停止", ToastWarning)
    }

    override fun delete() {
        shutdown("pluginDelete", "听歌统计已被删除（数据保留在数据目录）", ToastError)
    }

    override fun update() {
        logger.info("插件已通过 SPW 更新")
        toast("听歌统计已更新到 ${pluginContext.pluginVersion}", ToastSuccess)
    }

    // ---------------- 装配 ----------------

    private fun initRuntime() {
        val manager = WorkshopApi.manager.createConfigManager()
        configManager = manager
        val helper = manager.getConfig()
        configHelper = helper

        val dir = helper.getConfigPath().parent?.toFile()
            ?: File(System.getProperty("user.home"), "spw-listenstats")
        dir.mkdirs()
        dataDir = dir

        logger = Log(File(dir, "listenstats.log"))
        diagnostics = Diagnostics(logger::info)

        val statsStore = StatsStore(
            File(dir, "listenstats.json"),
            File(dir, "report-queue.json")
        )
        statsStore.load(logger::warn)
        store = statsStore

        val history = SessionLog(File(dir, "sessions.jsonl"))
        history.load(logger::warn)
        sessionLog = history

        // 可选功能（上报）不能拖垮核心功能（统计）：构造失败就只做本地统计
        reporter = runCatching { Reporter(statsStore, ::reportMeta, logger::info) }
            .onFailure { logger.error("上报器初始化失败（本地统计不受影响）", it) }
            .getOrNull()
        engine = StatsEngine(
            statsStore,
            ::engineConfig,
            logger::info,
            onSessionEnqueued = ::onSessionEnqueued,
            onSessionClosed = ::onSessionClosed
        )

        runCatching {
            manager.addConfigChangeListener { changed ->
                logger.info("配置已更改：${changed.getConfigPath()}")
                logger.info("配置生效值: ${engineConfig().describe()} | ${reportConfig().describe()}")
            }
        }.onFailure { logger.warn("注册配置监听失败：${it.message}") }

        scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "spw-listenstats").apply { isDaemon = true }
        }.also { executor ->
            executor.scheduleWithFixedDelay({ tick() }, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun shutdown(reason: String, message: String, toastType: WorkshopApi.Ui.ToastType) {
        try {
            engine?.close(reason)
            scheduler?.shutdownNow()
            saveAndLog("shutdown")
            exitFlush("插件停止($reason)")
            removeExitHook()
            refreshLocalReport(force = true)
            writeDiagnostics("停止($reason)")
            saveAndLog("shutdown-final")
            logger.info("插件收尾完成（$reason）")
        } catch (t: Throwable) {
            println("[ListenStats] 收尾异常：$t")
            logger.error("收尾异常（$reason）", t)
        }
        toast(message, toastType)
        instance = null
    }

    /**
     * 会话结算后的钩子：写进本地会话历史，并标记「本地报告该刷新了」。
     *
     * ⚠️ 运行在**宿主回调线程**：只做内存追加 + 打标记，落盘交给调度线程，绝不阻塞播放。
     */
    private fun onSessionClosed(record: SessionRecord) {
        runCatching {
            sessionLog?.append(record, logger::warn)
            reportDirty = true
        }.onFailure { logger.warn("写入会话历史失败：${it.message}") }
    }

    /**
     * 刷新**本地报告**（默认形态，完全不依赖 NAS / 接收端）。
     *
     * - 时机：插件启动、会话结算后（tick 节流）、插件停止、配置页按钮
     * - 覆盖写同一个文件（[ReportExport.FILE_NAME]），永远是最新那份
     * - 关掉 `local.enabled` 就完全不写
     */
    @Synchronized
    private fun refreshLocalReport(force: Boolean): File? {
        if (!readBool("local.enabled", true)) {
            if (force) logger.info("本地报告：已关闭（local.enabled=false），跳过")
            return null
        }
        val statsStore = store ?: return null
        val now = System.currentTimeMillis()
        if (!force && now - lastLocalReportAt < LOCAL_REPORT_MIN_INTERVAL_MS) return localReportFile
        return try {
            val dir = ReportExport.resolveDir(readString("local.dir", ""))
            val file = ReportExport.export(
                statsStore, reportMeta(), dir, now, sessionLog?.recent() ?: emptyList()
            )
            lastLocalReportAt = now
            localReportFile = file
            reportDirty = false
            logger.info(
                "本地报告已刷新：${file.absolutePath}（${file.length()} 字节，会话历史 ${sessionLog?.size ?: 0} 条）"
            )
            file
        } catch (t: Throwable) {
            logger.error("刷新本地报告失败（不影响统计与上报）", t)
            null
        }
    }

    /**
     * 会话结算后的钩子（运行在宿主回调线程）。
     *
     * 只在「每曲结束时上报」模式下立刻发一次；实现只是把任务丢给定时线程，**绝不阻塞回调**。
     */
    private fun onSessionEnqueued() {
        val report = reportConfig()
        if (!report.enabled || report.mode != "session") return
        runCatching { scheduler?.execute { flushInternal("会话结束") } }
    }

    /**
     * 启动时按开关决定是否立刻上报一次。
     *
     * 两种情况都要来一发（交给上报器自己判断）：① 队列里有上次没发出去的积压；
     * ② **插件刚升级**（版本号变了）或本地统计有变化 —— 此时队列可能是空的，
     * 但服务端手上的 `summary`（含 `trackStats`）已经过期，不补发报告页就会一直显示旧数据。
     */
    private fun scheduleStartupFlush() {
        val pending = store?.queue?.size ?: 0
        val report = reportConfig()
        if (!report.enabled) {
            logger.info("启动上报：上报未启用，跳过")
            return
        }
        if (!report.onStartup) {
            logger.info("启动上报：开关已关闭，跳过（积压=$pending）")
            return
        }
        val staleSummary = store?.summaryNeedsSend(pluginContext.pluginVersion) == true
        if (pending == 0 && !staleSummary) {
            logger.info("启动检查：暂无积压、摘要也是最新的，无需上报")
            return
        }
        logger.info(
            "启动补发：积压=$pending" +
                (if (staleSummary) "，且本地摘要比服务端新（版本=${pluginContext.pluginVersion}）" else "") +
                "，立即发送一次"
        )
        runCatching { scheduler?.execute { flushInternal("启动补发") } }
    }

    // ---------------- 退出上报 ----------------

    /**
     * 注册 JVM 退出钩子。
     *
     * 关键补强：SPW 正常关窗退出时**不一定**会调用 PF4J 的 `stop()`（实测 7 次退出只有 1 次走到），
     * 但 JVM 会执行 shutdown hook —— 这样「退出自动上报」才真正覆盖多数退出方式。
     * 被任务管理器强杀（SIGKILL / 结束任务）时钩子也不会执行，这是操作系统层面的限制。
     */
    private fun registerExitHook() {
        if (exitHook != null) return
        val hook = Thread({ runCatching { exitFlush("JVM 退出钩子") } }, "spw-listenstats-exit")
        runCatching { Runtime.getRuntime().addShutdownHook(hook) }
            .onSuccess {
                exitHook = hook
                logger.info("已注册 JVM 退出钩子（正常关窗退出时也会尝试上报一次）")
            }
            .onFailure { logger.warn("注册退出钩子失败：${it.message}") }
    }

    private fun removeExitHook() {
        val hook = exitHook ?: return
        runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
            .onFailure { logger.warn("移除退出钩子失败（可能正在退出）：${it.message}") }
        exitHook = null
    }

    /** 退出路径：短超时发送一次，绝不长时间拖住退出。 */
    private fun exitFlush(trigger: String) {
        val report = reportConfig()
        if (!report.enabled) {
            logger.info("退出上报($trigger)：上报未启用，跳过")
            return
        }
        if (!report.onExit) {
            logger.info("退出上报($trigger)：开关已关闭，跳过")
            return
        }
        val pending = store?.queue?.size ?: 0
        val staleSummary = store?.summaryNeedsSend(pluginContext.pluginVersion) == true
        if (pending == 0 && !staleSummary) {
            logger.info("退出上报($trigger)：队列为空且摘要已是最新，无需发送")
            return
        }
        logger.info("退出上报($trigger)：${report.describe()}，待发=$pending，使用短超时")
        val result = reporter?.flush(
            report,
            Reporter.EXIT_CONNECT_TIMEOUT_MS,
            Reporter.EXIT_READ_TIMEOUT_MS
        )
        logger.info("退出上报($trigger)结果：${result?.message ?: "上报器未就绪"}（成功=${result?.success == true}）")
    }

    /**
     * 统一的上报入口：日志 + 发送，供启动补发 / 会话钩子 / 手动按钮 / 定时任务共用。
     *
     * 「发不发」交给 [Reporter.flush] 判断：队列为空时，只要版本升级过或本地统计有变化，
     * 仍会发一份纯摘要（否则服务端永远等不到新数据）。[force] = 手动按钮，无条件发一次。
     */
    private fun flushInternal(trigger: String, force: Boolean = false): FlushResult? {
        val report = reportConfig()
        if (!report.enabled || report.url.isBlank()) {
            logger.warn("上报($trigger)被跳过：${report.describe()}")
            return null
        }
        val pending = store?.queue?.size ?: 0
        val staleSummary = store?.summaryNeedsSend(pluginContext.pluginVersion) == true
        if (!force && pending == 0 && !staleSummary) {
            logger.info("上报($trigger)：队列为空且摘要已是最新，无需发送")
            return null
        }
        logger.info(
            "上报($trigger)：${report.describe()}，待发=$pending" +
                (if (force) "（强制）" else "") +
                (if (staleSummary && pending == 0) "，本次为纯摘要补发" else "")
        )
        val result = reporter?.flush(report, force = force)
        if (result?.success == true) lastFlushAt.set(System.currentTimeMillis())
        return result
    }

    private fun tick() {
        try {
            val round = tickCount.incrementAndGet()
            val report = reportConfig()
            val now = System.currentTimeMillis()
            val due = when (report.mode) {
                "session" -> true
                "interval" -> now - lastFlushAt.get() >= report.intervalMinutes * 60_000L
                else -> false // exit：只在退出时上报
            }
            if (report.enabled && due) {
                // 有会话要发，或摘要比服务端新（例如刚升级插件/本地统计变了）→ 发一次。
                // ⚠️ 这里必须先判断再调用：`lastFlushAt` 只在**发送成功**后更新，
                // 若「无事可做」也进去，interval 模式下每 5 秒就会打一行「无需发送」把日志刷爆。
                val pending = store?.queue?.size ?: 0
                val staleSummary = store?.summaryNeedsSend(pluginContext.pluginVersion) == true
                if (pending > 0 || staleSummary) flushInternal("定时")
            }
            if (store?.dirty == true) saveAndLog("tick")
            // 本地报告是默认形态：有变化就（节流）刷新，不用用户记得点按钮
            if (reportDirty && now - lastLocalReportAt >= LOCAL_REPORT_MIN_INTERVAL_MS) {
                refreshLocalReport(force = false)
            }

            // 每 5 分钟一行回调计数：判断「宿主到底有没有在调我」
            if (round % HEARTBEAT_ROUNDS == 0L) {
                logger.info("回调统计: ${diagnostics?.summary() ?: "（无）"}")
                logger.info("引擎状态: ${engine?.describeCurrent() ?: "（未初始化）"}")
            }

            // 启动 60 秒内一条回调都没有 → 明确提示（这是最常见的「装了没反应」）
            if (round == NO_CALLBACK_ROUNDS &&
                diagnostics?.hasAnyCallback() != true &&
                noCallbackWarned.compareAndSet(0L, 1L)
            ) {
                logger.warn(
                    "启动约 60 秒仍未收到任何播放回调：可能是宿主未调用扩展点、" +
                        "当前没有在播放，或插件未被启用。可用配置页「输出诊断信息」再抓一次现场。"
                )
            }
        } catch (t: Throwable) {
            logger.error("定时任务异常", t)
        }
    }

    /** 落盘 + 记录数据量（排障时能看出「数据有没有写进去」）。 */
    private fun saveAndLog(reason: String) {
        val statsStore = store ?: return
        val warned = mutableListOf<String>()
        statsStore.save { warned += it }
        warned.forEach { logger.warn(it) }
        logger.info(
            "已落盘($reason): 总计=${statsStore.totals.listenedSeconds}s 会话=${statsStore.totals.sessions} " +
                "曲目=${statsStore.tracks.size} 待上报=${statsStore.queue.size}"
        )
    }

    // ---------------- 诊断输出 ----------------

    /** 写一段自包含的诊断块；用户只要把日志整份发来即可。 */
    fun writeDiagnostics(tag: String) {
        val dir = dataDir
        val statsStore = store
        logger.info("==== ListenStats 诊断信息（$tag）====")
        logger.info("插件: id=${pluginContext.pluginId} version=${pluginContext.pluginVersion} path=${pluginContext.pluginPath}")
        logger.info("宿主: spwVersion=${pluginContext.spwVersion} channel=${pluginContext.spwChannel}")
        logger.info(
            "运行环境: os=${System.getProperty("os.name")}/${System.getProperty("os.version")} " +
                "arch=${System.getProperty("os.arch")} java=${System.getProperty("java.version")} " +
                "vendor=${System.getProperty("java.vendor")} home=${System.getProperty("user.home")}"
        )
        logger.info("数据目录: ${dir?.absolutePath} 可写=${dir?.canWrite()} 已存在=${dir?.exists()}")
        logger.info("配置文件: ${runCatching { configHelper?.getConfigPath()?.toString() }.getOrNull()}")
        logger.info("配置原始值: ${CONFIG_KEYS.joinToString(" ") { "$it=${readRaw(it)}" }}")
        logger.info("配置生效值: ${engineConfig().describe()} | ${reportConfig().describe()}")
        logger.info(
            "宿主运行时探测: " + listOf(
                probe("java.net.http.HttpClient"),
                probe("java.net.HttpURLConnection"),
                probe("java.awt.Desktop"),
                probe("java.util.function.Consumer")
            ).joinToString(" ")
        )
        if (statsStore != null) {
            logger.info(
                "已有数据: 总计=${statsStore.totals.listenedSeconds}s 会话=${statsStore.totals.sessions} " +
                    "曲目=${statsStore.tracks.size} 今日=${statsStore.todaySeconds()}s 待上报=${statsStore.queue.size} " +
                    "摘要待补发=${statsStore.summaryNeedsSend(pluginContext.pluginVersion)}"
            )
        } else {
            logger.warn("已有数据: 存储未初始化")
        }
        // 本地报告是默认形态，排障时必须能一眼看出它的开关/落点/新鲜度
        val localDir = runCatching { ReportExport.resolveDir(readString("local.dir", "")) }.getOrNull()
        logger.info(
            "本地报告: 开关=" + readBool("local.enabled", true) +
                " 目录=" + (localDir?.absolutePath ?: "(解析失败)") +
                " 最近刷新=" + (if (lastLocalReportAt == 0L) "尚未" else Log.iso(lastLocalReportAt)) +
                " 待刷新=" + reportDirty
        )
        logger.info(
            "会话历史: ${sessionLog?.size ?: 0} 条" +
                " 文件=" + File(dataDir ?: File("."), "sessions.jsonl").absolutePath
        )
        logger.info("上报器: ${if (reporter == null) "未就绪（本地统计不受影响）" else "已就绪"}")
        logger.info("回调计数: ${diagnostics?.summary() ?: "（无）"}")
        logger.info("==== 诊断信息结束（排障请把 listenstats.log 整份发我）====")
    }

    private fun probe(className: String): String {
        val available = runCatching {
            Class.forName(className, false, this::class.java.classLoader)
            true
        }.getOrDefault(false)
        return "$className=${if (available) "可用" else "缺失"}"
    }

    /** 读取配置原始值用于诊断（不打印令牌明文）。 */
    private fun readRaw(key: String): String {
        if (key == "report.token") {
            val length = readString(key, "").length
            return if (length == 0) "未设置" else "已设置(${length}字符)"
        }
        val helper = configHelper ?: return "无配置助手"
        return runCatching {
            val value: Any? = helper.get<Any?>(key, null)
            if (value == null) "未设置" else "${value.javaClass.simpleName}($value)"
        }.getOrElse { "读取异常:${it.javaClass.simpleName}" }
    }

    // ---------------- 配置 ----------------

    private fun engineConfig(): EngineConfig {
        val enabled = readBool("stats.enabled", true)
        val idleGap = readNumber("stats.idleGapSeconds", 60.0).toLong().coerceIn(5L, 3600L)
        val reportEnabled = readBool("report.enabled", false)
        val countUnknown = readBool("stats.countUnknownTrack", EngineConfig.DEFAULT_COUNT_UNKNOWN)
        return EngineConfig(enabled, idleGap, reportEnabled, countUnknown)
    }

    private fun reportConfig(): ReportConfig {
        val enabled = readBool("report.enabled", false)
        val url = readString("report.url", "").trim()
        val token = readString("report.token", "").trim()
        val mode = readString("report.mode", "interval").lowercase()
        val interval = readNumber("report.intervalMinutes", 10.0).toLong().coerceIn(1L, 1440L)
        val onStartup = readBool("report.onStartup", true)
        val onExit = readBool("report.onExit", true)
        val pageUrl = readString("report.pageUrl", "").trim()
        return ReportConfig(enabled, url, token, mode, interval, onStartup, onExit, pageUrl)
    }

    private fun reportMeta(): ReportMeta = ReportMeta(
        pluginId = pluginContext.pluginId,
        pluginVersion = pluginContext.pluginVersion,
        spwVersion = pluginContext.spwVersion,
        channel = pluginContext.spwChannel.name
    )

    // 配置值类型由宿主 JSON 决定（Float/Int/Boolean/String 都可能），一律宽容读取
    private fun readBool(key: String, default: Boolean): Boolean = runCatching {
        ConfigValues.parseBool(configHelper?.get<Any>(key, default), default)
    }.getOrDefault(default)

    private fun readNumber(key: String, default: Double): Double = runCatching {
        ConfigValues.parseNumber(configHelper?.get<Any>(key, default), default)
    }.getOrDefault(default)

    private fun readString(key: String, default: String): String = runCatching {
        configHelper?.get<Any>(key, default)?.toString() ?: default
    }.getOrDefault(default)

    private fun toast(text: String, type: WorkshopApi.Ui.ToastType) {
        runCatching { WorkshopApi.ui.toast(text, type) }
    }

    internal fun statusLine(): String {
        val statsStore = store ?: return "尚未初始化"
        val today = StatsEngine.formatDuration(statsStore.todaySeconds())
        val current = engine?.describeCurrent() ?: "当前没有曲目信息"
        return "累计 ${StatsEngine.formatDuration(statsStore.totals.listenedSeconds)}" +
            " / ${statsStore.totals.sessions} 次 / ${statsStore.tracks.size} 首；今日 $today；$current"
    }

    companion object {
        private const val TICK_SECONDS = 30L

        /** 本地报告两次刷新之间的最小间隔（tick 每 30 秒一轮，实际最多滞后一轮）。 */
        const val LOCAL_REPORT_MIN_INTERVAL_MS = 20_000L

        /** 每 10 个 tick（=5 分钟）输出一次回调计数。 */
        private const val HEARTBEAT_ROUNDS = 10L

        /** 第 2 个 tick（=60 秒）时检查是否有回调。 */
        private const val NO_CALLBACK_ROUNDS = 2L

        /** 配置键清单集中在 [ConfigKeys]（自测会拿它和配置页声明交叉核对）。 */
        private val CONFIG_KEYS = ConfigKeys.ALL

        private val ToastSuccess = WorkshopApi.Ui.ToastType.Success
        private val ToastWarning = WorkshopApi.Ui.ToastType.Warning
        private val ToastError = WorkshopApi.Ui.ToastType.Error

        @Volatile
        private var instance: ListenStatsPlugin? = null

        /** 扩展点回调取用；插件未启用时为 null。 */
        internal fun engineOrNull(): StatsEngine? = instance?.engine

        /** 扩展点回调计数（插件未运行时丢弃，避免停止后残留回调污染诊断）。 */
        internal fun callback(name: String, detail: String = "") {
            instance?.diagnostics?.callback(name, detail)
        }

        /** 扩展点内部异常：写进日志文件。 */
        internal fun extensionError(context: String, throwable: Throwable) {
            val diagnostics = instance?.diagnostics
            if (diagnostics != null) {
                diagnostics.error(context, throwable)
            } else {
                println("[ListenStats] 扩展点回调异常[$context]（插件未运行）：$throwable")
            }
        }

        /** 配置页按钮：立即上报 */
        @JvmStatic
        @JvmName("flushNow")
        fun flushNow() {
            val plugin = instance ?: return
            try {
                val report = plugin.reportConfig()
                if (!report.enabled || report.url.isBlank()) {
                    plugin.logger.warn("手动上报被拒绝：${report.describe()}")
                    plugin.toast("请先启用上报并填写上报地址", ToastWarning)
                    return
                }
                // 手动按钮 = 无条件发一次：即使没有新会话，也要把最新摘要（含全量 trackStats）推上去
                val result = plugin.flushInternal("手动", force = true)
                if (result?.success == true) {
                    val what = if (result.count > 0) "${result.count} 条会话" else "最新统计摘要"
                    plugin.toast("已上报 $what（${result.message}）", ToastSuccess)
                } else {
                    plugin.toast("上报失败：${result?.message ?: "尚未初始化"}", ToastError)
                }
            } catch (t: Throwable) {
                plugin.logger.error("手动上报异常", t)
                plugin.toast("上报异常：${t.message}", ToastError)
            }
        }

        /** 配置页按钮：查看统计摘要 */
        @JvmStatic
        @JvmName("showSummary")
        fun showSummary() {
            val plugin = instance ?: return
            plugin.toast(plugin.statusLine(), ToastSuccess)
        }

        /** 配置页按钮：输出诊断信息（写进日志文件，便于整份发给我排障） */
        @JvmStatic
        @JvmName("dumpDiagnostics")
        fun dumpDiagnostics() {
            val plugin = instance ?: return
            plugin.writeDiagnostics("手动")
            val logPath = plugin.dataDir?.resolve("listenstats.log")?.absolutePath ?: "(未知)"
            plugin.toast("诊断信息已写入日志：$logPath", ToastSuccess)
        }

        /** 配置页按钮：打开报告页（纯本地便利，不依赖上报是否开启） */
        @JvmStatic
        @JvmName("openReportPage")
        fun openReportPage() {
            val plugin = instance ?: return
            val url = plugin.reportConfig().pageUrl
            if (url.isBlank()) {
                plugin.toast("请先在「上报」分组里填写报告页地址", ToastWarning)
                return
            }
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().browse(java.net.URI(url))
                    plugin.toast("已在浏览器打开报告页", ToastSuccess)
                } else {
                    plugin.toast("请手动访问：$url", ToastWarning)
                }
            } catch (t: Throwable) {
                plugin.logger.error("打开报告页失败", t)
                plugin.toast("打开失败：${t.message}", ToastError)
            }
        }

        /** 配置页按钮：刷新本地报告（平时会自动刷新，这个用于立刻要一份最新的） */
        @JvmStatic
        @JvmName("refreshLocalReportNow")
        fun refreshLocalReportNow() {
            val plugin = instance ?: return
            if (plugin.store == null) {
                plugin.toast("数据尚未初始化", ToastError)
                return
            }
            val file = plugin.refreshLocalReport(force = true)
            if (file != null) {
                plugin.toast("本地报告已刷新：${file.parentFile?.absolutePath}", ToastSuccess)
            } else {
                plugin.toast("本地报告未生成（可能已在配置里关闭）", ToastWarning)
            }
        }

        /** 配置页按钮：打开本地报告（没有就先刷新一份） */
        @JvmStatic
        @JvmName("openLocalReport")
        fun openLocalReport() {
            val plugin = instance ?: return
            val file = plugin.localReportFile ?: plugin.refreshLocalReport(force = true)
            if (file == null || !file.isFile) {
                plugin.toast("本地报告还没生成（检查「生成本地报告」开关）", ToastWarning)
                return
            }
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(file)
                    plugin.toast("已打开：${file.name}", ToastSuccess)
                } else {
                    plugin.toast("报告位置：${file.absolutePath}", ToastWarning)
                }
            } catch (t: Throwable) {
                plugin.logger.error("打开本地报告失败", t)
                plugin.toast("打开失败：${t.message}（${file.absolutePath}）", ToastError)
            }
        }

        /** 配置页按钮：打开数据目录 */
        @JvmStatic
        @JvmName("openDataDir")
        fun openDataDir() {
            val plugin = instance ?: return
            val dir = plugin.dataDir
            if (dir == null) {
                plugin.toast("数据目录尚未初始化", ToastError)
                return
            }
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(dir)
                    plugin.toast("已打开：${dir.absolutePath}", ToastSuccess)
                } else {
                    plugin.toast("数据目录：${dir.absolutePath}", ToastWarning)
                }
            } catch (t: Throwable) {
                plugin.logger.error("打开数据目录失败", t)
                plugin.toast("打开目录失败：${t.message}（${dir.absolutePath}）", ToastError)
            }
        }

        /** 自测用：直接注入一个实例，不经过宿主。 */
        internal fun attachForTest(plugin: ListenStatsPlugin) {
            instance = plugin
        }
    }
}
