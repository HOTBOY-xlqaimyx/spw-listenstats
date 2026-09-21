# 参与贡献

感谢你愿意花时间。这个项目很小，但有几条**硬约束**是踩过坑换来的，改动前请先读一遍。

## 环境

| 需要 | 说明 |
|---|---|
| JDK 21 | 插件与假宿主都用它编译（`jvmToolchain(21)`） |
| Gradle | 用仓库自带的包装器：`./gradlew`（无需预装 Gradle） |
| Python 3.10+ | 只在改接收端 / 页面构建脚本时需要；两者都只用标准库（页面构建另需 `qrcode`） |

## 构建与自测

```bash
cd plugin
./gradlew selftest      # 必需：纯逻辑自测（不依赖宿主），必须全绿
./gradlew plugin        # 打包成 build/dist/ListenStats-<版本>.zip
```

`selftest` 是**准入门槛**：改统计引擎、存储、上报、配置页文案或页面模板，都要补/改对应的检查项。
提交 PR 前请贴出 `---- selftest: N 项检查 ---- 全部通过 ✅` 的输出。

## 硬约束（改动前必读）

1. **绝不引用宿主内部类、绝不反射访问私有实现**。宿主是闭源应用，只能使用
   `com.xuncorp.spw.workshop.api` 的公开面；`compileOnly` 的依赖在运行时由宿主提供。
2. **回调里绝不抛异常**：宿主的回调线程上抛出异常会污染宿主。所有扩展点实现统一包 `try/catch` 并写日志。
3. **零第三方运行时依赖**（除 kotlin-stdlib）。宿主是 jlink 裁剪运行时，许多模块（例如 `java.net.http`）并不存在 ——
   联网只能用 `java.net.URL` + `HttpURLConnection`。
4. **配置项必须同时改三处**：`plugin/src/main/resources/preference_config.json`、
   `ConfigKeys.ALL`、以及需要时 `README` 的配置表。`selftest` 里有一条检查会比对前两者，漏了会失败。
5. **文案面向普通用户**：标题写结论、不写实现术语；示例用通用地址（`192.168.1.100`、`D:\…`），
   **不要把自己的内网地址/机器路径写进示例或默认值**。
6. **改了报告页要多跑一步**：页面源在 `web/index.html`，部署/内置用的是构建产物
   （`web/tools/build-report-page.py` 会把二维码内联进去），并且要同步 `plugin/src/main/resources/web/report.html`
   再升插件版本号 —— 见 `plugin/docs/RELEASE.md`。
7. **不要提交个人数据**：`sessions.jsonl`、`listenstats.json`、`report-data.json`、含真实听歌记录的示例
   一律不进仓库（`.gitignore` 已覆盖大部分，提交前请再确认）。

## 提交与 PR

- 提交信息用约定式前缀：`feat:` `fix:` `docs:` `refactor:` `test:` `chore:`；一句话说清"为什么"。
- 一个 PR 只做一件事。协议/数据结构有变化时，必须同时更新 `plugin/DESIGN.md` 与 `CHANGELOG.md`。
- 版本号规则见 `plugin/docs/RELEASE.md`（0.x 期间：修 bug 递增第三位、加功能递增第二位；1.x 之后语义化）。

## 报告问题

用仓库的 Issue 模板（bug 报告请附 `listenstats.log`，**先自行删掉令牌与个人路径**）。
安全问题请走 `SECURITY.md` 里的私下渠道，不要开公开 Issue。
