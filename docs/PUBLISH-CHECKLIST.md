# 发布到 GitHub 前的检查清单

当前状态：**结构、许可证、社区文件、脱敏都已完成；仓库已在本地提交，尚未推送。**
真要公开时，把下面「推送前」几项过一遍即可。

## 推送前

- [ ] `git status --short` 干净；`git log --oneline` 是你想要的提交历史
- [ ] 仓库名 `spw-listenstats`，可见性按需（建议**先私有**跑一次 CI 再转公开）
- [ ] 描述与 topics：`salt-player` `spw` `workshop` `mod` `kotlin` `statistics`
- [ ] GitHub Actions 通过：工作流跑 `./gradlew selftest` 并产出 zip 工件
- [ ] 若发布 Release：上传 `plugin/build/dist/ListenStats-<版本>.zip`（自建分发通道可并行使用）
- [ ] `plugin/build.gradle.kts` 的 `Plugin-Open-Source-Url` 指向本仓库（已指向）
- [ ] 最后确认一次**没有个人数据**：`git ls-files | grep -E "\.jsonl$|listenstats\.json|report-data\.json|\.zip$"` 应为空

## 脱敏（已于 2026-09-22 完成）

做过的替换（**新代码/新文档请沿用右侧写法**，别把真实值写回来）：

| 原来（作者环境） | 现在（通用写法） |
|---|---|
| `192.168.0.104` | `192.168.1.10`（示例网段） |
| `\\192.168.0.104\backup\spw` | `\\NAS\share\spw` |
| `/vol2/1000/docker/...` | `/srv/docker/...`（示例路径） |
| `/vol3/1000/backup` | `/srv/backup` |
| `/workspace/spw`、`/vol2/1000/docker/dsh/workspace/spw` | `<repo>`（仓库根） |
| `C:\Users\hotboy` | `C:\Users\<user>` |
| `DSH 工作区` | `开发工作区` |
| 容器名 `staticweb` / 站点目录 `ds5db` | `静态站容器` / `报告页目录` |
| `KEYqiang`（NAS 用户名 / 旧 mod provider） | `HOTBOY-xlqaimyx`（GitHub 账号） |
| 私有发布脚本路径（`release.sh` / `publish.sh` / 下载索引） | 描述为「作者私有运维，不在本仓库内」 |

另外：

- **`internal/project/**`（决策日志、待办、状态快照）已移出版本管理** —— 里面含大量环境细节与内部过程；
  如果你想公开这些"为什么这么决定"的记录，请先在 `internal/project/` 里脱敏，再把净化版放回 `docs/`。
- **含真实听歌记录的设计变体**（`data.json`）已移到 `internal/design-variants/` 并在 `.gitignore` 中。

再次自查（用**模式**而不是真实值）：

```bash
grep -rnE "192\.168\.[0-9]+\.[0-9]+\b|/vol[0-9]/|/workspace/|Users\\\\[A-Za-z]+|ds5db" \
     --include="*.md" --include="*.json" --include="*.kt" --include="*.html" --include="*.yml" . \
  | grep -v "^./internal/" | grep -v "192.168.1.10" | head -20
```

## 推送命令（确认上面都过了再执行）

```bash
git remote add origin git@github.com:HOTBOY-xlqaimyx/spw-listenstats.git
git push -u origin main
```
