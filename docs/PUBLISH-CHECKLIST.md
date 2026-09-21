# 发布到 GitHub 前的检查清单

当前状态：**仓库已在本地初始化并提交，但尚未推送到 GitHub**（用户 2026-09-22 的选择）。
真要公开时，按这份清单过一遍 —— 尤其是**脱敏**，仓库里有大量只属于你自己的内网信息。

## 1. 许可证（已就绪）

- [x] `LICENSE`（Apache-2.0 全文）
- [x] `NOTICE`（本项目 + kotlin-stdlib / PF4J / spw-workshop-api 的声明）
- [x] 插件分发包内随附 `LICENSE` + `NOTICE`（构建时有校验，缺文件会直接构建失败）
- [ ] 若你要换许可证（MIT / GPL）：换 `LICENSE`、改 `NOTICE` 首段、更新根 `README.md` 与
      `plugin/README.md` 里的许可证字样，并同步 `plugin/build.gradle.kts` 的说明注释

## 2. 脱敏（**必做**，否则会把内网拓扑公开）

公开前把下列**只属于你环境**的信息替换成占位符或删掉：

| 位置 | 内容 | 建议 |
|---|---|---|
| 根 `README.md`、`plugin/README.md`、`plugin/docs/*` | `192.168.0.104`、`\\192.168.0.104\backup\spw`、`/vol2/1000/docker/...`、`/vol3/1000/...` | 换成 `192.168.1.10` / `\\NAS\share\...` / `/path/to/...`，或整段改成"示例" |
| `docs/project/**`（state.json / decisions.md / PROJECT.md / todos.json） | 决策记录里含你的内网路径、端口、事件细节 | 公开版建议**整个目录不提交**，或只保留去掉环境细节的摘要 |
| `receiver/README.md` | 部署路径与容器名（含你的卷路径） | 保留结构说明，路径改为示例 |
| `docs/spw-workshop-api-study.md` | 引用你的实测日志片段 | 检查有无用户名（例如 `C:\Users\<你>`）|
| `plugin/docs/HOST-NOTES.md`、`TESTING.md` | 真机实测记录 | 同上，检查用户名与内网地址 |
| `docs/diagrams/*.html` | 图上写了你的部署地址与目录 | 生成一张"通用版"用于公开 |

快速自查：

```bash
grep -rn "192\.168\.0\.104\|/vol2/1000/docker\|/vol3/1000\|hotboy\|KEYqiang" \
     --include="*.md" --include="*.json" --include="*.html" --include="*.kt" . | grep -v "^./internal/" | head -40
```

## 3. 个人数据（已排除，提交前再确认）

- [x] `.gitignore` 覆盖 `sessions.jsonl` / `listenstats.json` / `report-data.json` / `*.jsonl` / `*.zip` / `tmp/`
- [x] 设计变体（含真实听歌记录的 `data.json`）已移到 `internal/` 并在 `.gitignore` 中
- [ ] `git status --short` 再过一眼，确认没有听歌记录、日志、令牌

## 4. 仓库设置（推送时）

- [ ] 仓库名 `spw-listenstats`，可见性按需（建议先私有跑一次 CI）
- [ ] 描述与 topics：`salt-player` `spw` `workshop` `mod` `kotlin` `statistics`
- [ ] 确认 GitHub Actions 通过（工作流会跑 `./gradlew selftest` + 打包）
- [ ] 若发布了 Release：把 `release.sh` 产出的 zip 传上去（本仓库目前用自建快速通道，两者可并行）
- [ ] 检查 `plugin/build.gradle.kts` 里的 `Plugin-Open-Source-Url` 指向**本仓库**（已是 `spw-listenstats`）

## 5. 推送命令（确认上面都过了再执行）

```bash
git remote add origin git@github.com:<你的账号>/spw-listenstats.git
git push -u origin main
```
