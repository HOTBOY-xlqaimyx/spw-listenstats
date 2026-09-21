# 宿主实测笔记（SPW 创意工坊）

> 这里记的是**在真实宿主上量到的事实**，不是官方文档的转述。写插件前先看这份能省很多时间。
> 来源：2026-09-17 起在 Windows 11 与 Linux 版 Salt Player 1.18.0 上的实测（本插件就是验证载体）。

## 宿主版本与运行时

| 项 | Windows 版 | Linux 版 |
|---|---|---|
| 应用 | Salt Player 1.18.0（Steam 渠道） | 同左，`bin/Salt Player` + `lib/runtime` |
| 打包方式 | jpackage 应用镜像 | 同左（`lib/app/ffmpeg-x64.so` 实为应用 jar，`voxzen.aot` 是 AOT **缓存**不是原生镜像） |
| Java 运行时 | **JetBrains Runtime 25.0.3** | **JBR 25.0.4.1**（jlink 裁剪） |
| PF4J | 3.14.1，`deployment` 模式 | 同左 |
| 创意工坊 API | `com.xuncorp.spw.workshop.api.*`（与 dev20 开源仓库一致） | 同左 |

**运行时模块清单**（实测 Linux 版，Windows 版同样缺 `java.net.http`）：

```
java.base java.compiler java.datatransfer java.xml java.prefs java.desktop java.instrument
java.logging java.management java.security.sasl java.naming java.security.jgss
java.transaction.xa java.sql jdk.accessibility jdk.crypto.ec jdk.security.auth jdk.unsupported
```

→ **没有 `java.net.http`**。用 `HttpClient` 会在插件初始化阶段 `NoClassDefFoundError`，把整个插件打挂。
HTTP 请用 `java.net.HttpURLConnection`（`java.base`）。`java.desktop` 有（`Desktop` 可用）。

## 插件目录与启用

| 项 | Windows | Linux |
|---|---|---|
| 插件根目录 | `%APPDATA%\Salt Player for Windows\workshop\plugins` | `~/.local/share/Salt Player/workshop/plugins` |
| 插件数据目录 | `…\workshop\data\<pluginId>\` | `…/workshop/data/<pluginId>/` |
| **启用机制** | 插件根目录下的 `enabled.txt`，每行一个 pluginId | 同左 |

- 官方文档明确：**自 1.6.10 EA 起 Mod 默认处于被禁用（DISABLED）状态**；直接丢 zip 进目录会被判 `is disabled`
- 应用内「设置 → 创意工坊 → 模组管理」的导入/启用写的也是上面这两个位置

## 加载流程（真实日志原文）

```
Lookup plugins in '…/workshop/plugins'
Unzip  → Expanded plugin zip 'ListenStats-x.y.z.zip' in 'ListenStats-x.y.z'
CompoundPluginDescriptorFinder：PropertiesPluginDescriptorFinder（找 plugin.properties，没有）
                              → 宿主自己的清单查找器（读 <插件目录>/META-INF/MANIFEST.MF）
Found descriptor PluginDescriptor [pluginId=…, pluginClass=…, version=…, provider=…]
DefaultPluginLoader：Add '…/classes/'、'…/lib/*.jar'
Plugin '…@x.y.z' resolved → Start plugin '…@x.y.z'
LegacyExtensionFinder：读取 extensions.idx → Added extension '…' → 实例化
```

要点：
1. **插件 zip 必须让解包后的顶层 `META-INF/MANIFEST.MF` 带 `Plugin-*`**（外层打包任务的清单，不是内层 jar 的）
2. `classes/` + `lib/` 布局被 `DefaultPluginLoader` 接受
3. 扩展点靠 kapt 生成的 `META-INF/extensions.idx` 发现（`LegacyExtensionFinder`）

## 回调行为（实测）

| 现象 | 结论 |
|---|---|
| `onPositionUpdated` | 约 1 次/秒（5 分钟 302 次） |
| `onBeforeLoadLyrics` | **只在真正加载歌词时触发**：切歌时触发，**插件加载时已在播的那首不触发**，且 API 没有「取当前曲目」 |
| `onStateChanged` | 每首至少 `Ended`；暂停/空闲时 `Idle` |
| `onIsPlayingChanged` | 暂停/继续时触发（用它就能识别暂停，墙钟空闲阈值只是兜底） |
| `onSeekTo` | 只在真的拖进度条时触发（5 天仅 1 次） |
| `onLyricsLineUpdated` | 歌词滚动时高频触发 |
| 切歌瞬间 | 宿主会**先补一次旧曲目的位置回调**，然后才给新曲目的 `MediaItem`（会产生一个 0 秒会话） |

## 启动与校验

- **没有 Steam 会话时**，Linux 版会弹模态框 `This application requires a package identity.` 并停在启动阶段
  （**插件在此之前已经加载并 start**）。这是渠道/所有权校验——**不要试图规避**。
- 无头可跑：`Xvfb :99` + `LIBGL_ALWAYS_SOFTWARE=1`（Skiko 报 `Cannot create Linux GL context` 后自动回退）；
  ALSA 用 `pcm.!default { type null }` 可让 BASS 打开设备
- JVM 的 `user.home` 取自 passwd/token 而非 `$HOME`（容器里要注意）
- fnOS 卷上解压出的文件权限会退化，启动器需要显式 `chmod +x`

## 官方文档索引

- 安装/渠道：<https://moriafly.com/program/spw/doc/install.html>
- 创意工坊（Mod 默认禁用）：<https://moriafly.com/program/spw/doc/workshop.html>
- 插件 API 仓库：<https://github.com/Moriafly/spw-workshop-api>
