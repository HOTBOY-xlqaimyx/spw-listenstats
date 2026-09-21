# 架构与数据流

> 代码在 `src/main/kotlin/com/keyqiang/spw/listenstats/`，共 9 个文件（不含自测）。

## 组件

```
宿主 SPW ──回调──▶ PlaybackStatsExtension ──▶ StatsEngine ──▶ StatsStore ──▶ 两个 JSON 文件
                        （计数/排障）           （会话切分）      （持久化）
                                                     │
                                                     └──▶ Reporter ──HTTP──▶ 用户自己的服务端
ListenStatsPlugin（主类）：装配以上组件 + 定时任务 + 配置页按钮的静态入口
Diagnostics：回调计数 / 首次触发记录 / 异常计数（排障）
Json.kt：自带极简 JSON 编解码（零第三方依赖）
Log.kt：文件日志（512 KB 轮转，同时打 stdout）
```

## 数据流

1. 宿主调用扩展点回调 → 扩展点**先计数**（`Diagnostics`）、再转成引擎输入（异常绝不外抛）
2. `StatsEngine` 把零散回调拼成「收听会话」：
   - 曲目身份来自 `onBeforeLoadLyrics`/`onAfterLoadLyrics` 的 `MediaItem.path`
   - 时长按 `min(位置增量, 墙钟增量)` 累计（≤3000ms 才算，防止跳转/倍速/补发虚增）
   - 会话结束条件：切歌 / 暂停 / 播放结束 / 空闲超阈值 / 插件停止 / 每 5 分钟 checkpoint
   - 少于 5 秒的会话丢弃（0 秒不写日志）
3. 会话结算 → 两件事并行：
   - `StatsStore.applySession()`：更新总量、按天、按曲目（**本地**）；上报开启时同时入队
   - `SessionLog.append()`：把**逐条明细**追加进 `sessions.jsonl`（本地会话历史，滚动保留 2000 条）
4. 定时任务（30s）负责落盘、**刷新本地报告**（有变化且距上次 ≥20s）、定时上报、心跳日志、「60 秒无回调」告警
5. **本地报告**（默认形态）：`ReportExport` 把报告页模板 + 聚合数据（含最近播放）拼成自包含 HTML，
   覆盖写 `我的文档\SPW听歌统计\ListenStats-本地报告.html`；**不依赖 NAS/服务端**
6. `Reporter` 批量 POST 到用户服务端（**可选扩展**）；**只有 2xx 才出队**，失败保留重试

## 输出形态（本地 vs 在线）

| | 本地报告（默认） | 在线页（可选扩展） |
|---|---|---|
| 产物 | 单个自包含 HTML（数据内联） | `report-data.json` + 静态页 |
| 位置 | `我的文档\SPW听歌统计\`（可配） | 自建静态站（示例：NAS + nginx，8123） |
| 刷新 | 会话结算后自动（tick 节流 ≤30s）+ 页内 ⟳ 重载 | 接收端每次收数重写；页内 ⟳ / 每 60s 自动 |
| 最近播放 | 最多 300 条 | 最多 20 条（接收端摘要） |
| 依赖 | 无 | 接收端 + 静态站 + 局域网 |

## 持久化格式

> 上报载荷里的 `summary` 是这套本地数据的**投影**：
> 总量/按天/计数来自 `totals`+`daily`，`trackStats` 来自 `tracks`（排序后截断到 2000 条），
> `unattributedSeconds` 来自 `totals.unattributedSeconds`（宿主没给曲目信息的那段，计入总量但不进曲目表）。

数据目录由宿主的 `ConfigHelper.getConfigPath().parent` 反推（不硬编码 `%APPDATA%`）：

| 文件 | 内容 |
|---|---|
| `listenstats.json` | `{schema, updatedAt, totals{listenedSeconds,sessions,firstPlayedAt,lastPlayedAt}, daily{日期:秒}, tracks{路径:{title,artist,album,albumArtist,listenedSeconds,sessions,firstPlayedAt,lastPlayedAt}}}` |
| `report-queue.json` | 待上报会话数组（`[]` 表示无积压） |
| `listenstats.log` | 运行日志（超 512 KB 轮转 `listenstats.log.1`） |
| `config.json` | 宿主维护的配置（7 项 + 1 个 0.2.1 新增开关） |

写入一律**临时文件 + 原子替换**；曲目表上限 5000（超出按最近播放淘汰，总量不受影响）；
待上报队列上限 2000。

## 线程模型

- 扩展点回调线程由宿主决定（文档只说歌词回调在 IO 线程）
- `StatsEngine` 全部方法 `@Synchronized`；`Diagnostics` 用并发容器
- 落盘与 HTTP 只在守护线程（`spw-listenstats`，30 秒定时）或停止流程里做
- 回调路径上**绝不阻塞**：不写文件、不发网络请求

## 关键约束（改动前必看）

1. **宿主没有切歌回调**：曲目身份只能靠歌词回调；插件加载时已在播放的曲目拿不到 `MediaItem`
2. **宿主运行时无 `java.net.http`**（Windows/Linux 实测）→ HTTP 只能用 `java.net.HttpURLConnection`
3. **包装成 zip 时两份 manifest 都要写 `Plugin-*`**（PF4J 读解包后顶层那份）
4. 宿主模组**默认禁用**，必须应用内启用或写插件目录的 `enabled.txt`
5. 平台无关：只用 JDK（`java.base` + `java.desktop`）+ SPI API，无原生代码

## 扩展点

目前只实现 `PlaybackExtensionPoint`（8 个回调 + 1 个只计数不参与统计的 `onLyricsLineUpdated`）。
如果宿主将来开放新扩展点（队列/音频流等，见 API 仓库 issue #19/#20/#24），按同样的模式加一个
`XxxExtension` 类 + 在 `Diagnostics` 登记即可。
