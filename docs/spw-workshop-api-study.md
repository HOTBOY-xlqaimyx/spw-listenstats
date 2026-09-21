# SPW 创意工坊（Mod）API 研读笔记

- 仓库：<https://github.com/Moriafly/spw-workshop-api>
- 本地快照：`/workspace/spw/src/spw-workshop-api-main`（2026-09-16 拉取 main 分支 tar 快照，容器内无 git）
- 研读时间：2026-09-16（北京时间）

---

## 1. 一句话定位

这是 **Salt Player for Windows（SPW，椒盐音乐 Windows 版，Steam App 3009140）** 的
**第三方模组（Mod / 插件）开发接口库**：定义插件能实现什么（SPI 扩展点）、
宿主开放了什么（`WorkshopApi` 门面）、插件怎么被加载（PF4J）+ 怎么声明配置界面。

它**不是**宿主程序本体（宿主仓库是 [Moriafly/SPW](https://github.com/Moriafly/SPW)，
只有官网/反馈入口，464★），也**不包含** PF4J 实现——运行时由 SPW 自带。

| 指标 | 值 |
|---|---|
| 语言 / 工具链 | Kotlin + Java 21，Gradle 8.11.1 wrapper，ktlint（max_line_length=100） |
| 许可证 | Apache-2.0 |
| 发布渠道 | JitPack：`com.github.Moriafly:spw-workshop-api:<tag>` |
| 最新版本 | **0.1.0-dev20**（tag 2026-01-04），共 20 个 dev tag，全是 pre-release（GitHub「latest release」为空） |
| 活跃度 | 建仓 2025-05-26；主开发期 2025-05 ~ 2025-12-29；**dev20 之后 8 个月无提交** |
| 社区 | 63★ / 7 fork / 7 个 open issue（全是功能请求）；核心维护者 = Moriafly（作者）+ GaodaGG（社区主力，贡献示例插件与配置 API） |
| 规模 | 179 KB；`api` 模块只有 **7 个 Kotlin 文件**；无 CI（无 `.github`）、无测试源码 |

---

## 2. 仓库结构

```
spw-workshop-api/
├── api/                     ← 唯一发布物（库本体）
│   ├── build.gradle.kts     group=com.github.Moriafly, version=0.1.0-dev20, Java 21
│   └── src/main/java/com/xuncorp/spw/workshop/api/
│       ├── WorkshopApi.kt                 宿主门面（Playback / Ui / Manager）
│       ├── PlaybackExtensionPoint.kt      唯一的扩展点接口 + MediaItem/LyricsLine 数据类
│       ├── SpwPlugin.kt                   插件基类（PF4J Plugin 子类，带 PluginContext）
│       ├── PluginContext.kt               插件上下文（id/version/path/spwVersion/channel）
│       ├── WorkshopPluginManager.kt       PF4J DefaultPluginManager 子类（宿主侧用）
│       ├── Annotations.kt                 @SinceApi / @UnstableSpwWorkshopApi
│       └── config/{ConfigManager,ConfigHelper}.kt
├── example/                 ← 示例插件（既有独立 settings.gradle.kts，又被根 settings include）
│   ├── src/main/kotlin/com/gg/example/{MainPlugin,PlaybackExtensionExample,ConfigExample}.kt
│   ├── src/main/resources/preference_config.json
│   └── build.gradle.kts     manifest 元数据 + 自定义 plugin 打包任务
├── docs/configs.md          ← 配置界面 JSON 规范（199 行，中文，写得很细）
├── README.md                ← 用法 + manifest 元数据清单（部分内容已过期）
└── gradle/libs.versions.toml
```

包名 `com.xuncorp.spw.workshop.api`（xuncorp = 作者的另一个组织名），
发布 groupId 却是 `com.github.Moriafly`。

---

## 3. 运行机制（PF4J 插件体系）

1. **加载器**：SPW 内部用 `WorkshopPluginManager(vararg paths: Path) : DefaultPluginManager`（PF4J 3.12.0）。
2. **插件形态**：一个 **zip**，内部固定为
   - `classes/`：插件主 jar
   - `lib/`：插件运行期依赖 jar
   这是 PF4J 的目录式插件布局。
3. **清单元数据**（jar manifest）：
   - 必需：`Plugin-Class`（必须继承 `SpwPlugin`）、`Plugin-Id`（唯一，建议包名风格）、`Plugin-Version`
   - 常用：`Plugin-Name`、`Plugin-Provider`（作者）、`Plugin-Description`、`Plugin-Open-Source-Url`
   - 配置：`Plugin-Has-Config` = `true|false`
4. **扩展发现**：扩展点实现类打 `@Extension`，编译期由 **kapt** 跑 PF4J 自带的注解处理器
   （已核实 `pf4j-3.12.0.jar` 内含 `META-INF/services/javax.annotation.processing.Processor`
   → `org.pf4j.processor.ExtensionAnnotationProcessor`）生成 `extensions.idx`。
   **这就是示例里必须 `kapt(libs.spw.workshop.api)` 的原因**（api 依赖传递了 pf4j）。
5. **宿主注入**：`WorkshopApi.Companion.instance` 由 SPW 自行赋值（Kotlin 侧直接读 `WorkshopApi.playback`；
   Java 侧有 `@JvmStatic @JvmName` 的静态 getter）。插件侧**只读不写**。
6. **生命周期**：`SpwPlugin.start()/stop()/delete()`（PF4J 原语）+ 自研 `update()`
   （仅当用户在 SPW 内更新插件时调用，替换文件不触发）。
7. **二进制兼容**：Java 21 + `-Xjvm-default=all`，接口回调全部有默认空实现 ——
   插件只需覆盖关心的回调，新增回调不会破坏旧插件。

---

## 4. 当前 API 全貌（dev20）

### 4.1 `PlaybackExtensionPoint`（唯一扩展点，9 个回调）

| 回调 | 引入 | 说明 |
|---|---|---|
| `onStateChanged(State)` | SPW 1.3.16 / dev06 | State = Idle / Buffering / Ready / Ended |
| `onIsPlayingChanged(Boolean)` | 同上 | **禁止在此回调里调 `changeExclusive`** |
| `onSeekTo(Long)` | 同上 | 跳转通知 |
| `updateLyrics(MediaItem): String?` | 同上，**已废弃** | → 用 `onBeforeLoadLyrics` |
| `onBeforeLoadLyrics(MediaItem): String?` | 1.5.20 / dev07 | 抢在 SPW 默认逻辑前给歌词，返回 null 回落；**IO 线程** |
| `onAfterLoadLyrics(MediaItem): String?` | 1.5.20 / dev07 | 默认逻辑失败后的兜底，官方推荐；**IO 线程** |
| `onLyricsLineUpdated(LyricsLine?)` | 1.5.20 / dev07 | 当前行更新 |
| `onPositionUpdated(Long)` | 1.6.20 / dev10 | **每秒 1 次** |

数据类（内嵌在接口里）：`MediaItem(title, artist, album, albumArtist, path)`，
多值以「/」分割；`LyricsLine(startTime, endTime, lyricsCells, pureMainText, pureSubText)`
+ `Cell(startTime, endTime, text)`（卡拉 OK 最小单元）。

### 4.2 `WorkshopApi.Playback`（主动控制）

`changeExclusive(Boolean)`（**必须主线程**，1.3.16）、`pause()` / `play()` / `previous()` / `next()`（1.6.20）、
`seekTo(Long ms)`（1.7.0）。

### 4.3 `WorkshopApi.Ui`

`toast(text, ToastType)`，ToastType = Success / Warning / Error（1.5.20）。

### 4.4 `WorkshopApi.Manager` + config 包（全部标 `@UnstableSpwWorkshopApi`）

- `createConfigManager()`（dev11 起；旧的无参带 pluginId 版本已废弃）
- `ConfigManager`：`getConfig()`（默认 `config.json`）、`getConfig(fileName)`（同名缓存同一实例）、
  `addConfigChangeListener(listener)` / `addConfigChangeListener(fileName, listener)` / `removeConfigChangeListener`
- `ConfigHelper`：泛型 `get<T>(key, default)`、`set(key, value)`（内存态，支持 `a.b` 点号嵌套）、
  `save()`、`reload()`、`getConfigPath()`
- 配置文件位置（文档口径）：`%APPDATA%/workshop/data/<plugin_id>/<config 字段>`

### 4.5 辅助类型

- `PluginContext(pluginId, pluginVersion, pluginPath, spwVersion, spwChannel)`
- `enum Channel { Steam, MS }`（Steam 版 / 微软商店版）
- `@SinceApi(spwVersion, modVersion)`：双版本号标注「宿主版本 + API 版本」，
  例如 `@SinceApi("1.9.2", "0.1.0-dev15")`。（第二个参数名叫 `modVersion`，实际是 API 版本，命名略歧义。）
- `@UnstableSpwWorkshopApi`：`RequiresOptIn(Level.ERROR)`，用 config / UI 面板类 API 必须显式 opt-in
  （Kotlin `@OptIn`，示例用文件级 `@file:OptIn(...)`）。

---

## 5. 插件配置界面（声明式，SPW 渲染）

`src/main/resources/preference_config.json` 描述配置页；SPW 读取后**自己渲染 UI**，插件不写界面代码。

- 顶层 `configs: []`，每项 = `{ title, config(相对配置文件名), preferences[] }`
- 5 种控件：
  - `switch`：布尔
  - `list`：单选，需 `entries`（显示）+ `entry_values`（值）
  - `seekbar`：数值，需 `min` / `max`（Float），**不支持 summary**
  - `edittext`：点开 dialog 修改
  - `button`：不存值、不需要 key，需 `on_click = 全限定静态方法名`（反射调用，**无参静态**；
    Kotlin 伴生对象必须 `@JvmStatic` + `@JvmName`，示例里 `MainPlugin.onExampleButtonClick` 就是这么写的）
- 通用字段：`type` / `title` / `key` / `default_value` / `summary`(可选) / `arrow_type`(按钮: none|link|arrow)
- 改动即自动落盘，插件用 `ConfigManager::addConfigChangeListener` 响应

---

## 6. 开发/交付流程（实测可行的路径）

1. 新建 Kotlin JVM 库工程，Java 21；依赖 `com.github.Moriafly:spw-workshop-api:0.1.0-dev20`，
   `compileOnly` + `kapt`（**不要**把 API 打进插件包）。
2. 主类 `class XxxPlugin(pluginContext: PluginContext) : SpwPlugin(pluginContext)`；
   需要扩展就写 `@Extension class E : PlaybackExtensionPoint { … }`。
3. `jar` 任务写 manifest 的 `Plugin-*` 元数据；再注册一个 `plugin`（Jar 任务、`archiveExtension = "zip"`）
   把 `classes/`（主 jar）与 `lib/`（runtimeClasspath 的 jar）打进去。
4. 启动期自检：`WorkshopApi.ui.toast("...", Success)`——示例主类就是这么验证加载成功的。
5. 安装：产物丢进 `%APPDATA%/Salt Player for Windows/workshop/plugins/`，
   或按真实插件 README 的做法「设置 → 创意工坊 → 模组管理 → 右上角导入模组 → 选 zip」，
   再在「模组管理」里启用、改配置、切歌验证。
6. 作者明确表态：**希望 Mod 开源、建议不混淆代码**。

---

## 7. 版本演进与兼容性（关键风险）

| 阶段 | 内容 |
|---|---|
| dev01–dev06（2025-05~08） | 起步：`PlaybackExtensionPoint` 基础回调、`@SinceApi`、`-Xjvm-default=all` |
| dev07–dev10（2025-08） | 歌词（before/after/line/position）、UI Toast、跳转；配置 API 从零长出（ConfigManager/ConfigHelper） |
| dev11–dev13（2025-09） | `seekTo`、`createConfigManager()`、`PluginContext`、`removeConfigChangeListener`、`SpwPlugin.update()` |
| dev15–dev19（2025-12-29） | **`PlaybackScreenExtensionPoint`**：用 Compose + Salt UI 让插件自定义**播放界面歌词面板**（面向 SPW 1.9.2），新增 `data.lyrics.{LyricsDocument,LyricsLine,LyricsCell}`、`LyricsPanelStyle` |
| **dev20（2026-01-04）** | **整套播放界面扩展点连同 `data/lyrics/*` 被删除**，只留下 nested `LyricsLine`；版本号 +1 即发布 |

两点后果：

- **API 停摆而宿主继续跑**：宿主已到 **SPW 1.17.0**（社区插件 README 提到），API 仍停在面向 1.9 的 dev20。
  社区插件的应对是**反射读宿主内部类/字段**（例如 `EVA-Hollow001/spw-ttml-lyrics`
  标注「内部类名/字段名来自本机 SPW 1.17.0 只读分析」，`GaBoron/Dynamic-Lyrics-Island-for-SPW`
  用「失败即回退的宿主探测」恢复完整歌词轴）。
- **Compose / Salt UI 成了死依赖**：`api/build.gradle.kts` 仍以 `api(...)` 引入
  `compose.ui` / `compose.foundation`（1.11.0-alpha01）+ `io.github.moriafly:salt-ui`（2.9.0-alpha02），
  已核实 `api/src` 中**再无任何 `androidx.compose` / `moriafly.salt` 引用**，
  但 JitPack POM 里它们是 **compile 作用域**，会进入所有插件的编译类路径。

另外注意：这是 **pre-release 全量**仓库，没有稳定版；API 兼容性靠 `@SinceApi` 标注与宿主做版本匹配，
插件应针对自己需要的**最低 API 版本**编译，而不是盲目跟最新 tag。

---

## 8. 生态实况（用 GitHub 代码搜索 + 仓库元数据核实）

社区 Mod 全部以 `compileOnly + kapt` 引 dev20（部分老插件还停在 dev08/dev10）：

| 仓库 | ★ | 做什么 |
|---|---|---|
| [GaodaGG/SaltSteamPlugin](https://github.com/GaodaGG/SaltSteamPlugin) | 63 | Steam 丰富状态（`{title}{artist}{album}{mainLyrics}{position}…` 模板，5 秒限频、不能换行） |
| [zmxlsss666/SaltLyricPlugin](https://github.com/zmxlsss666/SaltLyricPlugin) | 54 | 桌面歌词 + HTTP API 控制（SPW 1.8.0 自带桌面歌词后已停更） |
| [GaBoron/Dynamic-Lyrics-Island-for-SPW](https://github.com/GaBoron/Dynamic-Lyrics-Island-for-SPW) | 24 | 灵动词岛：逐字歌词、悬浮窗、播放控制 |
| [univers629/SPW-Tag-Workbench](https://github.com/univers629/SPW-Tag-Workbench) | 16 | 音乐标签在线源批量匹配/写入 |
| [GaodaGG/SaltDiscordPlugin](https://github.com/GaodaGG/SaltDiscordPlugin) | 14 | Discord 丰富状态 |
| [zmxlsss666/TaskbarLyricsPlugin](https://github.com/zmxlsss666/TaskbarLyricsPlugin) | 13 | 任务栏歌词 |
| [Andy-Landers/salt-player-navidrome-plugin](https://github.com/Andy-Landers/salt-player-navidrome-plugin) | 10 | 接 Navidrome |
| [Le672/SPW-MultiLyrics](https://github.com/Le672/SPW-MultiLyrics) | 2 | 多平台在线歌词（Apple/网易/QQ/酷狗/酷我/Spotify） |
| [akikiri/LastFmForSaltPlayer](https://github.com/akikiri/LastFmForSaltPlayer) | 2 | Last.fm scrobble |
| [Minokori/Minokori.Plugin.NcmConverter](https://github.com/Minokori/Minokori.Plugin.NcmConverter) | 1 | 网易云 .ncm 解码 |
| [EVA-Hollow001/spw-ttml-lyrics](https://github.com/EVA-Hollow001/spw-ttml-lyrics) | 1 | TTML 逐字卡拉 OK 歌词 |
| [AugustuXue/spw-fixed-shuffle-plugin](https://github.com/AugustuXue/spw-fixed-shuffle-plugin) | 0 | 固定随机（已脱离官方模板独立构建） |
| [Alive-Project/AliveSalt](https://github.com/Alive-Project/AliveSalt) / [univers629/SaltAlivePlugin](https://github.com/univers629/SaltAlivePlugin) | 0 | 音乐状态上报 |

**生态特征**：歌词/状态上报两类占绝大多数；不少插件要反射探宿主内部才能实现 API 没暴露的能力。

---

## 9. 待办需求（7 个 open issue，反映 API 缺口）

- #19（2026-04-07）播放队列 / 播放模式的读写与监听
- #20（2026-07-19）音频数据流接口
- #21（2026-08-10）快捷键控制
- #23（2026-09-08）读取（编辑）播放列表
- #24（2026-09-10）暴露**解析后的完整歌词时间轴**（现有回调只有「当前行」，合唱/重叠行无法还原）
- #15（2025-09-09）窗口标题修改
- #22（2026-08-31）建一个模组索引仓库方便发现 Mod；评论区提到「早日开放（Steam）创意工坊」

另有已闭但**未合并**的 PR：#17（`Plugin-Update-Url` + 插件更新机制）。

---

## 10. 仓库质量观察 / 踩坑清单

1. **README 已过期**：示例写 `class ClassicalPlugin : SpwPlugin()`（无参），
   而 dev13 起构造函数**必须传 `PluginContext`**；依赖示例写 dev14，最新是 dev20。
2. **僵尸版本号**：根与 example 的 `libs.versions.toml` 都声明 `spw-workshop-api = "0.1.0-dev10"`，
   而实际用的是 `project(":api")`（example 里 Maven 依赖那两行是注释）。
3. **example 双 settings 冲突**：根 `settings.gradle.kts` 有 `include(":example")`，
   example 自己又有 `settings.gradle.kts`（`rootProject.name = "example-plugin"`，不 include `:api`）
   —— 所以 example **只能从根工程构建**（`project(":api")` 才成立），单独打开会失败。
4. **构建配置残留 Android 属性**：`gradle.properties` 里 `android.useAndroidX` / `android.nonTransitiveRClass`
   对 JVM 工程无效。
5. **工具链版本不一致**：wrapper 8.11.1（根）vs 8.14（example）；Kotlin 2.3.0（根）vs 2.2.0（example）vs
   README 里的 2.0.21。
6. **无 CI、无测试**：声明了 `testImplementation(libs.junit)` 但没有任何测试源码；没有 GitHub Actions。
7. **死依赖**：见 §7 的 Compose / Salt UI。
8. **发布件为零附件**：20 个 tag 全是 pre-release，`releases/latest` 返回空；取件只能走 JitPack。
9. **路径口径待实测**：`docs/configs.md` 写配置目录 `%APPDATA%/workshop/data/<plugin_id>/`，
   而示例 README 写插件目录 `%APPDATA%/Salt Player for Windows/workshop/plugins/`
   —— 两者父目录层级不一致，动手前以真机实测为准。
10. **`.idea/` 已入库**：虽然 `.gitignore` 里写了 `/.idea/`（先提交后忽略）。

---

## 11. 结论：这个项目对我们意味着什么

- 想给 SPW 写 Mod：**可用能力只有「播放监听 + 播放控制 + Toast + 声明式配置」**，
  想要队列、音频流、完整歌词轴、快捷键，必须自己反射或等上游。
- 想贡献上游：仓库小而清晰，7 个文件、无 CI/测试，PR 门槛低（历史 PR 多由社区 GaodaGG 提交并被合并）；
  当前最大的空白是 **#22 模组索引仓库** 与 **#24 完整歌词时间轴**。
- 风险：API 自 2026-01 停更，宿主已走到 1.17.0，**版本漂移**是最大的长期风险；
  同时 issue 区在讨论开放 Steam 创意工坊，可能改变 Mod 的分发方式。

## 附：本地已就绪的东西

- 源码快照：`/workspace/spw/src/spw-workshop-api-main/`（main 分支，含 example）
- 依赖可用性已核实：JitPack `0.1.0-dev20` POM 可下载；pf4j 3.12.0 含注解处理器
- 容器内无 git/gradle/JDK；如需真编译要在宿主或另装工具链（宿主有 python3，无 JDK 的话需确认）

---

## 附二：真实编译踩到的坑（2026-09-16 实测，写插件必看）

1. **API 的死 Compose 依赖会污染消费方依赖图**：`0.1.0-dev20` 的 POM 里
   `org.jetbrains.compose.ui:ui-desktop:1.11.0-alpha01`、`compose.foundation:foundation-desktop`、
   `io.github.moriafly:salt-ui-desktop:2.9.0-alpha02` 都是 **compile 作用域**，传递下去最终指向
   `androidx.lifecycle/androidx.compose.runtime/androidx.savedstate/...`，**只在 Google Maven**。
   直接 `compileOnly(libs.spw.workshop.api)` 会在依赖解析阶段就失败：
   `Could not find androidx.lifecycle:lifecycle-common:2.9.4 ...`
   （这正是 dev20 删除播放界面扩展点却忘了删依赖的后果）。
   解法：
   ```kotlin
   compileOnly(libs.spw.workshop.api) { isTransitive = false }
   compileOnly(libs.pf4j)   // api 类层次需要 org.pf4j.ExtensionPoint / Plugin
   kapt(libs.pf4j)          // 注解处理器确实在 pf4j.jar 内
   ```
2. **`kapt(libs.spw.workshop.api)` 不是必须的**，官方 README 那样写只是借传递依赖把 pf4j 带进来；
   直接 `kapt(pf4j)` 更准。处理器运行日志：`Note: Processing @org.pf4j.Extension`，
   产物 `classes/META-INF/extensions.idx`。
3. **插件 zip 的 `classes/` 是展开的 class 目录**（`into("classes"){ with(jarTask) }` 会把 jar 解包），
   不是嵌套 jar —— 这是对的，别改成 `from(jarFile)`。
   ⚠️ 但真正的坑在后面（2026-09-17 用模拟宿主实测复现）：
   **PF4J 对「目录型插件」读的描述符是 `<插件目录>/META-INF/MANIFEST.MF`，也就是 zip 解包后顶层那份，
   它来自外层打包 Jar 任务自己的清单，而不是内层插件 jar 的清单。**
   官方 README 的模板只给内层 jar 设 `Plugin-*`，外层那份是空清单 →
   PF4J 得到 `id=null class=org.pf4j.Plugin` 的空描述符 →
   `InvalidPluginDescriptorException: Field 'id' cannot be empty`，插件根本加载不了。
   正解：把 `Plugin-*` 抽成一个 map，**内层 jar 与打包 zip 两个 Jar 任务的 manifest 都要写**。
   （`DefaultPluginDescriptor` 的默认值就是 `pluginClass=org.pf4j.Plugin`，这正是空清单时的表现。）
4. **PF4J 默认描述符只映射 8 个键**：`Plugin-Id` / `Plugin-Class` / `Plugin-Version` / `Plugin-Provider` /
   `Plugin-Description` / `Plugin-Dependencies` / `Plugin-Requires` / `Plugin-License`。
   `Plugin-Name`、`Plugin-Has-Config`、`Plugin-Open-Source-Url`（README 明确列出的可用元数据）
   **不会进 `descriptor.getProperties()`**——SPW 的模组管理必须自己读清单文件。写插件时别指望从描述符取这些值。
5. **PF4J 3.12 没有公开的 `setPluginFactory`**：`getPluginFactory()` 是 protected，定制方式是重写
   `protected PluginFactory createPluginFactory()`，而它在父类构造期就会被调用
   （Java 里没法先赋值字段再 super()，只能用 ThreadLocal/静态工厂方法传递）。
   这也说明 `SpwPlugin(PluginContext)` 的注入只能由 SPW 自己重写工厂实现——
   API 里的 `WorkshopPluginManager` 只是个 `DefaultPluginManager` 别名，注入逻辑不在开源部分。
6. 属性命名冲突：插件主类里定义 `private var log` 会**遮蔽 PF4J `Plugin` 的 Java 字段 `log: Logger`**
   （编译警告 *hides Java field 'log'*），改名 `logger` 即可。
7. `SpwPlugin` 的构造函数**必须**是 `(PluginContext)`（宿主用自定义 PluginFactory 注入），
   README 里 `class X : SpwPlugin()` 的写法是过期文档。
8. 构建环境：NAS 无 JDK → 用 `gradle:8.14-jdk21` 一次性容器（详见
   `/vol2/1000/docker/spwbuild/docker-compose.yml`），Gradle 缓存挂到 `data/gradle`。
9. **fnOS 挂载卷的文件权限会退化成 `000`**：Gradle 在挂载卷里产出的 zip，`ls -l` 显示 `----------`，
   本地 `cat` 还能读（ACL 生效），但 `cp` / Java `Files.copy` 会把这个 000 带到新位置 →
   目标文件真的读不了（PF4J 报 `FileNotFoundException ... Permission denied`）。
   解法：产出后 `setReadable(true, false)`，或复制后显式 `chmod`。
10. 用「假宿主」做集成测试很值：上面第 3 条这种 bug，纯逻辑自测和包结构检查都发现不了，
    只有让真实 PF4J 加载真实 zip 才会暴露。参考 `listenstats-hostsim/`（41 项断言）。


---

## 附三：真机实测 —— Linux 版 SPW（2026-09-17）

用 DepotDownloader 拿到的 Steam Linux 包（`spl.zip`，142 MB，jpackage 应用镜像），在 NAS 上无头跑通。
这是本项目第一次拿到**真实宿主**的行为证据，结论比任何推断都硬：

### 宿主实况

| 项 | 实测值 |
|---|---|
| 应用 | Salt Player **1.18.0**（Linux amd64，Steam 渠道；`bin/Salt Player` 启动器 + `lib/runtime` 内嵌 JRE） |
| 技术栈 | Kotlin 应用（主类 `MainKt`），代码大量混淆（`com.xuncorp.voxzen.*`，内部代号 voxzen） |
| 应用本体 | 藏在 `lib/app/ffmpeg-x64.so`（jpackage classpath 指向它，实为 23450 个条目的 jar） |
| AOT | `-XX:AOTCache=voxzen.aot`（AOT **缓存**，不是原生镜像 → 仍是标准 JVM，插件体系完好） |
| 运行时 | 内嵌 **JDK 25.0.4.1**（jlink 裁剪） |
| PF4J | **3.14.1**，`deployment` 模式 |
| 创意工坊 API | 完整内嵌 `com.xuncorp.spw.workshop.api.*`（23 个类，含 `SpwPlugin`/`WorkshopApi`/`PlaybackExtensionPoint`/`ConfigManager`） |

### 插件体系的关键路径（写插件必知）

- **插件根目录**：`~/.local/share/Salt Player/workshop/plugins`（`$XDG_DATA_HOME`；Windows 侧是 `%APPDATA%\Salt Player for Windows\workshop\plugins`）
- **启用机制**：插件根目录下的 `enabled.txt`，每行一个 pluginId。**只丢 zip 进去会被判 `is disabled`**（应用内「模组管理 → 启用」写的就是这个文件）
- **插件数据目录**：`~/.local/share/Salt Player/workshop/data/<pluginId>/`
- **加载流程（真机日志原文）**：
  `Expanded plugin zip → Found descriptor（读的是清单，pluginId/provider/description 全部正确）→ Loaded plugin（classes/ + lib/）→ resolved → Start plugin → LegacyExtensionFinder 读取 extensions.idx → Added extension → 实例化扩展`
- **描述符查找器**：`CompoundPluginDescriptorFinder` = PF4J 的 `PropertiesPluginDescriptorFinder`（找 `plugin.properties`，没有）+ **宿主自己的清单查找器**（混淆类 `androidx.compose.ui.gQ`）。也就是说宿主读的就是**插件目录顶层那份 `META-INF/MANIFEST.MF`** —— 与本项目附二第 3 条实测一致，两份清单都写 `Plugin-*` 是**必需**的。

### ⚠️ 最大的坑：jlink 运行时没有 `java.net.http`

真实运行时的模块清单：

```
java.base java.compiler java.datatransfer java.xml java.prefs java.desktop java.instrument
java.logging java.management java.security.sasl java.naming java.security.jgss
java.transaction.xa java.sql jdk.accessibility jdk.crypto.ec jdk.security.auth jdk.unsupported
```

**没有 `java.net.http`**。插件里只要用了 `java.net.http.HttpClient`，在真机上就是
`java.lang.NoClassDefFoundError: java/net/http/HttpClient`，而且是在**插件初始化阶段**炸掉（实测把本地统计一起带走）。
写 SPW 插件要用 HTTP 就用 **`java.net.HttpURLConnection` / `java.net.Socket`（java.base）**；
同理，别假设宿主运行时里有 `java.sql`、`java.xml` 之外的模块。`java.desktop` 是有的（要用 `Desktop` 打开目录没问题）。

### 无头运行的其他实测结论（对做自动化有参考价值）

- 全程可无头跑通：`Xvfb :99` + `LIBGL_ALWAYS_SOFTWARE=1`，Skiko 报 `Cannot create Linux GL context` 后自动回退，应用照常启动
- **没有 Steam 客户端也能启动**：`SteamAPI_Init(): SteamAPI_IsSteamRunning() did not locate a running instance of Steam` 之后应用继续跑（只是 Steam 相关功能不可用）
- **没有声卡也不影响加载插件**：BASS 初始化 `DeviceDriverNotAvailable`，插件体系照常初始化；但**要验证播放回调必须给真实音频设备**
- `java.home`/`user.home` 用 **passwd 里该 uid 的 home**，不认 `$HOME` 环境变量（容器里要注意）
- fnOS 卷上解压出来的文件权限会退化，启动器需要显式 `chmod +x`

### 启动限制：无 Steam 会话时应用拒绝继续

容器里没有 Steam 客户端，`SteamAPI_Init()` 失败后应用会弹出模态框
**「This application requires a package identity.」**并停在启动阶段（点掉后画面无变化）——这是**渠道/所有权校验**，
不是缺陷；也**没有**官方支持的无 Steam 启动方式（官网「启动行为」「FAQ（Legacy）」都只讲单实例与安装问题）。

注意执行顺序：**workshop / PF4J 的插件加载发生在该校验之前**，所以插件依然能被完整加载、`resolved`、`Start plugin`、
扩展点实例化、`start()` 里落盘日志。要做**播放级**验证（真实回调 → 统计 → 上报），必须在正常跑在 Steam 里的客户端上做。

另外，官方创意工坊文档明确写了：**「自 1.6.10 EA 版本开始，Mod 将默认处于被禁用（DISABLED）状态」**——
与实测一致（丢进目录的插件会被判 `is disabled`，必须在应用内启用或写 `enabled.txt`）。
