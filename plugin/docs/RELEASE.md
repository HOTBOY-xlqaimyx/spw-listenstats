# 发版流程

## 版本规则

- **1.x 起按语义化版本**：不兼容的协议/数据结构变更 → 主版本；向下兼容的新功能 → 次版本；修 bug 与文案 → 修订号。
- 协议字段只做**增量**（服务端读到自己不认识的字段必须忽略），并在 `plugin/DESIGN.md` 记录。
- 页面、接收端各自独立版本号，不与插件强制同步；但三者版本表在本文件维护。


- **未正式上线期间停留在 `0.x`**；正式上线才升 `1.0.0`
- `0.x` 阶段：修 bug 递增第三位（`0.2.0` → `0.2.1`），新增功能递增第二位（`0.2.x` → `0.3.0`）
- **任何修改都递增版本号**（用户明确要求）
- 文件名带版本：`ListenStats-<version>.zip`

## 一条命令

```bash
bash /vol2/1000/docker/spwbuild/release.sh            # 构建 + selftest + 发布到快速通道
bash /vol2/1000/docker/spwbuild/release.sh 0.2.2      # 指定版本
bash /vol2/1000/docker/spwbuild/release.sh --with-hostsim   # 需要交叉验证时再加跑假宿主
```

`release.sh` = `docker compose run --rm builder`（= 编译 + `selftest` + 打包）+ `publish.sh`（发布）。

## 快速下载通道（每次发版必须同步并交付）

| 通道 | 地址模板 |
|---|---|
| HTTP（最快） | `http://192.168.0.104:8123/downloads/ListenStats-<ver>.zip` |
| SMB | `\\192.168.0.104\backup\spw\ListenStats-<ver>.zip` |
| DSH 工作区 | `/vol2/1000/docker/dsh/workspace/spw/ListenStats-<ver>.zip` |

- `publish.sh` 会自动**删掉通道里的旧版本**（历史产物留在构建容器的 `build/dist/`）
- 交付用户时必须附上：**两条链接 + sha256 + 字节数**

## 阶段 2 组件的版本（与插件版本分开记）

| 组件 | 版本 | 位置 |
|---|---|---|
| 插件 zip | `1.0.0` | 快速通道 `ListenStats-1.0.0.zip` |
| 接收端 | `spw-receiver 0.2.2` | `/vol2/1000/docker/spw-receiver/receiver.py` |
| 报告页 | `v0.4.1`（变体 B + 夜间模式 + 时间范围/新鲜度/搜索/二维码/最近播放 + 手动/自动刷新）；**部署前需用 `web/tools/build-report-page.py` 生成内联二维码** | `/vol2/1000/docker/staticweb/web/spw-report/index.html` |

改这三者中的任何一个都要**递增对应版本号**，并在 [../../receiver/README.md](../../receiver/README.md)
或本目录 README 里同步；插件 zip 之外的组件不进快速下载通道。

## 改了报告页就要多跑一步

报告页源文件在 `web/index.html`，**线上页必须用构建脚本生成**（它把二维码内联进去）：

```bash
# 宿主上（venv 里装了 python-qrcode）
/vol2/1000/docker/spwbuild/pyenv/bin/python \
  web/tools/build-report-page.py \
  web/index.html \
  /vol2/1000/docker/staticweb/web/spw-report/index.html \
  http://192.168.0.104:8123/spw-report/
```

同时把源文件复制成插件内置模板（本地报告用的就是它）：`cp web/index.html plugin/src/main/resources/web/report.html`，
然后**升插件版本号**（模板进了包，产物就变了）。

## 发版清单

1. 改 `build.gradle.kts` 的 `version`（以及 hostsim / selftest 里的版本断言）
2. 更新 `README.md`（顶部版本号、安装说明里的文件名、「快速下载通道」章节）
3. 在 `CHANGELOG.md` 写本版条目
4. 跑 `release.sh`，确认 `selftest` 全绿
5. 校验产物：`Plugin-Version` 正确、新增内容确实进包（必要时 `unzip -p` 抽查）
6. **把通道链接与哈希交付用户**
7. 更新项目状态：`docs/project/state.json`（版本/产物/通道）与 `decisions.md`（如有新决策）

## 回滚

- 安装层面：删掉插件根目录里的 `ListenStats-*` 目录与 zip，放进旧版本即可（`enabled.txt` 保留）
- 数据层面：`listenstats.json` / `report-queue.json` 向后兼容（`schema: 1`），删掉即从零开始
