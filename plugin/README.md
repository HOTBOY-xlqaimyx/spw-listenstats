# SPW 听歌统计（ListenStats）

> 当前版本 **1.0.0**（首个正式版）（未正式上线，改动历史见 [CHANGELOG.md](../CHANGELOG.md)）。规则：**任何修改都递增版本号**，
> 未上线期间停留在 `0.x`；每次发版重跑两套验证并同步下载通道。

> Salt Player for Windows 创意工坊插件 · 基于 [spw-workshop-api](https://github.com/Moriafly/spw-workshop-api) `0.1.0-dev20`
> 纯公开 API 实现，**零反射、零第三方运行时依赖**（除 kotlin-stdlib）

## 它做什么

- 记录**每首曲目的收听时长**、播放次数、首次/最近播放时间
- 按天累计总听歌时长，曲目表上限 5000 首（超出按最近播放淘汰）
- 可选：把收听记录**定时上报到你自己的服务端**（HTTP POST JSON），带令牌校验、失败重试
- 上报里带**全量逐曲目聚合**（0.5.0 起），服务端可以直接渲染「曲目榜 / 艺人榜」，不需要回补历史
- **本地报告（默认形态）**：会话结算后自动刷新一份自带数据的 HTML（默认在 `我的文档\SPW听歌统计`），双击就能看；
  **NAS / 接收端只是可选扩展**（在线页、手机扫码看在线版）
- 宿主**迟到的曲目信息**会被用来回填（0.7.0 起）：启动时已在播的那首，位置回调先到时先把时长挂起，曲目信息一到就归因给它，只有确实无法归因的部分才记「未归因」
- 全程不联网（除非你开启上报），播放链路零阻塞（统计在内存、落盘/上报在独立守护线程）

## 文档索引

| 文档 | 内容 |
|---|---|
| 本文件 | 安装、配置、数据文件、上报协议、日志排障、构建 |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 组件与数据流、持久化格式、线程模型、关键约束 |
| [docs/UPSTREAM-1.19.md](docs/UPSTREAM-1.19.md) | **上游动态**：SPW 1.19 新 API（含 `getCurrentMediaItem()`）、权限与 `.spmod` 打包、我们的适配清单 |
| [docs/HOST-NOTES.md](docs/HOST-NOTES.md) | **宿主实测笔记**：真实路径、加载流程、回调行为、运行时模块坑 |
| [docs/TESTING.md](docs/TESTING.md) | 三层验证各覆盖什么、怎么跑、历史教训 |
| [docs/RELEASE.md](docs/RELEASE.md) | 版本规则、发版命令、快速通道、发版清单 |
| [../receiver/README.md](../receiver/README.md) | **阶段 2**：接收端部署、聚合接口、报告页数据来源 |
| [DESIGN.md](DESIGN.md) | 设计边界与决策表（会话切分规则、上报协议） |
| [CHANGELOG.md](../CHANGELOG.md) | 每版改了什么 + 发版清单 |

项目级的**计划/里程碑/待决策**在 `projects/spw-listenstats/PROJECT.md`（工作区根目录），
状态快照与决策日志在 `docs/project/`。

## 发布产物

当前版本 **1.0.0**（sha256 `edd654eb95f229d77ebba6fe46fbce5fdb8d778440b0bb99ff71e8e5230c18b3`，1,846,971 字节）：

- 构建产物：`plugin/build/dist/ListenStats-<版本>.zip`（`cd plugin && ./gradlew plugin`）
- 分发方式随你：GitHub Releases、自己的静态站 / SMB 共享、网盘都行 —— 插件本身不依赖任何分发渠道
- 接收端与报告页是**另一套版本号**（见 [../receiver/README.md](../receiver/README.md) 与本目录 `docs/RELEASE.md`）

## 安装

方式一（推荐）：SPW → 设置 → 创意工坊 → 模组管理 → 右上角**导入模组** → 选 `ListenStats-1.0.0.zip` → 启用。

方式二：把 zip 解压到
`%APPDATA%\Salt Player for Windows\workshop\plugins\`（保持 `classes/`、`lib/` 结构），重启 SPW 后在模组管理里启用。

启动成功会弹一个 Toast：`听歌统计已启动，累计 x`。

## 配置（设置 → 创意工坊 → 模组管理 → 本插件）

| 配置项 | 默认 | 说明 |
|---|---|---|
| 启用听歌统计 | 开 | 关闭后不再记录新数据，已有的都保留 |
| 会话断开阈值 | 60 秒 | **档位**：15 秒 / 30 秒 / 60 秒（推荐）/ 2 分钟 / 5 分钟；暂停或关歌超过这么久就结算当前这一场 |
| 未识别曲目的时长也计入 | **开** | 宿主未给出曲目信息时（例如启动时已在播放的那首），这段时间**照样计入总量与每日**，但单独记为「未归因」——**不进曲目榜/艺人榜**。默认开启；关掉则这段时长直接丢弃 |
| 启用上报 | 关 | 开启后才会发网络请求 |
| 上报地址 | 空 | 你的接收端地址；局域网自建形如 `http://192.168.1.100:8199/api/listen`，有域名也可填 `https://stats.example.com/api/listen` |
| 上报令牌 | 空 | 作为 `X-SPW-Stats-Token` 头发送 |
| 启动时自动上报 | 开 | 启动若发现上次有没发出去的记录，立刻补发一次 |
| 退出时自动上报 | 开 | 退出/停用时补发（短超时）；**并注册 JVM 退出钩子**，正常关窗退出也能发；被"结束任务"强杀则不执行（系统限制）|
| 上报时机 | 定时批量 | `定时批量` / `每曲结束时`（**会话一结算就立刻发，不等定时**）/ `仅退出时` |
| 定时上报间隔 | 10 分钟 | **档位**：1 / 2 / 5 / 10（推荐）/ 15 / 30 / 60 分钟 |
| 生成本地报告 | **开** | **默认形态**：听完一场就自动更新一份自带数据的 HTML 报告，双击就能看，不需要 NAS 或服务器 |
| 本地报告保存位置 | 空 | 留空 = `我的文档\SPW听歌统计`；也可填同步盘或共享目录，例如本地盘 `D:\听歌统计`，或已映射的网络盘 `Z:\听歌统计` |
| 按钮：刷新本地报告 | — | 平时会自动更新；点一下立刻生成最新的一份 |
| 按钮：打开本地报告 | — | 在浏览器里打开本地报告（还没生成就先自动生成）|
| 按钮：查看统计摘要 | — | 弹窗显示累计时长、会话次数、曲目数与今日时长 |
| 按钮：打开数据目录 | — | 统计数据、会话历史（sessions.jsonl）、待发队列与日志都在这里 |
| 按钮：立即上报 | — | 立刻把还没发出去的记录发一次（没有新会话时也会推送最新摘要）|
| 在线报告页地址 | 空 | 填了可用下面的按钮一键打开；一般是 `http://192.168.1.100:8123/spw-report/`。**默认不预填**，避免把作者的内网地址带进插件 |
| 按钮：打开在线报告页 | — | 在浏览器里打开上面的地址（需要 NAS / 接收端在线）|

## 本地报告（默认形态 · 不需要 NAS）

插件默认就会维护一份**自带数据的 HTML 报告**，双击就能看。**这是本项目的默认形态**；
「上报到自己的服务端」与在线页都是**可选扩展**。

| 项 | 说明 |
|---|---|
| 位置 | `我的文档\SPW听歌统计\ListenStats-本地报告.html`（依次探测 `Documents` → `OneDrive\Documents` → `Desktop` → 用户主目录）。可在配置里改 `local.dir`，例如本地盘 `D:\听歌统计` 或已映射的网络盘 `Z:\听歌统计` |
| 何时刷新 | 插件启动、**每次会话结算后**（最多滞后一轮 30 秒）、插件停止时，覆盖写同一份 |
| 内容 | 总时长 / 每日（近 7 天·近 30 天·全部）/ 常听艺人 / 曲目榜（可搜索、可展开全部）/ **最近播放**（逐条明细，最多 300 条）/ 未归因脚注 |
| 看最新 | 页面右上角 **⟳** 或直接刷新浏览器（本地报告是重载文件，不做轮询） |
| 手机看 | 把 HTML 文件发到手机（微信/文件传输）直接打开；或把 `local.dir` 指到共享目录，手机用文件管理器打开 |
| 备份 / 搬迁 | 把整个数据目录（`listenstats.json` + `sessions.jsonl` + `report-queue.json` + `config.json`）拷走就等于搬家，报告随即可用 |
| 注意 | 逐条会话明细从 **0.9.0** 起才记录，更早的会话无法回填（聚合数据不受影响）；在线页的「最近播放」只显示最近 20 条（接收端摘要），本地最多 300 条 |

## 数据文件（在插件数据目录，点「打开数据目录」直达）

| 文件 | 内容 |
|---|---|
| `listenstats.json` | 累计统计：总量 / 按天 / 按曲目 / 未归因时长 |
| `sessions.jsonl` | **本地会话历史**（逐条明细，滚动保留 2000 条）—— 本地报告「最近播放」的数据源 |
| `report-queue.json` | 待上报队列（只有 2xx 成功才出队） |
| `config.json` | 配置页写入的配置（改路径/开关都在这里） |
| `listenstats.log` | 运行日志（含每次结算的会话），超过 512 KB 自动轮转成 `.log.1` |

想清零重来：停用插件 → 删除 `listenstats.json`、`sessions.jsonl` 与 `report-queue.json` → 重新启用（没有做配置页的「重置」按钮，避免误触）。

想换机器：把**整个数据目录**拷到新机器的同位置（`%APPDATA%\Salt Player for Windows\workshop\data\com.keyqiang.spw.listenstats\`），累计、会话历史与本地报告都跟着走。

## 上报协议（`spw.listenstats.v1`）

```
POST <上报地址>
Content-Type: application/json; charset=utf-8
X-SPW-Stats-Token: <上报令牌>          # 留空则不发送
User-Agent: SPW-ListenStats/1.0.0
```

```json
{
  "schema": "spw.listenstats.v1",
  "pluginId": "com.keyqiang.spw.listenstats",
  "pluginVersion": "1.0.0",
  "spwVersion": "1.17.0",
  "channel": "Steam",
  "sentAt": 1758000000000,
  "sentAtIso": "2026-09-16T20:00:00+08:00",
  "sessions": [
    { "id": "uuid", "path": "C:\\Music\\a.flac", "title": "..", "artist": "..", "album": "..",
      "albumArtist": "..", "startedAt": 1758000000000, "endedAt": 1758000060000,
      "listenedSeconds": 60, "endedReason": "ended", "continued": false }
  ],
  "summary": { "listenedSeconds": 12345, "sessions": 42, "tracks": 17,
               "firstPlayedAt": 0, "lastPlayedAt": 0, "daily": { "2026-09-16": 3600 },
               "trackStatsTotal": 17,
               "trackStats": [ { "title": "..", "artist": "..", "album": "..", "albumArtist": "..",
                                 "path": "C:\\Music\\a.flac", "listenedSeconds": 900, "sessions": 3,
                                 "firstPlayedAt": 0, "lastPlayedAt": 0 } ] }
}
```

服务端只要返回 2xx 即视为成功（本插件不解析响应体）。

**纯摘要补发**（0.6.0 起）：报文里有 `summaryOnly: true` 时表示这一批 `sessions` 为空、只有最新 `summary`
—— 插件在**升级后首次启动**、**本地统计有变化但队列为空**、以及你点「立即上报」时都会发这种报文。
服务端只要照常按 `summary` 更新即可（接收端 `0.2.1` 起日志会写明 `summaryOnly=`）。

**`summary` 的三层覆盖范围**（做聚合视图前必须分清，0.5.0 起如此）：

| 字段 | 覆盖范围 | 适合做什么 |
|---|---|---|
| `sessions[]` | **只有开启上报之后**结束的会话 | 会话明细、时间线 |
| `summary.listenedSeconds/sessions/tracks/daily` | 插件本地**全量累计** | 总时长、每日时长、计数 |
| `summary.trackStats[]`（0.5.0+） | 插件本地**全量累计**，按听时长降序，≤2000 条 | 曲目榜、艺人榜 |
| `summary.unattributedSeconds`（0.7.0+） | 宿主未给出曲目信息的那段时长（已含在 `listenedSeconds` 里） | 报告页脚注 |

所以**聚合类视图不需要历史补发**；只有「逐条会话明细」才受开启时间限制。旧的 `/api/listen` 服务端不用改也能继续收（多余字段会被忽略）。

**发送时机**：`每曲结束时` = 会话结算**立即**发送；`定时批量` = 每到间隔发一次；`仅退出时` = 只在退出时发。另有独立开关控制**启动补发**与**退出补发**（默认都开；退出补发同时挂在 PF4J 停止和 JVM 退出钩子上）。**插件启动时若队列有积压、或本地摘要比服务端新（例如刚升级），都会立刻补发一次**；配置页「立即上报」按钮**无条件**发一次（哪怕没有新会话，也会把最新摘要推上去）。日志里会写明触发源：`启动补发 / 会话结束 / 定时 / 手动`。

`endedReason` 取值：`trackChanged` / `paused` / `ended` / `idle` / `idleState` / `checkpoint` / `pluginStop` / `pluginDelete`。
`continued=true` 表示这条是长曲目 checkpoint 切出来的续段（不是新的一次播放点击）。

## 阶段 2：接收端与听歌报告页（已上线）

| 组件 | 地址 | 版本 |
|---|---|---|
| 收数接口（插件往这里 POST） | http://192.168.1.10:8199/api/listen | spw-receiver **0.2.0** |
| 收数摘要（批次数/会话数/最近接收） | http://192.168.1.10:8199/api/listen | 同上 |
| 聚合数据（报告页的数据源） | http://192.168.1.10:8199/api/report | 同上 |
| 听歌报告页（手机可用） | http://192.168.1.10:8123/spw-report/ | 变体 B + 夜间模式 |

```
SPW 插件 --POST--> spw-receiver(8199) --落盘--> data/listen-YYYY-MM-DD.jsonl
                          |
                          +--聚合--> 静态站 web/spw-report/report-data.json
                                              |
                                     报告页同源 fetch 渲染
```

- 报告页是**纯前端**（`index.html` + `fetch('report-data.json')`），由任意静态服务器托管，接收端只负责生成那个 JSON。
- 数据不完整时页面会自己说明（例如插件还是 0.4.0：曲目榜只覆盖上报期，升级后自动补全）。
- 部署方式、环境变量、接口字段见 [../receiver/README.md](../receiver/README.md)。

## 从源码构建

需要 JDK 21；Gradle wrapper 会自动处理（fooJay 工具链解析器可按需下载 JDK）。

```bash
./gradlew selftest   # 纯逻辑自测：JSON / 统计引擎 / 存储 / HTTP 上报（当前 79 项）
./gradlew plugin     # 产出 build/dist/ListenStats-1.0.0.zip
```

> **构建避坑（实测定论）**：`spw-workshop-api:0.1.0-dev20` 的 POM 仍以 `compile` 作用域挂着
> Compose Multiplatform 1.11.0-alpha01 + Salt UI 2.9.0-alpha02（dev15~dev19 播放界面扩展点的遗留，
> dev20 已删掉那些代码但没删依赖）。它们的传递依赖最终指向 `androidx.*`，**只在 Google Maven**，
> 于是直接依赖 API 会在解析阶段就报一堆 `Could not find androidx.lifecycle:...`。
> 本项目的做法是：
> ```kotlin
> compileOnly(libs.spw.workshop.api) { isTransitive = false }  // 只要 API 本体
> compileOnly(libs.pf4j)                                        // 显式补上真正需要的 PF4J
> kapt(libs.pf4j)                                               // 注解处理器在 pf4j 里
> ```
> 这样既不用加 Google 仓库，又把依赖图从上百个 android 构件缩到 1 个。

> **打包避坑（更致命，实测复现）**：PF4J 对「目录型插件」读的描述符是
> `<插件目录>/META-INF/MANIFEST.MF` —— 也就是 **zip 被解包后顶层的那份清单**，
> 它不是内层插件 jar 的清单，而是外层打包 Jar 任务自己的清单！
> 官方 README 的模板只给内层 jar 设 `Plugin-*`，外层那份是空的，于是 PF4J 拿到
> `id=null class=org.pf4j.Plugin` 的空描述符，直接
> `InvalidPluginDescriptorException: Field 'id' cannot be empty` 拒绝加载。
> 本项目的做法：把 `Plugin-*` 抽成一个 `pluginManifest` map，**两份清单都写**（见 `build.gradle.kts`）。
> `listenstats-hostsim` 里有一条断言专门比对两份清单内容一致，防止回归。


### 用容器构建（没有本机 JDK 时）

写好一份 `docker-compose.yml`，用固定的 Gradle + JDK 镜像跑同一条 Gradle 任务即可：

```yaml
services:
  builder:
    image: gradle:8.14-jdk21
    working_dir: /src/plugin
    volumes:
      - ./src:/src                       # src/plugin 是插件工程
      - ./data/gradle:/home/gradle/.gradle   # Gradle 缓存持久化，重建容器不丢
    command: ["gradle", "--no-daemon", "--console=plain", "selftest", "plugin"]
```

```bash
docker compose run --rm builder
```

> ⚠️ fnOS 挂载卷上的文件权限会退化成 `000`：本地 `cat` 还能读（靠 ACL），但 `cp`/`Files.copy`
> 会把这个 000 带到新位置导致真的读不了（PF4J 因此报 `Permission denied`）。
> 所以 `plugin` 任务末尾有 `setReadable(true, false)`，`hostsim` 复制 zip 后也会显式放权。

## 实现要点（为什么这么写）

- 宿主 **没有切歌回调**，曲目身份只能来自 `onBeforeLoadLyrics/onAfterLoadLyrics` 的 `MediaItem.path`
- 时长只认「播放位置增量」，单次增量 ≤ 3000 ms；且每次取「位置增量」与「两次回调之间的真实耗时」**较小值**
  （所以倍速播放、宿主异常补发 tick 都不会虚增时长）；跳转（`onSeekTo`、位置突跳）与单曲循环回绕一律不计数
- 会话结束条件：切歌 / 暂停 / 播放结束 / 空闲超阈值 / 插件停止 / 每 5 分钟 checkpoint
- 少于 5 秒的会话只写日志不入库（防跳歌刷数据）
- 所有写入都是「临时文件 + 原子替换」；配置值按宿主实际 JSON 类型宽容读取
- 扩展点回调全部包 try/catch，**绝不让异常冒进宿主**

## 状态说明（诚实版）

- ✅ **纯逻辑自测**：`./gradlew selftest` 79 项断言全绿（JSON 编解码、存储往返与损坏恢复、10 个播放场景——含倍速播放与异常 tick 风暴的墙钟上界、HTTP 上报成功/失败路径、队列上限、曲目表超限淘汰）
- ✅ **模拟宿主集成测试**：`listenstats-hostsim` 用**真实 PF4J** 加载**最终交付的 zip**，97 项断言全绿 ——
  描述符解析、扩展点发现、`SpwPlugin(PluginContext)` 注入、start/stop 生命周期、回调驱动统计、
  配置页三个按钮按全限定名反射调用、HTTP 上报（成功路径 / 500 失败保留后重试补发 / `exit` 模式只在停止时上报）、
  **宿主重启后从同一数据目录继续累计**、落盘 JSON 与队列清空、两份清单一致性，
  以及对抗性场景：快速切歌不刷数据、宿主被强杀时定时任务已落盘、**多线程并发回调 + 4000 次 tick 风暴不虚增时长**；
  另含**生命周期健壮性**（插件停止后残留回调安全无操作、同一宿主里停用再启用不重复计数、启动 Toast 正确带出历史累计）、
  **配置生效验证**（关闭统计后 6 秒不入库 / 空闲阈值改成 5 秒后确实切成两段）、**定时上报模式**（不点按钮，等插件定时任务自动发）、
  `update()` 钩子、**含空格与中文的数据目录**（贴近真实的 `%APPDATA%\Salt Player for Windows\...`）；
  外加一项**类引用审计**：解析 41 个 class 的常量池 + 全部类型描述符，证明外部引用只有 JDK / kotlin-stdlib / SPW API，
  没有 androidx、Compose 或任何第三方库（「零运行时依赖」的硬证据）
- ✅ **真实宿主验证（Linux 版，2026-09-17）**：在 NAS 上无头运行 **Salt Player 1.18.0 Linux（Steam 渠道，内嵌 PF4J 3.14.1 + JDK 25）**，
  把交付的 zip 放进插件目录后，宿主日志完整走完
  `Expanded plugin zip → Found descriptor → Loaded plugin → resolved → Start plugin →
  LegacyExtensionFinder 读出 PlaybackStatsExtension → 实例化扩展`，
  插件自身也成功初始化并落盘 `listenstats.log`（含 `spw=1.18.0 channel=Steam 数据目录=...`）
- ⚠️ **仍未验证：播放级回调**。宿主在**没有 Steam 会话时会弹出模态框**
  「This application requires a package identity.」并停在启动阶段——这是渠道/所有权校验（本插件在它**之前**已完成加载与启动），
  所以「真实播放 → onPositionUpdated → 记到会话」这一段必须在**正常运行在 Steam 里的客户端**上确认（Windows 或 Linux/Steam Deck）。
  无头容器也没有可驱动 UI 的手段，**不会去规避该校验**
- ⚠️ Windows 版真机未测（无 Windows 环境；Linux 版与 Windows 版同源同 API）

## 首次真机试用清单（Windows / Linux 版 SPW）

1. **导入**：设置 → 创意工坊 → 模组管理 → 右上角「导入模组」→ 选 `ListenStats-1.0.0.zip` → 启用
2. **看启动提示**：应弹出 `听歌统计已启动，累计 0 秒`
3. **放一首歌**，播 10 秒以上后暂停 → 点配置页「查看统计摘要」，应看到「累计 x 秒 / 1 次 / 1 首」
4. **点「打开数据目录」**：应打开资源管理器并弹出路径；若按钮无效，去日志里找 `数据目录=...` 那一行
5. **看日志**：`listenstats.log` 应出现 `记录会话 Ns（paused/ended）：曲名 — 艺人`
6. **上报**（可选）：填好地址与令牌 → 开启上报 → 点「立即上报」→ 应弹 `已上报 N 条记录（HTTP 200）`；
   失败会弹原因，数据留在队列里等下次重试，不会丢
7. **出问题时给我这三样**：`listenstats.log` 全文、配置页截图、「查看统计摘要」的 Toast 文案

## Linux 版（2026-09-14 起）—— 已实测

Salt Player 自 **2026-09-14** 原生支持 Linux（Steam 新闻《Now available on Linux!》；Steam appid 3009140
平台支持已变为 Windows + Linux amd64；AUR / Flathub 暂未搜到，官方口径是走 Steam）。

**实测结论（2026-09-17，NAS 上无头运行 Steam 版 Linux 包）：**

| 项 | 实测结果 |
|---|---|
| 创意工坊是否存在于 Linux 版 | ✅ 有。内嵌完整 PF4J **3.14.1** 与同一套 API `com.xuncorp.spw.workshop.api.*` |
| 插件目录 | `~/.local/share/Salt Player/workshop/plugins`（即 `$XDG_DATA_HOME`） |
| 启用机制 | 该目录下的 `enabled.txt`（每行一个 pluginId）；应用内「模组管理」写的就是它。官方文档亦确认「自 1.6.10 EA 起 Mod 默认处于被禁用状态」 |
| 无 Steam 会话时 | 应用弹「This application requires a package identity.」并停在启动阶段（**插件在此之前已加载并启动**） |
| 插件数据目录 | `~/.local/share/Salt Player/workshop/data/<pluginId>/` |
| 宿主版本 / 运行时 | Salt Player **1.18.0**、内嵌 **JDK 25**（jlink 裁剪运行时） |
| 我的插件能否加载 | ✅ 描述符解析、加载、`resolved`、`Start plugin`、扩展点经 `extensions.idx` 发现并实例化，全部通过 |
| 渠道识别 | `pluginContext` 注入正确：`spw=1.18.0 channel=Steam` |

**这次真机验证抓到的真实 bug（已修）**：宿主 jlink 运行时的模块清单是
`java.base java.desktop java.xml java.sql jdk.crypto.ec …`，**没有 `java.net.http`**。
原先上报用 `java.net.http.HttpClient`，于是在真实宿主里插件初始化直接
`NoClassDefFoundError: java/net/http/HttpClient`——**连本地统计都不工作**。
现已改用 `java.net.HttpURLConnection`（java.base，任何运行时都有），并让「上报器构造失败」不再拖垮本地统计。

**仍未覆盖**：播放级回调（无头环境无音频设备）。请在**有声音**的机器上确认第 3 步「放一首歌」后能记到会话。

## 日志与远程排障（0.2.0 起）

**出问题时你只需要做一件事：把 `listenstats.log` 整份发给我。** 它被设计成自包含的现场快照：

| 日志内容 | 能看到什么 |
|---|---|
| `==== ListenStats 诊断信息（启动/停止/手动）====` | 插件 id/版本/路径、宿主版本与渠道、OS/JVM/user.home、数据目录与可写性、配置**原始值**（含宿主给的真实类型）、配置生效值、**宿主运行时探测**、已有数据量、上报器是否就绪 |
| `回调首次触发: xxx（上下文）` | 宿主到底有没有调用扩展点，以及首次调用的曲目/位置/状态 |
| `回调统计: …`（每 5 分钟） | 回调是否持续在来；出现 `（尚未收到任何播放回调）` 说明宿主侧没走播放流程 |
| `开始统计 / 记录会话 / 丢弃过短会话` | 统计引擎的会话生命周期与判定原因 |
| `tick 心跳`（每 60 次回调） | 位置是否在推进、会话是否在累计 |
| `已落盘(原因): 总计/会话/曲目/待上报` | 数据有没有真的写进磁盘 |
| `上报…` | 上报每一步：目标主机、待发条数、HTTP 码、失败原因 |
| `扩展点回调异常[上下文]` | 插件内部异常（**也会写进日志文件**，不再只打 stdout） |

日志文件：数据目录下的 `listenstats.log`（超过 512 KB 自动轮转为 `.log.1`）。
配置页有 **「输出诊断信息」** 按钮，点一下就会把当前现场再抓一份进日志，并弹出日志路径。
**日志里不会出现上报令牌明文，也不含上报 URL 的路径/查询串**（只给 scheme://host:port）。

### 真机实例（Salt Player 1.18.0 Linux 上的实际输出）

```
==== ListenStats 诊断信息（启动）====
插件: id=com.keyqiang.spw.listenstats version=0.2.1 path=/home/ubuntu/.local/share/Salt Player/workshop/plugins/ListenStats-0.2.0
宿主: spwVersion=1.18.0 channel=Steam
运行环境: os=Linux/6.18.18.c1032-trim arch=amd64 java=25.0.4.1 vendor=JetBrains s.r.o. home=/home/ubuntu
数据目录: /home/ubuntu/.local/share/Salt Player/workshop/data/com.keyqiang.spw.listenstats 可写=true 已存在=true
配置文件: /home/ubuntu/.local/share/Salt Player/workshop/data/com.keyqiang.spw.listenstats/config.json
配置原始值: stats.enabled=未设置 stats.idleGapSeconds=未设置 report.enabled=未设置 …
配置生效值: 统计=开 空闲阈值=60s 上报开关=false | 上报=关 目标=(未填) 令牌=无 模式=interval 间隔=10min
宿主运行时探测: java.net.http.HttpClient=缺失 java.net.HttpURLConnection=可用 java.awt.Desktop=可用 java.util.function.Consumer=可用
已有数据: 总计=0s 会话=0 曲目=0 今日=0s 待上报=0
上报器: 已就绪
回调计数: （尚未收到任何播放回调）
==== 诊断信息结束（排障请把 listenstats.log 整份发我）====
```

> 这一行 `java.net.http.HttpClient=缺失` 正是 0.1.0 在真机上崩溃的根因——现在一眼可见。

### 常见现象的读法

- 完全没有 `回调首次触发` 行 → 宿主没调用扩展点 / 当前没在播放 / 插件没启用
- 有 `onPositionUpdated` 但没有 `onBeforeLoadLyrics` → 拿不到曲目信息，不会入库（按设计）
- 有回调但诊断块里 `上报器=未就绪` → 上报功能不可用，但本地统计正常
- 诊断块里 `java.net.http.HttpClient=缺失` → 正常（0.1.0 的真机崩溃就是它，现在已改用 `HttpURLConnection`）

## 已知行为与限制

- **启动时已经在播的那首不会被统计**：真机实测宿主只在「真正加载歌词」时才调 `onBeforeLoadLyrics`，
  插件加载时那首歌的歌词早已加载过，于是拿不到曲目信息（API 也没有「取当前曲目」的接口）。
  默认这段时间不入库（日志会写 `收到位置回调但尚无曲目信息`）；打开「统计无法归因的播放」后会记入「未知曲目」。
- **没有切歌回调**：曲目身份只能靠宿主的歌词回调（`onBeforeLoadLyrics`/`onAfterLoadLyrics`）拿到。
  若某曲目宿主完全不触发歌词回调，这段时间**不会入库**（宁可不记，也不把时长算到别的曲目头上；
  模拟宿主里有专门一条断言锁住这个行为）
- 少于 5 秒的会话只写日志不入库（防跳歌刷数据）；拖动进度条不计入时长；单曲循环不算两次播放
- 音频流、播放队列、完整歌词轴等 API 未开放，本插件不涉及


## 本地回归怎么跑（`listenstats-hostsim/`）

`hostsim` 是一个**假的 SPW 宿主**（Java 写的 WorkshopApi/PF4J 宿主替身），用来在没有 Windows 的情况下验证插件包：

```bash
# 先构建插件
cd plugin && ./gradlew plugin
# 再用假宿主加载它
cd ../listenstats-hostsim && ./gradlew run --args="../plugin/build/dist/ListenStats-1.0.0.zip"  # 假宿主工程在内部目录，不在本仓库
```

它做的事：复制 zip 到临时 plugins 目录（需 `chmod`，见下）→ `WorkshopPluginManager` 加载 → 断言描述符/扩展点/元数据
→ 注入假 `WorkshopApi` → 模拟播放回调 → 反射调用配置页按钮 → 起一个真 HttpServer 收上报 → 停插件 → 校验落盘 JSON。
失败时退出码 1，可直接进 CI。

> 假宿主跑一轮约 1.5 分钟：它必须**按真实 1 秒节奏**喂 tick（引擎把位置增量与墙钟耗时取小，
> 紧循环灌 tick 只会累计到毫秒级），另有 32 秒等待用来验证「宿主被强杀后定时任务落盘」。
> 长会话（300 秒 checkpoint）等大数字场景由 `selftest` 注入假时钟覆盖，不放进假宿主以免跑几分钟。

`Dbg3`（`./gradlew dbg`）是排查 PF4J 描述符读取的调试入口，定位过下面这个坑。


## 改名/改 ID

`build.gradle.kts` 顶部的 `pluginId` / `pluginClass` / `pluginProvider` 与包名 `com.keyqiang.spw.listenstats`、
以及 `preference_config.json` 里三个按钮的 `on_click` 全限定名必须同步修改。

