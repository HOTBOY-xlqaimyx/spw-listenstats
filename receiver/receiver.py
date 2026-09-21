#!/usr/bin/env python3
"""SPW 听歌统计接收端（spw-receiver 0.2.0）

- POST /api/listen   接收插件上报（spw.listenstats.v1），原文追加到 <数据目录>/listen-YYYY-MM-DD.jsonl
- GET  /api/listen   返回收数摘要（批次数 / 会话数 / 最近接收时间），便于快速核对
- GET  /api/report   返回**聚合后的报告数据**（报告页用的就是它），并会顺手刷新静态文件
- GET  /health       健康检查

聚合结果会原子写入 <REPORT_DIR>/report-data.json —— 报告页由静态站托管，
同源 fetch 这个文件即可，不需要跨域、不需要改 nginx。

只依赖标准库。令牌：设置环境变量 SPW_TOKEN 才校验 X-SPW-Stats-Token；不设置则接受任意请求。
日志里**不打印令牌明文**（只记 present/none）。
"""
import datetime
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DATA_DIR = os.environ.get("DATA_DIR", "/data")
REPORT_DIR = os.environ.get("REPORT_DIR", "/report")
REPORT_NAME = "report-data.json"
PORT = int(os.environ.get("PORT", "8199"))
TOKEN = os.environ.get("SPW_TOKEN", "").strip()
MAX_BODY = 8 * 1024 * 1024

VERSION = "0.2.2"
RECENT_SESSIONS = 20
# 艺人拆分符：宿主给的 artist 常见 "A/B"、"A、B"；刻意**不拆 `&`**（"Simon & Garfunkel" 是一个人）
ARTIST_SEPS = ("/", "、", ";", "；")


def now_iso():
    return datetime.datetime.now().astimezone().isoformat(timespec="seconds")


def _log_files():
    if not os.path.isdir(DATA_DIR):
        return []
    return [os.path.join(DATA_DIR, n) for n in sorted(os.listdir(DATA_DIR))
            if n.startswith("listen-") and n.endswith(".jsonl")]


def _read_records():
    """按文件顺序读出所有上报记录（每条 = 一次 POST）。"""
    for path in _log_files():
        try:
            with open(path, encoding="utf-8") as fh:
                for line in fh:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        yield json.loads(line)
                    except Exception:
                        continue
        except Exception:
            continue


def _ms_to_iso(ms):
    if not ms:
        return None
    try:
        return datetime.datetime.fromtimestamp(ms / 1000.0).astimezone().isoformat(timespec="seconds")
    except Exception:
        return None


def _split_artists(value):
    if not value:
        return []
    parts = [value]
    for sep in ARTIST_SEPS:
        nxt = []
        for part in parts:
            nxt.extend(part.split(sep))
        parts = nxt
    return [p.strip() for p in parts if p.strip()]


