## 这个 PR 做了什么

<!-- 一句话说清"为什么"，而不只是"改了什么" -->

## 类型

- [ ] 修 bug（`fix:`）
- [ ] 新功能（`feat:`）
- [ ] 文档 / 注释（`docs:`）
- [ ] 重构 / 清理（`refactor:` `chore:`）

## 自测（必需）

- [ ] `cd plugin && ./gradlew selftest` 全绿 —— 粘贴输出：`---- selftest: N 项检查 ---- 全部通过 ✅`
- [ ] 涉及统计引擎 / 存储 / 上报 / 页面模板的改动，已补对应的检查项

## 硬约束自查

- [ ] 没有引用宿主内部类、没有反射访问私有实现
- [ ] 扩展点回调里没有向外抛异常（统一 try/catch + 写日志）
- [ ] 没有新增第三方运行时依赖（联网只用 `HttpURLConnection`）
- [ ] 若改了配置项：`preference_config.json`、`ConfigKeys.ALL`、README 配置表三处都改了
- [ ] 若改了报告页：已跑页面构建脚本 + 同步插件内置模板 + 升插件版本号
- [ ] 没有提交个人数据（`sessions.jsonl` / `listenstats.json` / `report-data.json` / 含真实听歌记录的示例）
- [ ] 文案面向普通用户，示例用通用地址（不含维护者自己的内网地址）

## 其他

<!-- 截图、日志（已脱敏）、需要 reviewer 特别注意的地方 -->
