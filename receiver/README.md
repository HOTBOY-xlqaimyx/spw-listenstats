# SPW 听歌统计 · 接收端与报告页（阶段 2）

一套最小、只用 Python 标准库的服务：收插件上报 → 落盘 → 聚合成报告数据 → 交给静态站渲染。

| 组件 | 版本 | 位置 |
|---|---|---|
| `receiver.py` | **0.2.2** | 宿主 `/srv/spw-receiver/receiver.py`，容器 `spw-receiver`（`python:3-alpine`） |
| 报告页 | **v0.4.1**（变体 B + 夜间模式 + 时间范围/新鲜度/搜索/二维码/最近播放） | 宿主 `/srv/www/web/spw-report/index.html`（部署在你的静态站上，示例端口 8123） |
| 报告数据 | 每次收到上报即重写 | 同上目录 `report-data.json`（由接收端生成） |

## 地址

| 用途 | 地址 |
|---|---|
| 插件上报地址（填进插件配置） | `http://192.168.1.10:8199/api/listen`（POST） |
| 收数摘要 | `http://192.168.1.10:8199/api/listen`（GET） |
| 聚合数据（报告页的数据源） | `http://192.168.1.10:8199/api/report`（GET，会顺手刷新静态文件） |
| 健康检查 | `http://192.168.1.10:8199/health` |
| **听歌报告页** | `http://192.168.1.10:8123/spw-report/` |

## 数据流

```
SPW 插件 --POST /api/listen--> receiver
   ├─ 原文追加到 /data/listen-YYYY-MM-DD.jsonl（含 receivedAt/remote/token 标记/UA）
   ├─ 聚合：最新一批的 summary（总量/按天/曲目聚合）+ 所有批次的 sessions 并集
   └─ 原子写入 /report/report-data.json（= 静态站目录）
报告页 --同源 fetch report-data.json--> 渲染（总时长 / 每日 / 艺人榜 / 曲目榜）
```

三个挂载（见 `docker-compose.yml`）：

| 容器内 | 宿主 | 用途 |
|---|---|---|
| `/data` | `/srv/spw-receiver/data` | 原始上报 jsonl |
| `/report` | `/srv/www/web/spw-report` | 报告页与 `report-data.json` |
| `/app/receiver.py` | `.../receiver/receiver.py`（只读） | 代码 |

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `PORT` | `8199` | 监听端口 |
| `DATA_DIR` | `/data` | 原始 jsonl 目录 |
| `REPORT_DIR` | `/report` | `report-data.json` 输出目录（静态站目录） |
| `SPW_TOKEN` | 空 | 填了才校验 `X-SPW-Stats-Token`；日志只记 `present/none`，**不打印明文** |

## 聚合口径（重要）

| 字段 | 覆盖范围 | 来源 |
|---|---|---|
| `totals.listenedSeconds` / `sessions` / `tracks`、`daily` | **插件本地全量** | 最新一批的 `summary` |
| `tracks[]`（曲目榜）、`artists[]`（艺人榜） | 插件 0.5.0+：**全量**；0.4.0 及以前：**只有上报期**（由 sessions 推导） | `summary.trackStats` 优先，缺失则回退 sessions |
| `recentSessions[]` | **只有上报期**（最多 20 条） | 所有批次 sessions 并集（按 `id` 去重） |

- `totals.unattributedSeconds`（插件 0.7.0+）：宿主未给出曲目信息的那段时长，已含在 `listenedSeconds` 里，报告页做成脚注。
- `lastBatchSummaryOnly`：最新一批是不是**纯摘要补发**（插件 0.6.0+ 在没有新会话时也会推最新摘要）。
- `trackSource` 字段写明用了哪条路径（`trackStats` / `sessions`），报告页据此显示「数据不完整」提示。
- 艺人榜按 `/`、`、`、`;` 拆分多艺人（**不拆 `&`**，否则 "Simon & Garfunkel" 会被劈开）；多人曲目给每位艺人各记全额，故合计可能大于总时长。
- `artists` 的 `name` 取 `artist`，为空时退回 `albumArtist`。

## 部署 / 更新

```bash
# 代码在本仓库的 receiver/ 目录，复制到部署位置再重建：
sudo cp <repo>/receiver/receiver.py     /srv/spw-receiver/
sudo cp <repo>/receiver/docker-compose.yml /srv/spw-receiver/
cd /srv/spw-receiver && sudo docker compose up -d --force-recreate
sudo docker logs --tail 20 spw-receiver
```

报告页：

```bash
sudo cp <repo>/web/index.html \
        /srv/www/web/spw-report/index.html
sudo chmod 644 /srv/www/web/spw-report/index.html
```

## 排障

| 现象 | 检查 |
|---|---|
| 报告页「还没有收到任何上报」 | `curl http://192.168.1.10:8199/api/listen` 看批次数；插件配置里的地址/开关/令牌 |
| 曲目榜只有最近几首 | 插件还是 0.4.0：升级到 0.5.0 后重开 SPW，会自动带上全量 `trackStats` |
| 页面报「读取数据失败」 | `report-data.json` 是否存在于 web 目录、权限是否 644、接收端日志是否「报告已刷新」 |
| 数据不更新 | 接收端容器是否在跑；挂载的 web 目录是否是静态站正在托管的那个 |

> 权限坑：fnOS 挂载卷上新建文件权限会退化成 `000`，接收端写入后显式 `chmod 644`；目录 `755`。
