# 上游动态：SPW 1.19 的新 API 与我们该怎么接（调研于 2026-09-22）

> 结论先说：**宿主的 API 能力只能由宿主实现**，我们无法自己"更新"（理由见下）；
> 但**不用提需求也不用等太久** —— 官方 PR 里已经在做我们最需要的那个接口，预计 **SPW 1.19** 可用。

## 1. 为什么不能自己加

| 事实 | 说明 |
|---|---|
| 回调的**调用方在宿主里** | `PlaybackExtensionPoint` 的方法由 SPW（闭源，Steam appid `3009140`）在自己的代码里调用。我们 fork API 库、加接口、发自己的 JitPack 版本，宿主**不会调**，等于空转 |
| API 库本身是开源的 | `Moriafly/spw-workshop-api`（Apache-2.0，Kotlin），JitPack 发布；但**它只是"契约"**，实现与调用都在宿主 |
| `WorkshopApi` 目前能力极少 | 只有 `play/pause/next/previous/seekTo/toast/changeExclusive/createConfigManager`；**没有**任何"当前曲目/曲库"查询 |
| 曲目身份只有 3 个入口 | `updateLyrics`（已废弃，等价）、`onBeforeLoadLyrics`、`onAfterLoadLyrics` —— **全是"加载歌词"事件**，我们三个都已挂上 |
| 最新版本 | `0.1.0-dev20`（2026-01-04，等同于 main HEAD）；JitPack 上的 `main-2ba42286c2-1` 就是同一 commit → **没有更新的可用版本** |

## 2. 上游正在做什么（关键）

- **issue #19**（open，2026-04-07）已提出我们要的接口：
  `PlaybackExtensionPoint.onCurrentPlaybackItemChanged(item, index)` 与
  `WorkshopApi.Playback.getCurrentPlaybackItem(): MediaItem?`。
  该 issue 的作者自述：没有公开 API，只能**反射访问宿主内部类与混淆**来实现（`spw-fixed-shuffle-plugin`、`spw-ai-playlist`）。
- **协作方回复（GaodaGG，2026-09-18）**：「部分 API 开发中……相关 API 可在 **#25** 中预览，**预计在 SPW 1.19 中可使用**」。
- **PR #25「在 SPW 1.19 会呈现的 API」**（open，面向 SPW 1.19.0，API 将升到 `0.1.0-dev21`），与本项目相关的部分：

| 新能力 | 对我们的意义 |
|---|---|
| **`WorkshopApi.playback.getCurrentMediaItem()`**（同步返回 MediaItem 快照，无歌返回 null） | **直接解决"未归因"**：不必再等"加载歌词"事件，随时可问"现在在放什么" |
| `WorkshopApi.library.*`（`getTrackById` / `getAllTracks` / `getTracks(afterId,limit)` / `getCoverById`） | 可用曲目 ID 做稳定主键（比 path 稳），未来可做"曲库视角"的统计 |
| `MediaItem` 新增 `id / year / genre / number / duration / isFavorite / readable` | 拿到 `duration`（可做"听完率"）、`id`（重命名/移动文件不再断档） |
| `getLyricsLines()` + `onLyricsLinesUpdated` | 完整歌词时间轴（本项目暂不需要） |
| 权限系统 `PluginPermission`：`KEY_BINDINGS` / `LIBRARY_READ` / `LIBRARY_WRITE` + `PluginPermissionDeniedException` | **查询当前曲目/曲库需要 `LIBRARY_READ` 权限**（用户授权，未授权会抛异常，必须捕获降级） |
| 打包方式：`.spmod` 后缀 + Gradle 插件 `com.xuncorp.spw.workshop`（`spmod {}` 声明元数据与权限、自动写 Manifest、`plugin` 任务产出 `.spmod`） | **我们的发版流程要改**：产物从 `ListenStats-x.y.z.zip` 变成 `.spmod`（zip 仍可用但不推荐） |
| 快捷键 `KeyBindingManager` / `ActionShortcut` | 本项目可选（例如"立即上报"做成快捷键） |

## 3. 我们到 1.19 时要做的事（适配清单）

1. **升 API 版本**：`libs.versions.toml` 的 `spw-workshop-api` → `0.1.0-dev21`（或 1.19 正式对应的版本）。仍是 `compileOnly`（宿主提供实现）。
2. **声明权限**：Manifest 加 `library-read`（若用快捷键再加 `key-bindings`）。**权限要能"未授权也能跑"**：统计主体（位置回调 + 歌词回调）不需要权限，只有"查当前曲目"才需要。
3. **消灭未归因**：
   - `onPosition` 在"尚无曲目信息"时，后台线程调 `WorkshopApi.playback.getCurrentMediaItem()`；
   - 成功 → 直接归因（替代现在的"占位 + 迟到归因"）；
   - `null` / `PluginPermissionDeniedException` / 其他异常 → 落回现有逻辑（占位 → 迟到归因 → 未归因）。
4. **主键升级（可选）**：`MediaItem.id` 做曲目主键，`path` 作为兼容字段保留（老数据用 path）。
5. **打包与发版**：接入 `com.xuncorp.spw.workshop` 插件产出 `.spmod`；快速通道、`publish.sh`、下载索引、README/CHANGELOG 一并改名（同时保留 zip 一段时间兼容 1.18.x 用户）。
6. **兼容策略**：一份代码要同时服务 1.18.x（dev20，无新接口）与 1.19（dev21）。
   - 编译期：只能编译到一个版本。建议**先按 dev20 发 1.18 兼容版**，1.19 正式发布后再切 dev21 发新版（宿主升级是真机可控的）；
   - 若要单包兼容，只能用"反射探测**方法是否存在**"来降级（探测 `WorkshopApi.playback` 是否有 `getCurrentMediaItem`，**不读宿主内部类**，因此不违反"零反射访问内部实现"的初衷）。
7. **回归**：`selftest`（纯逻辑）+ `hostsim`（假宿主，需同步升级到 dev21 才能覆盖新接口）。

## 4. 我们要不要向上游说话

- **不用重复提 issue**：#19 已覆盖 `getCurrentPlaybackItem` / `onCurrentPlaybackItemChanged`。
- **值得留言的两条**（需用户同意才对外发言）：
  1. 希望 `getCurrentMediaItem()` 之外再给一个**事件式回调**（`onCurrentMediaItemChanged`）—— 轮询不如事件省事，也与 #19 的诉求一致；
  2. 权限模型希望能**优雅降级**：未授权时插件依然可以只靠现有回调工作（我们就是这种场景）。
