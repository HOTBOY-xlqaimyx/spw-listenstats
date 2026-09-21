# SPW 听歌统计插件 — 设计边界与决策

> 版本 1.0.0 / API `0.1.0-dev20` / 仅为公开 API，零反射、零第三方运行时依赖（除 kotlin-stdlib）

## 1. 公开 API 能拿到什么（这是全部约束）

`PlaybackExtensionPoint` 只有 8 个回调，**没有任何「切歌/曲目变化」回调**：

| 回调 | 线程（文档口径） | 我们能拿来做什么 |
|---|---|---|
| `onBeforeLoadLyrics(MediaItem)` | IO | **唯一的曲目元数据来源**（title/artist/album/albumArtist/path） |
| `onAfterLoadLyrics(MediaItem)` | IO | 同上，兜底（默认加载失败才调） |
| `onPositionUpdated(Long)` | 未声明 | 每秒位置 → 时长累计的唯一依据 |
| `onIsPlayingChanged(Boolean)` | 未声明 | 暂停/继续边界 |
| `onStateChanged(Idle/Buffering/Ready/Ended)` | 未声明 | 曲目结束边界 |
| `onSeekTo(Long)` | 未声明 | 跳转 → 不能把跳变量算进收听时长 |
| `onLyricsLineUpdated(LyricsLine?)` | 未声明 | 不用（本插件与歌词无关） |
| `updateLyrics`（已废弃） | IO | 不用 |

主动能力只有：`pause/play/previous/next/seekTo/changeExclusive` + `Ui.toast` + 配置 API。

## 2. 由此推导出的设计决策

| 边界情况 | 决策 | 理由 |
|---|---|---|
| 没有切歌回调 | 曲目身份 = `onBeforeLoadLyrics` 给的 `MediaItem.path`；**切歌判断 = path 变化** | path 是稳定主键，比 title 可靠 |
| 同一首歌重复播放 | path 不变时**不切会话**，位置回绕只重置基准，不重复开会话 | 避免单曲循环刷次数 |
| 无歌词/歌词已缓存的曲目 | 仍以最后一次 `MediaItem` 为当前曲目；即使 `onBeforeLoadLyrics` 只调一次也不影响统计 | 位置累计不依赖回调频率 |
| 拖动进度条 | 用「位置差」累计：`delta ∈ (0, 3000] ms` 才算收听；`delta ≤ 0`（回绕）或 `> 3000`（跳转）不计数；`onSeekTo` 直接重置基准 | 防止 seek 被算成听了 30 分钟 |
| 倍速播放 / 宿主补发 tick | 每次累计取 `min(位置增量, 两次回调之间的真实墙钟耗时)` | 只认位置增量隐含「≈真实耗时」的假设：倍速播放与异常补发都会**线性虚增**（实测 1 秒内的 tick 风暴曾虚增 3300 秒 = 55 分钟）。正常每秒一次 tick 时两者几乎相等，行为不变 |
| 暂停 | ticks 通常停止 → 用**墙钟空闲阈值**（默认 60s，可配）判定会话结束；`onIsPlayingChanged(false)` 立即结束会话 | 不依赖 ticks 是否存在 |
| 跨天/长时间单曲 | 每累计 5 分钟自动 checkpoint：结束当前会话并立刻续开（标记 `continued`） | 崩溃最多丢 5 分钟，且不产生假播放次数 |
| 极短播放（跳歌划过） | 会话 < 5 秒**不入库**（只写日志） | 避免刷爆统计 |
| 宿主退出/插件停用 | `SpwPlugin.stop()/delete()` 里结束会话、落盘、尽力上报一次 | PF4J 生命周期保证 |
| 时区 | 一律用**本机时区**（Windows 侧即北京时间）按 `LocalDate` 分日 | 与用户直觉一致 |
| 崩溃安全 | 统计只写「已结束的会话」；写文件用临时文件 + 原子替换 | 避免半截 JSON |

## 3. 上报协议（v1，阶段 2 的服务端按此实现）