def build_report():
    """把「最新一批的 summary」+「所有批次的会话并集」聚合成报告页数据。"""
    records = list(_read_records())
    files = [os.path.basename(p) for p in _log_files()]
    report = {
        "generatedAt": now_iso(),
        "version": VERSION,
        "source": "SPW 创意工坊插件「听歌统计」上报数据（spw.listenstats.v1）",
        "files": files,
        "batches": len(records),
        "pluginVersion": None,
        "spwVersion": None,
        "channel": None,
        "lastReceivedAt": records[-1].get("receivedAt") if records else None,
        "trackSource": None,
        "range": None,
        "totals": {"listenedSeconds": 0, "sessions": 0, "tracks": 0},
        "daily": [],
        "artists": [],
        "tracks": [],
        "recentSessions": [],
    }
    if not records:
        return report

    # 最新状态 = sentAt 最大的那一批（不是「文件里最后一行」，防止乱序写入误判）
    latest = max(records, key=lambda r: (r.get("payload") or {}).get("sentAt") or 0)
    payload = latest.get("payload") or {}
    summary = payload.get("summary") or {}
    report["pluginVersion"] = payload.get("pluginVersion")
    report["spwVersion"] = payload.get("spwVersion")
    report["channel"] = payload.get("channel")
    # 最新一批是不是「纯摘要补发」（插件 0.6.0+ 在没有新会话时也会推一份最新摘要）
    report["lastBatchSummaryOnly"] = bool(payload.get("summaryOnly")) or not (payload.get("sessions") or [])

    # ---- 会话并集（跨批次按 id 去重，后到的覆盖先到的）----
    sessions = {}
    for rec in records:
        for s in (rec.get("payload") or {}).get("sessions") or []:
            key = s.get("id") or "%s@%s" % (s.get("path"), s.get("startedAt"))
            sessions[key] = s

    # ---- 曲目：优先用插件的全量逐曲目聚合（0.5.0+），否则退回「上报期会话推导」----
    raw_tracks = summary.get("trackStats") or []
    if raw_tracks:
        report["trackSource"] = "trackStats"
        tracks = [{
            "title": t.get("title") or "未知曲目",
            "artist": t.get("artist") or "",
            "album": t.get("album") or "",
            "albumArtist": t.get("albumArtist") or "",
            "path": t.get("path") or "",
            "seconds": int(t.get("listenedSeconds") or 0),
            "sessions": int(t.get("sessions") or 0),
            "firstPlayedAt": t.get("firstPlayedAt") or 0,
            "lastPlayedAt": t.get("lastPlayedAt") or 0,
        } for t in raw_tracks]
    else:
        report["trackSource"] = "sessions"
        merged = {}
        for s in sessions.values():
            key = s.get("path") or "%s|%s" % (s.get("title"), s.get("artist"))
            item = merged.setdefault(key, {
                "title": s.get("title") or "未知曲目",
                "artist": s.get("artist") or "",
                "album": s.get("album") or "",
                "albumArtist": s.get("albumArtist") or "",
                "path": s.get("path") or "",
                "seconds": 0, "sessions": 0, "firstPlayedAt": 0, "lastPlayedAt": 0,
            })
            item["seconds"] += int(s.get("listenedSeconds") or 0)
            item["sessions"] += 1
            started, ended = s.get("startedAt") or 0, s.get("endedAt") or 0
            if started and (not item["firstPlayedAt"] or started < item["firstPlayedAt"]):
                item["firstPlayedAt"] = started
            if ended > item["lastPlayedAt"]:
                item["lastPlayedAt"] = ended
        tracks = list(merged.values())
    tracks.sort(key=lambda t: (-t["seconds"], t["title"]))
    report["tracks"] = tracks

    # ---- 艺人榜：按拆分后的名字累计（多人曲目给每位艺人都记全额，故合计可能大于总时长）----
    artists = {}
    for t in tracks:
        names = _split_artists(t["artist"]) or _split_artists(t["albumArtist"]) or ["未知艺人"]
        for name in set(names):
            item = artists.setdefault(name, {"name": name, "seconds": 0, "tracks": 0, "sessions": 0})
            item["seconds"] += t["seconds"]
            item["tracks"] += 1
            item["sessions"] += t["sessions"]
    report["artists"] = sorted(artists.values(), key=lambda a: (-a["seconds"], a["name"]))

    # ---- 总量 / 按天 ----
    daily = summary.get("daily") or {}
    report["daily"] = [{"date": d, "seconds": int(daily[d] or 0)} for d in sorted(daily)]
    report["totals"] = {
        "listenedSeconds": int(summary.get("listenedSeconds") or 0),
        "sessions": int(summary.get("sessions") or 0),
        "tracks": int(summary.get("tracks") or len(tracks)),
        # 宿主没给出曲目信息的那段时长（已含在 listenedSeconds 里，单独标出来供页面脚注）
        "unattributedSeconds": int(summary.get("unattributedSeconds") or 0),
    }
    days = [d["date"] for d in report["daily"]]
    report["range"] = {
        "from": days[0] if days else None,
        "to": days[-1] if days else None,
        "days": len(days),
        "firstPlayedAt": summary.get("firstPlayedAt") or 0,
        "lastPlayedAt": summary.get("lastPlayedAt") or 0,
        "firstPlayedAtIso": _ms_to_iso(summary.get("firstPlayedAt")),
        "lastPlayedAtIso": _ms_to_iso(summary.get("lastPlayedAt")),
    }

    # ---- 会话明细（只有「开启上报之后」的，条数通常远小于 totals.sessions）----
    recent = sorted(sessions.values(), key=lambda s: s.get("startedAt") or 0, reverse=True)
    report["recentSessions"] = [{
        "title": s.get("title") or "未知曲目",
        "artist": s.get("artist") or "",
        "album": s.get("album") or "",
        "seconds": int(s.get("listenedSeconds") or 0),
        "startedAt": s.get("startedAt") or 0,
        "endedAt": s.get("endedAt") or 0,
        "startedAtIso": _ms_to_iso(s.get("startedAt")),
        "endedReason": s.get("endedReason") or "",
    } for s in recent[:RECENT_SESSIONS]]
    report["coverage"] = {
        "sessionsReported": len(sessions),
        "sessionsTotal": report["totals"]["sessions"],
        "tracksReported": len(tracks),
        "tracksTotal": int(summary.get("trackStatsTotal") or report["totals"]["tracks"]),
        "full": report["trackSource"] == "trackStats",
    }
    return report


def _write_report():
    report = build_report()
    try:
        os.makedirs(REPORT_DIR, exist_ok=True)
        try:
            os.chmod(REPORT_DIR, 0o755)
        except Exception:
            pass
        target = os.path.join(REPORT_DIR, REPORT_NAME)
        tmp = target + ".tmp"
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump(report, fh, ensure_ascii=False, indent=1)
            fh.flush()
            os.fsync(fh.fileno())
        os.replace(tmp, target)
        # fnOS 挂载卷上新建文件权限会退化成 000，显式放权给静态站读取
        try:
            os.chmod(target, 0o644)
        except Exception:
            pass
    except Exception as exc:
        print("[%s] 写报告文件失败：%s" % (now_iso(), exc), flush=True)
    return report


