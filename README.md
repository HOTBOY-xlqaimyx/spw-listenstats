# SPW 听歌统计 · spw-listenstats

> Salt Player for Windows 创意工坊模组：记录**每首歌听了多久**，并生成一份**双击就能看**的报告页。
> 当前版本 **1.0.0**（首个正式版）· 许可证 **Apache-2.0**

**设计取向：本地是默认形态，服务端是可选扩展。** 装上插件什么都不用配就能用；
想要手机看、或者多设备汇总，再部署接收端与在线页（都在本仓库里）。

| 组件 | 目录 | 作用 | 额外需要什么 |
|---|---|---|---|
| **插件**（Kotlin / PF4J） | `plugin/` | 记录会话、维护本地报告、可选上报 | Windows + Salt Player 1.18+ |
| **本地报告**（单文件 HTML） | 由插件生成 | 总时长 / 每日 / 艺人 / 曲目榜 / 最近播放 | 只要插件，零依赖 |
| **接收端**（Python 标准库） | `receiver/` | 收上报、聚合成报告数据 | 一台常开的机器（NAS / 小主机） |
| **在线页 + 入口页**（纯前端） | `web/` | 手机扫码看、多设备汇总 | 任意静态服务器 |

## 快速上手（3 步）

1. **装**：下载 `ListenStats-1.0.0.zip` → SPW：设置 → 创意工坊 → 模组管理 → **导入模组** → 启用。
2. **听**：什么都不用配。听完一首后，插件会自动生成/刷新
   `我的文档\SPW听歌统计\ListenStats-本地报告.html`，**双击就能看**（也可以点配置页的「打开本地报告」）。
3. **（可选）上手机**：部署 `receiver/`（收数）+ `web/`（在线页），在插件「上报」分组填上报地址与在线报告页地址；
   之后手机扫报告页上的二维码即可。

> 详细安装、配置、数据文件、上报协议、日志排障：见 **[plugin/README.md](plugin/README.md)**。

## 目录结构

```
spw-listenstats/
├── plugin/                    Kotlin 插件（Gradle 工程，自带包装器）
│   ├── src/main/kotlin/…      统计引擎 / 存储 / 上报 / 本地报告 / 自测（11 个 .kt）
│   ├── src/main/resources/    preference_config.json（配置页声明）、web/report.html（内置报告页模板）
│   ├── docs/                  架构、宿主实测笔记、测试策略、发版流程、上游 1.19 计划
│   ├── DESIGN.md              设计边界与决策
│   ├── README.md              插件主文档（安装 / 配置 / 协议 / 排障）
│   └── gradlew · build.gradle.kts · gradle/   构建入口与包装器
├── receiver/                  Python 接收端（只用标准库）
│   ├── receiver.py · docker-compose.yml · README.md
├── web/                       报告页（单文件纯前端）
│   ├── index.html             页面源文件
│   └── tools/build-report-page.py   部署前生成内联二维码
├── docs/                      宿主 API 研究、组件关系图、发布检查清单
├── .github/                   CI 工作流 + Issue / PR 模板
├── README.md · CHANGELOG.md · CONTRIBUTING.md · SECURITY.md · CODE_OF_CONDUCT.md
├── LICENSE · NOTICE           Apache-2.0 与第三方声明
└── .editorconfig · .gitattributes · .gitignore
```

> 只跟踪上述内容；个人听歌数据（`sessions.jsonl` / `listenstats.json` / `report-data.json`）、
> 构建产物与内部运维资产都在 `.gitignore` 里。

## 文档索引

| 文档 | 内容 |
|---|---|
| [plugin/README.md](plugin/README.md) | **主文档**：安装、配置表、数据文件、上报协议、日志排障、构建 |
| [plugin/DESIGN.md](plugin/DESIGN.md) | 设计边界与决策（会话切分规则、上报协议、明确不做的事） |
| [plugin/docs/ARCHITECTURE.md](plugin/docs/ARCHITECTURE.md) | 组件与数据流、持久化格式、本地 vs 在线两种输出形态 |
| [plugin/docs/HOST-NOTES.md](plugin/docs/HOST-NOTES.md) | 宿主实测笔记（真实路径、加载流程、回调行为、运行时模块坑） |
| [plugin/docs/TESTING.md](plugin/docs/TESTING.md) | 三层验证各覆盖什么、怎么跑、历史教训 |
| [plugin/docs/RELEASE.md](plugin/docs/RELEASE.md) | 版本规则、发版命令、快速通道、发版清单 |
| [plugin/docs/UPSTREAM-1.19.md](plugin/docs/UPSTREAM-1.19.md) | 上游 API 现状与 SPW 1.19 将带来的能力（含「查询当前曲目」） |
| [receiver/README.md](receiver/README.md) | 接收端部署、聚合口径、接口与排障 |
| [docs/spw-workshop-api-study.md](docs/spw-workshop-api-study.md) | 宿主 API 调研与踩坑清单 |
| [docs/diagrams/spw-listenstats-arch.html](docs/diagrams/spw-listenstats-arch.html) | 组件关系图（单文件，浏览器直接打开） |

## 兼容性

- **Windows 版 Salt Player 1.18.x**（实测 1.18.0 / 1.18.5）；Linux 版实测过加载与启动。
- 宿主是 **jlink 裁剪运行时**：插件不引入任何第三方运行时依赖，联网只用 `HttpURLConnection`
  （该运行时里没有 `java.net.http`）。
- 上游 API 版本 `0.1.0-dev20`（当前主线）。SPW 1.19 会带来更多能力，见 UPSTREAM 文档。

## 已知限制（诚实清单）

- 宿主**没有切歌回调**，曲目身份只在「加载歌词」时给出。因此「插件启动时已经在播的那首」
  宿主可能一直不告知曲目 —— 这段时间记为**未归因**：计入总时长与每日，但**不进曲目榜**（0.7.0 起会尽量回填）。
- **逐条会话明细**从 0.9.0 起才有记录，更早的会话无法回填（聚合数据不受影响）。
- 曲目表上限 5000 首、会话历史滚动保留 2000 条、单批上报最多 200 条。

## 许可证与致谢

本项目采用 **Apache License 2.0**（见 [LICENSE](LICENSE)）；随包分发的第三方组件声明见 [NOTICE](NOTICE)。
特别感谢 [spw-workshop-api](https://github.com/Moriafly/spw-workshop-api)（Moriafly）提供的模组 API。
本项目是非官方第三方插件，与 Salt Player 官方无隶属关系。