```
POST <report.url>
Content-Type: application/json; charset=utf-8
X-SPW-Stats-Token: <report.token>        # 留空则不发送
User-Agent: SPW-ListenStats/1.0.0

{
  "schema": "spw.listenstats.v1",
  "pluginId": "com.keyqiang.spw.listenstats",
  "pluginVersion": "1.0.0",
  "spwVersion": "<PluginContext.spwVersion>",
  "channel": "Steam|MS",
  "sentAt": 1758000000000,
  "sentAtIso": "2026-09-16T20:00:00+08:00",
  "summaryOnly": false,
  "sessions": [
    {"id":"<uuid>","path":"C:\\Music\\a.flac","title":"..","artist":"..","album":"..",
     "albumArtist":"..","startedAt":1758000000000,"endedAt":1758000060000,
     "listenedSeconds":60,"endedReason":"trackChanged|paused|ended|idle|checkpoint|pluginStop",
     "continued":false}
  ],
  "summary": {
    "listenedSeconds": 12345, "sessions": 42, "tracks": 17,
    "firstPlayedAt": 0, "lastPlayedAt": 0,
    "daily": {"2026-09-16": 1234},
    "trackStatsTotal": 17,
    "trackStats": [
      {"title":"..","artist":"..","album":"..","albumArtist":"..","path":"C:\\Music\\a.flac",
       "listenedSeconds":900,"sessions":3,"firstPlayedAt":0,"lastPlayedAt":0}
    ]
  }
}
```

**`trackStats`（0.5.0 新增，协议仍是 v1）**：**全量逐曲目聚合**，按收听时长降序，最多 2000 条
（`trackStatsTotal` 是全量条数）。它和 `sessions` 的区别很关键：

| 字段 | 覆盖范围 | 用途 |
|---|---|---|
| `sessions` | **只有开启上报之后**结束的会话（增量） | 会话明细、时间线 |
| `summary.daily` / `listenedSeconds` / `sessions` / `tracks` | **插件本地全量累计** | 总量、每日时长 |
| `summary.trackStats` | **插件本地全量累计**（0.5.0+） | 曲目榜、艺人榜 |

也就是说：**聚合类视图不需要历史补发**；只有「逐条会话明细」才受上报开启时间限制。
旧服务端读到多余的 `trackStats` 会直接忽略，所以这是**向后兼容的增量**。

- 队列落盘 `report-queue.json`，2xx 才算发送成功并出队；失败保留，下轮重试。
- 队列上限 2000 条，溢出丢最旧（日志告警）。
- 上报在**独立守护线程**，不阻塞播放回调。
- 触发源有四种（日志会写明）：`启动补发`（启动时队列有积压**或摘要比服务端新**）、`会话结束`（mode=session 时立即）、`定时`（interval 到点）、`手动`（配置页按钮，**强制**）。
- **`sessions` 为空也会发**（0.6.0 起）：升级插件后/本地统计变化后，服务端手上的 `summary` 已过期，
  此时发一份 `summaryOnly: true` 的纯摘要快照。**否则「装了新版本但还没听完一首歌」的机器上，报告页会一直显示旧数据** ——
  这是 0.5.0 的真实缺口（用户 2026-09-22 报「页面数据不对」）。会话结束的钩子运行在宿主回调线程上，只做一次任务投递。

## 4. 明确不做的事（v1）

- 不做播放列表/队列读写（API 未开放，issue #19/#23）
- 不做完整歌词时间轴（API 未开放，issue #24，需反射 → 风险高，另开方向）
- 不做数据重置按钮（避免配置页误触毁数据，需要时手动删 `listenstats.json`）
- 服务端与报告页属阶段 2，待用户确认后另做

## 改动约束（硬性，改代码前先读）

1. **不碰宿主内部**：只用 `com.xuncorp.spw.workshop.api` 的公开面，不反射访问私有实现（宿主闭源，反射一升级就碎）。
2. **回调里不抛异常**：扩展点实现统一 `try/catch` + 写日志，异常冒到宿主里会污染播放链路。
3. **零第三方运行时依赖**（除 kotlin-stdlib）：宿主是 jlink 裁剪运行时，联网只能用 `HttpURLConnection`（没有 `java.net.http`）。
4. **配置项要改三处**：`preference_config.json`、`ConfigKeys.ALL`、README 配置表；`selftest` 会比对前两者，漏一处就失败。
5. **改报告页要同步三处**：`web/index.html`（源）→ 用 `web/tools/build-report-page.py` 生成线上页（见 `web/README.md`）→ 复制成 `plugin/src/main/resources/web/report.html` 并升插件版本。
6. **文案面向普通用户**：结论前置、不用实现术语、示例用通用地址（不写自己的内网地址）。
7. **不提交个人数据**：`sessions.jsonl` / `listenstats.json` / `report-data.json` 与含真实听歌记录的示例都不进仓库。