class Handler(BaseHTTPRequestHandler):
    server_version = "spw-receiver/" + VERSION

    def _json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _summary(self):
        batches = 0
        sessions = 0
        last = None
        tracks = set()
        files = []
        if os.path.isdir(DATA_DIR):
            files = sorted(f for f in os.listdir(DATA_DIR)
                           if f.startswith("listen-") and f.endswith(".jsonl"))
        for name in files:
            path = os.path.join(DATA_DIR, name)
            with open(path, encoding="utf-8") as fh:
                for line in fh:
                    line = line.strip()
                    if not line:
                        continue
                    batches += 1
                    try:
                        payload = json.loads(line).get("payload") or {}
                        sessions += len(payload.get("sessions") or [])
                        for s in payload.get("sessions") or []:
                            tracks.add(s.get("title") or s.get("path") or "?")
                    except Exception:
                        pass
            last = os.path.getmtime(path)
        return {
            "ok": True,
            "version": VERSION,
            "files": files,
            "batches": batches,
            "sessions": sessions,
            "distinctTracks": len(tracks),
            "lastReceivedAt": (datetime.datetime.fromtimestamp(last)
                               .astimezone().isoformat(timespec="seconds")) if last else None,
            "tokenRequired": bool(TOKEN),
            "dataDir": DATA_DIR,
            "reportDir": REPORT_DIR,
        }

    def do_GET(self):
        if self.path in ("/api/listen", "/api/listen/summary"):
            self._json(200, self._summary())
        elif self.path.startswith("/api/report"):
            # 顺手刷新静态文件：即使报告页/文件被删过，一次访问就恢复
            self._json(200, _write_report())
        elif self.path == "/health":
            self._json(200, {"ok": True, "version": VERSION})
        else:
            self._json(404, {"ok": False, "error": "not found"})

    def do_POST(self):
        if self.path != "/api/listen":
            self._json(404, {"ok": False, "error": "not found"})
            return
        token_header = (self.headers.get("X-SPW-Stats-Token") or "").strip()
        if TOKEN and token_header != TOKEN:
            self._json(401, {"ok": False, "error": "bad token"})
            return
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > MAX_BODY:
            self._json(400, {"ok": False, "error": "bad content-length"})
            return
        raw = self.rfile.read(length)
        try:
            payload = json.loads(raw.decode("utf-8"))
        except Exception as exc:
            self._json(400, {"ok": False, "error": "bad json: %s" % exc})
            return

        os.makedirs(DATA_DIR, exist_ok=True)
        day = datetime.datetime.now().strftime("%Y-%m-%d")
        path = os.path.join(DATA_DIR, "listen-%s.jsonl" % day)
        record = {
            "receivedAt": now_iso(),
            "remote": self.client_address[0],
            "token": "present" if token_header else "none",
            "userAgent": self.headers.get("User-Agent", ""),
            "contentLength": length,
            "payload": payload,
        }
        with open(path, "a", encoding="utf-8") as fh:
            fh.write(json.dumps(record, ensure_ascii=False) + "\n")
            fh.flush()
            os.fsync(fh.fileno())
        try:
            os.chmod(path, 0o644)
        except Exception:
            pass

        summary = payload.get("summary") or {}
        sessions = len(payload.get("sessions") or [])
        track_stats = len(summary.get("trackStats") or [])
        print("[%s] 收到上报 schema=%s sessions=%d trackStats=%d summaryOnly=%s 来自 %s（UA=%s）"
              % (now_iso(), payload.get("schema"), sessions, track_stats,
                 payload.get("summaryOnly", sessions == 0),
                 self.client_address[0], self.headers.get("User-Agent", "")), flush=True)

        report = _write_report()
        print("[%s] 报告已刷新：批次数=%d 总时长=%ss 曲目=%d 数据源=%s"
              % (now_iso(), report["batches"], report["totals"]["listenedSeconds"],
                 len(report["tracks"]), report["trackSource"]), flush=True)
        self._json(200, {"ok": True, "received": 1, "sessions": sessions,
                         "reportTracks": len(report["tracks"])})

    def log_message(self, fmt, *args):
        print("[%s] %s %s" % (now_iso(), self.address_string(), fmt % args), flush=True)


if __name__ == "__main__":
    os.makedirs(DATA_DIR, exist_ok=True)
    print("spw-receiver %s 启动：端口 %d，数据目录 %s，报告目录 %s，令牌校验 %s"
          % (VERSION, PORT, DATA_DIR, REPORT_DIR, "开" if TOKEN else "关"), flush=True)
    _write_report()
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
