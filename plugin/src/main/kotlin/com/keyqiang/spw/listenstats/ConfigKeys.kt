package com.keyqiang.spw.listenstats

/**
 * 配置键清单（**单一来源**）。
 *
 * 为什么单独放在这里：
 * 1. 诊断信息要按这份清单逐个打印「配置原始值」，硬编码在插件类里时，
 *    新增配置项很容易忘记登记 —— 排障时正好看不到它（2026-09-22 真实发生过：
 *    `local.enabled` / `local.dir` / `report.pageUrl` 三个新键都没进清单）。
 * 2. 放到纯 Kotlin 文件里，自测就能拿它和 `preference_config.json` 交叉核对：
 *    **配置页声明的键 == 这份清单**，多一个少一个都报错。
 */
internal object ConfigKeys {
    val ALL = listOf(
        "stats.enabled",
        "stats.idleGapSeconds",
        "stats.countUnknownTrack",
        "local.enabled",
        "local.dir",
        "report.enabled",
        "report.url",
        "report.token",
        "report.mode",
        "report.intervalMinutes",
        "report.onStartup",
        "report.onExit",
        "report.pageUrl"
    )
}
