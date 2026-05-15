#!/usr/bin/env python3
"""
NasiTracker Server
位置追踪数据接收、存储、查询、导出服务
运行: python3 server.py
"""

import sqlite3
import hashlib
import hmac
import json
import csv
import io
import os
import time
import threading
from datetime import datetime, timezone, timedelta
from functools import wraps
from flask import Flask, request, jsonify, render_template_string, session, redirect, url_for, Response

# ─── 配置 ────────────────────────────────────────────────────────────────────
API_KEY        = "NasiTracker2026SecretKey#X9mZ"   # 与App保持一致
WEB_USERNAME   = "admin"
WEB_PASSWORD   = "NasiAdmin2026!"                  # Web登录密码，请修改
SECRET_KEY     = "NasiWebSession#Secret2026"       # Flask session 密钥
DB_PATH        = "tracker.db"
PORT           = 55000
HOST           = "0.0.0.0"

TZ_CN = timezone(timedelta(hours=8))  # 北京时间

app = Flask(__name__)
app.secret_key = SECRET_KEY

# ─── 数据库初始化 ─────────────────────────────────────────────────────────────
def get_db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn

def init_db():
    with get_db() as conn:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS locations (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                latitude  REAL    NOT NULL,
                longitude REAL    NOT NULL,
                accuracy  REAL    NOT NULL DEFAULT 0,
                timestamp INTEGER NOT NULL,
                received  INTEGER NOT NULL
            )
        """)
        conn.execute("CREATE INDEX IF NOT EXISTS idx_timestamp ON locations(timestamp)")
        # MQTT 配置表
        conn.execute("""
            CREATE TABLE IF NOT EXISTS mqtt_config (
                key   TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
        """)
        # 写入默认配置（若不存在）
        defaults = {
            "broker":   "xy.nasi.cn",
            "port":     "1883",
            "topic":    "SM09aZ09aZ/GPSTrackHost/info",
            "username": "",
            "password": "",
            "client_id": "NasiTrackerServer"
        }
        for k, v in defaults.items():
            conn.execute(
                "INSERT OR IGNORE INTO mqtt_config(key,value) VALUES(?,?)", (k, v)
            )
    print(f"[DB] Initialized: {DB_PATH}")

def get_mqtt_config():
    with get_db() as conn:
        rows = conn.execute("SELECT key,value FROM mqtt_config").fetchall()
    return {r["key"]: r["value"] for r in rows}

def save_mqtt_config(cfg: dict):
    with get_db() as conn:
        for k, v in cfg.items():
            conn.execute(
                "INSERT OR REPLACE INTO mqtt_config(key,value) VALUES(?,?)", (k, v)
            )

# ─── 认证装饰器 ───────────────────────────────────────────────────────────────
def require_login(f):
    @wraps(f)
    def decorated(*args, **kwargs):
        if not session.get("logged_in"):
            return redirect(url_for("login", next=request.url))
        return f(*args, **kwargs)
    return decorated

def verify_api_key(data: dict) -> bool:
    return hmac.compare_digest(data.get("api_key", ""), API_KEY)

# ─── API：接收位置上报 ────────────────────────────────────────────────────────
@app.route("/api/upload", methods=["POST"])
def api_upload():
    try:
        data = request.get_json(force=True)
    except Exception:
        return jsonify({"ok": False, "error": "invalid json"}), 400

    if not verify_api_key(data):
        return jsonify({"ok": False, "error": "unauthorized"}), 401

    try:
        lat       = float(data["latitude"])
        lng       = float(data["longitude"])
        accuracy  = float(data.get("accuracy", 0))
        timestamp = int(data["timestamp"])
    except (KeyError, ValueError) as e:
        return jsonify({"ok": False, "error": f"bad params: {e}"}), 400

    received = int(time.time() * 1000)
    with get_db() as conn:
        conn.execute(
            "INSERT INTO locations (latitude,longitude,accuracy,timestamp,received) VALUES (?,?,?,?,?)",
            (lat, lng, accuracy, timestamp, received)
        )

    print(f"[UPLOAD] lat={lat}, lng={lng}, acc={accuracy}, ts={timestamp}")
    return jsonify({"ok": True})

# ─── MQTT 配置接口 ────────────────────────────────────────────────────────────
@app.route("/api/mqtt_config", methods=["GET", "POST"])
@require_login
def api_mqtt_config():
    if request.method == "GET":
        cfg = get_mqtt_config()
        cfg.pop("password", None)   # 不回显密码
        return jsonify({"ok": True, "config": cfg})

    data = request.get_json(force=True) or {}
    allowed = ["broker", "port", "topic", "username", "password", "client_id"]
    to_save = {k: str(v) for k, v in data.items() if k in allowed}
    if to_save:
        save_mqtt_config(to_save)
    return jsonify({"ok": True})

# ─── MQTT Publish 接口 ───────────────────────────────────────────────────────
@app.route("/mqtt_pub", methods=["POST"])
@require_login
def mqtt_pub():
    """将指定消息发布到 MQTT，或将最新位置数据打包发布"""
    try:
        import paho.mqtt.publish as publish
    except ImportError:
        return jsonify({"ok": False, "error": "paho-mqtt 未安装，请运行: pip install paho-mqtt"}), 500

    cfg = get_mqtt_config()
    broker    = cfg.get("broker", "xy.nasi.cn")
    port      = int(cfg.get("port", 1883))
    topic     = cfg.get("topic", "SM09aZ09aZ/GPSTrackHost/info")
    username  = cfg.get("username", "") or None
    password  = cfg.get("password", "") or None
    client_id = cfg.get("client_id", "NasiTrackerServer")

    # 允许请求体覆盖 topic 和 payload
    body = request.get_json(force=True) or {}
    topic   = body.get("topic", topic)
    payload = body.get("payload", None)

    # 若无指定 payload，则自动取最新位置记录
    if payload is None:
        with get_db() as conn:
            row = conn.execute(
                "SELECT * FROM locations ORDER BY timestamp DESC LIMIT 1"
            ).fetchone()
        if row:
            dt = datetime.fromtimestamp(row["timestamp"] / 1000, tz=TZ_CN)
            payload = json.dumps({
                "latitude":  row["latitude"],
                "longitude": row["longitude"],
                "accuracy":  row["accuracy"],
                "time":      dt.strftime("%Y-%m-%d %H:%M:%S"),
                "timestamp": row["timestamp"]
            }, ensure_ascii=False)
        else:
            return jsonify({"ok": False, "error": "暂无位置数据"}), 400

    auth = {"username": username, "password": password} if username else None

    try:
        publish.single(
            topic,
            payload=payload,
            hostname=broker,
            port=port,
            client_id=client_id,
            auth=auth,
            keepalive=30
        )
        print(f"[MQTT] Published to {broker}:{port} topic={topic}  payload={payload[:80]}")
        return jsonify({"ok": True, "topic": topic, "payload": payload})
    except Exception as e:
        print(f"[MQTT] Error: {e}")
        return jsonify({"ok": False, "error": str(e)}), 500

# ─── Web 登录 ─────────────────────────────────────────────────────────────────
@app.route("/login", methods=["GET", "POST"])
def login():
    error = ""
    if request.method == "POST":
        u = request.form.get("username", "")
        p = request.form.get("password", "")
        if hmac.compare_digest(u, WEB_USERNAME) and hmac.compare_digest(p, WEB_PASSWORD):
            session["logged_in"] = True
            return redirect(request.args.get("next") or url_for("index"))
        error = "用户名或密码错误"
    return render_template_string(LOGIN_HTML, error=error)

@app.route("/logout")
def logout():
    session.clear()
    return redirect(url_for("login"))

# ─── Web 主页：查询与显示 ─────────────────────────────────────────────────────
@app.route("/")
@require_login
def index():
    start_str = request.args.get("start", "")
    end_str   = request.args.get("end", "")
    page      = max(1, int(request.args.get("page", 1)))
    per_page  = 50

    conditions = []
    params     = []
    if start_str:
        try:
            ts = int(datetime.strptime(start_str, "%Y-%m-%d").replace(
                tzinfo=TZ_CN).timestamp() * 1000)
            conditions.append("timestamp >= ?")
            params.append(ts)
        except ValueError:
            pass
    if end_str:
        try:
            ts = int((datetime.strptime(end_str, "%Y-%m-%d").replace(
                tzinfo=TZ_CN) + timedelta(days=1)).timestamp() * 1000)
            conditions.append("timestamp < ?")
            params.append(ts)
        except ValueError:
            pass

    where = ("WHERE " + " AND ".join(conditions)) if conditions else ""

    with get_db() as conn:
        total = conn.execute(f"SELECT COUNT(*) FROM locations {where}", params).fetchone()[0]
        offset = (page - 1) * per_page
        rows = conn.execute(
            f"SELECT * FROM locations {where} ORDER BY timestamp DESC LIMIT ? OFFSET ?",
            params + [per_page, offset]
        ).fetchall()
        # 统计数据
        stats = conn.execute("""
            SELECT COUNT(*) as cnt,
                   MIN(timestamp) as first_ts,
                   MAX(timestamp) as last_ts
            FROM locations
        """).fetchone()

    records = []
    for r in rows:
        dt = datetime.fromtimestamp(r["timestamp"] / 1000, tz=TZ_CN)
        records.append({
            "id":        r["id"],
            "lat":       r["latitude"],
            "lng":       r["longitude"],
            "accuracy":  r["accuracy"],
            "time_str":  dt.strftime("%Y-%m-%d %H:%M:%S"),
            "timestamp": r["timestamp"],
        })

    total_pages = max(1, (total + per_page - 1) // per_page)

    # 构造统计字符串
    stat_info = {"cnt": 0, "first": "—", "last": "—"}
    if stats and stats["cnt"] > 0:
        stat_info["cnt"] = stats["cnt"]
        stat_info["first"] = datetime.fromtimestamp(stats["first_ts"] / 1000, tz=TZ_CN).strftime("%Y-%m-%d %H:%M:%S")
        stat_info["last"]  = datetime.fromtimestamp(stats["last_ts"]  / 1000, tz=TZ_CN).strftime("%Y-%m-%d %H:%M:%S")

    mqtt_cfg = get_mqtt_config()
    mqtt_cfg.pop("password", None)

    return render_template_string(
        INDEX_HTML,
        records=records, total=total,
        page=page, total_pages=total_pages,
        start_str=start_str, end_str=end_str,
        stat_info=stat_info,
        mqtt_cfg=mqtt_cfg
    )

# ─── 数据导出 ─────────────────────────────────────────────────────────────────
@app.route("/export")
@require_login
def export():
    fmt       = request.args.get("fmt", "csv")          # csv / gaode / baidu / google
    start_str = request.args.get("start", "")
    end_str   = request.args.get("end", "")

    conditions, params = [], []
    if start_str:
        try:
            ts = int(datetime.strptime(start_str, "%Y-%m-%d").replace(
                tzinfo=TZ_CN).timestamp() * 1000)
            conditions.append("timestamp >= ?"); params.append(ts)
        except ValueError: pass
    if end_str:
        try:
            ts = int((datetime.strptime(end_str, "%Y-%m-%d").replace(
                tzinfo=TZ_CN) + timedelta(days=1)).timestamp() * 1000)
            conditions.append("timestamp < ?"); params.append(ts)
        except ValueError: pass

    where = ("WHERE " + " AND ".join(conditions)) if conditions else ""
    with get_db() as conn:
        rows = conn.execute(
            f"SELECT * FROM locations {where} ORDER BY timestamp ASC", params
        ).fetchall()

    if fmt == "csv":
        return export_csv(rows)
    elif fmt == "gaode":
        return export_map_html(rows, "gaode")
    elif fmt == "baidu":
        return export_map_html(rows, "baidu")
    elif fmt == "google":
        return export_map_html(rows, "google")
    else:
        return jsonify({"error": "unknown format"}), 400


def export_csv(rows):
    buf = io.StringIO()
    writer = csv.writer(buf)
    writer.writerow(["ID", "纬度", "经度", "精度(m)", "时间(北京)", "时间戳(ms)"])
    for r in rows:
        dt = datetime.fromtimestamp(r["timestamp"] / 1000, tz=TZ_CN).strftime("%Y-%m-%d %H:%M:%S")
        writer.writerow([r["id"], r["latitude"], r["longitude"], r["accuracy"], dt, r["timestamp"]])
    buf.seek(0)
    filename = f"tracker_{datetime.now(TZ_CN).strftime('%Y%m%d_%H%M%S')}.csv"
    return Response(
        buf.getvalue().encode("utf-8-sig"),
        mimetype="text/csv",
        headers={"Content-Disposition": f"attachment; filename={filename}"}
    )


def export_map_html(rows, map_type: str):
    """生成可在对应地图上显示轨迹的 HTML 文件"""
    points = [[r["latitude"], r["longitude"]] for r in rows]
    points_json = json.dumps(points)

    if map_type == "gaode":
        html = GAODE_MAP_HTML.replace("__POINTS__", points_json)
    elif map_type == "baidu":
        html = BAIDU_MAP_HTML.replace("__POINTS__", points_json)
    elif map_type == "google":
        html = GOOGLE_MAP_HTML.replace("__POINTS__", points_json)
    else:
        html = ""

    filename = f"track_{map_type}_{datetime.now(TZ_CN).strftime('%Y%m%d_%H%M%S')}.html"
    return Response(
        html.encode("utf-8"),
        mimetype="text/html",
        headers={"Content-Disposition": f"attachment; filename={filename}"}
    )

# ─── HTML 模板 ─────────────────────────────────────────────────────────────────

LOGIN_HTML = """
<!DOCTYPE html>
<html lang="zh-CN">
<head><meta charset="utf-8"><title>Tracker 登录</title>
<style>
body{font-family:sans-serif;background:#f0f2f5;display:flex;justify-content:center;align-items:center;height:100vh;margin:0}
.box{background:#fff;padding:40px;border-radius:10px;box-shadow:0 2px 12px rgba(0,0,0,.1);width:320px}
h2{text-align:center;color:#1976D2;margin-bottom:24px}
input{width:100%;box-sizing:border-box;padding:10px;margin-bottom:14px;border:1px solid #ddd;border-radius:6px;font-size:14px}
button{width:100%;padding:11px;background:#1976D2;color:#fff;border:none;border-radius:6px;font-size:15px;cursor:pointer}
button:hover{background:#1565C0}
.error{color:#e53935;font-size:13px;margin-bottom:12px;text-align:center}
</style></head>
<body><div class="box">
<h2>📍 NasiTracker</h2>
{% if error %}<div class="error">{{ error }}</div>{% endif %}
<form method="post">
<input name="username" placeholder="用户名" autofocus>
<input name="password" type="password" placeholder="密码">
<button type="submit">登录</button>
</form>
</div></body></html>
"""

INDEX_HTML = """
<!DOCTYPE html>
<html lang="zh-CN">
<head><meta charset="utf-8"><title>位置追踪数据</title>
<style>
*{box-sizing:border-box}
body{font-family:sans-serif;background:#f5f5f5;margin:0;padding:0}
.header{background:#1976D2;color:#fff;padding:14px 20px;display:flex;justify-content:space-between;align-items:center}
.header a{color:#fff;text-decoration:none;font-size:13px}
.container{max-width:1100px;margin:20px auto;padding:0 16px}
.card{background:#fff;border-radius:8px;padding:16px;margin-bottom:16px;box-shadow:0 1px 4px rgba(0,0,0,.06)}
.card h3{margin:0 0 12px;font-size:15px;color:#1976D2;border-bottom:1px solid #eee;padding-bottom:8px}
.filter-bar{display:flex;flex-wrap:wrap;gap:10px;align-items:center}
.filter-bar input{padding:8px;border:1px solid #ddd;border-radius:5px;font-size:13px}
.filter-bar button,.btn{padding:8px 16px;background:#1976D2;color:#fff;border:none;border-radius:5px;cursor:pointer;font-size:13px}
.btn:hover,.filter-bar button:hover{opacity:.88}
.btn-grey{background:#757575}
.export-bar{display:flex;gap:8px;flex-wrap:wrap}
.export-bar a{padding:7px 14px;border-radius:5px;font-size:13px;text-decoration:none;color:#fff}
.btn-csv{background:#4CAF50}.btn-gaode{background:#FF9800}.btn-baidu{background:#1565C0}.btn-google{background:#E53935}
table{width:100%;border-collapse:collapse;background:#fff;border-radius:8px;overflow:hidden;box-shadow:0 1px 4px rgba(0,0,0,.08)}
th{background:#1976D2;color:#fff;padding:10px 12px;text-align:left;font-size:13px}
td{padding:9px 12px;font-size:13px;border-bottom:1px solid #f0f0f0}
tr:last-child td{border-bottom:none}
tr:hover td{background:#f9f9f9}
.page-bar{margin-top:16px;display:flex;gap:6px;align-items:center;flex-wrap:wrap}
.page-bar a{padding:5px 10px;background:#fff;border:1px solid #ddd;border-radius:4px;text-decoration:none;color:#333;font-size:13px}
.page-bar a.active{background:#1976D2;color:#fff;border-color:#1976D2}
.total{font-size:13px;color:#666;margin-top:8px}
.stat-grid{display:flex;gap:20px;flex-wrap:wrap}
.stat-item{flex:1;min-width:160px;background:#f0f7ff;border-radius:6px;padding:10px 14px}
.stat-item .label{font-size:12px;color:#888}
.stat-item .value{font-size:18px;font-weight:bold;color:#1976D2;margin-top:4px}
.mqtt-grid{display:flex;gap:10px;flex-wrap:wrap;align-items:flex-end}
.mqtt-grid label{font-size:12px;color:#555;display:block;margin-bottom:4px}
.mqtt-grid input{padding:7px 9px;border:1px solid #ddd;border-radius:5px;font-size:13px;width:100%}
.mqtt-field{flex:1;min-width:120px}
.mqtt-field-lg{flex:2;min-width:200px}
#mqttStatus{font-size:13px;margin-top:8px;min-height:20px}
.green{color:#2e7d32}.red{color:#c62828}.blue{color:#1565C0}
.pub-row{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-top:10px}
.pub-row textarea{flex:3;min-width:200px;padding:8px;border:1px solid #ddd;border-radius:5px;font-size:13px;resize:vertical;min-height:60px}
</style>
</head>
<body>
<div class="header">
  <span>📍 NasiTracker 数据中心</span>
  <a href="/logout">退出登录</a>
</div>
<div class="container">

  <!-- 统计卡片 -->
  <div class="card">
    <h3>📊 数据统计</h3>
    <div class="stat-grid">
      <div class="stat-item">
        <div class="label">总记录数</div>
        <div class="value">{{ stat_info.cnt }}</div>
      </div>
      <div class="stat-item">
        <div class="label">最早记录</div>
        <div class="value" style="font-size:13px;margin-top:6px">{{ stat_info.first }}</div>
      </div>
      <div class="stat-item">
        <div class="label">最新记录</div>
        <div class="value" style="font-size:13px;margin-top:6px">{{ stat_info.last }}</div>
      </div>
    </div>
  </div>

  <!-- MQTT 发布 -->
  <div class="card">
    <h3>📡 MQTT 发布 (mqtt_pub)</h3>
    <div id="mqttStatus">（点击"发布"将当前最新位置推送到 MQTT，或自定义 payload）</div>
    <div class="pub-row">
      <label style="font-size:13px;white-space:nowrap">Topic：</label>
      <input id="pubTopic" type="text" style="flex:2;min-width:200px;padding:7px 9px;border:1px solid #ddd;border-radius:5px;font-size:13px" value="{{ mqtt_cfg.topic }}">
      <label style="font-size:13px;white-space:nowrap">Payload（留空=最新位置）：</label>
      <textarea id="pubPayload" placeholder='留空自动取最新位置，或输入自定义文本/JSON'></textarea>
      <button class="btn" onclick="mqttPublish()">🚀 发布</button>
    </div>
  </div>

  <!-- MQTT 服务器设置 -->
  <div class="card">
    <h3>⚙️ MQTT 服务器设置</h3>
    <div class="mqtt-grid">
      <div class="mqtt-field-lg">
        <label>Broker 地址</label>
        <input id="mqBroker" type="text" value="{{ mqtt_cfg.broker }}">
      </div>
      <div class="mqtt-field" style="max-width:90px">
        <label>端口</label>
        <input id="mqPort" type="number" value="{{ mqtt_cfg.port }}">
      </div>
      <div class="mqtt-field-lg">
        <label>默认 Topic</label>
        <input id="mqTopic" type="text" value="{{ mqtt_cfg.topic }}">
      </div>
      <div class="mqtt-field">
        <label>用户名（可选）</label>
        <input id="mqUser" type="text" value="{{ mqtt_cfg.username }}">
      </div>
      <div class="mqtt-field">
        <label>密码（可选）</label>
        <input id="mqPass" type="password" placeholder="（留空保持不变）">
      </div>
      <div class="mqtt-field">
        <label>Client ID</label>
        <input id="mqClientId" type="text" value="{{ mqtt_cfg.client_id }}">
      </div>
    </div>
    <div style="margin-top:10px">
      <button class="btn" onclick="saveMqttConfig()">💾 保存 MQTT 设置</button>
      <span id="mqSaveStatus" style="font-size:13px;margin-left:10px"></span>
    </div>
  </div>

  <!-- 数据查询 -->
  <div class="card">
    <h3>🔍 数据查询</h3>
    <form class="filter-bar" method="get">
      <label>开始日期：</label><input type="date" name="start" value="{{ start_str }}">
      <label>结束日期：</label><input type="date" name="end" value="{{ end_str }}">
      <button type="submit">🔍 查询</button>
      <a href="/" style="padding:8px 14px;background:#757575;color:#fff;border-radius:5px;font-size:13px;text-decoration:none">重置</a>
    </form>
  </div>

  <!-- 导出 -->
  <div class="card" style="padding:12px 16px">
    <div class="export-bar">
      <a class="btn-csv" href="/export?fmt=csv&start={{ start_str }}&end={{ end_str }}">⬇ 导出 CSV</a>
      <a class="btn-gaode" href="/export?fmt=gaode&start={{ start_str }}&end={{ end_str }}">🗺 导出高德地图</a>
      <a class="btn-baidu" href="/export?fmt=baidu&start={{ start_str }}&end={{ end_str }}">🗺 导出百度地图</a>
      <a class="btn-google" href="/export?fmt=google&start={{ start_str }}&end={{ end_str }}">🗺 导出谷歌地图</a>
    </div>
  </div>

  <div class="total">共 {{ total }} 条记录，第 {{ page }}/{{ total_pages }} 页</div>

  <table>
    <tr><th>#</th><th>时间</th><th>纬度</th><th>经度</th><th>精度(m)</th></tr>
    {% for r in records %}
    <tr>
      <td>{{ r.id }}</td>
      <td>{{ r.time_str }}</td>
      <td>{{ "%.6f"|format(r.lat) }}</td>
      <td>{{ "%.6f"|format(r.lng) }}</td>
      <td>{{ "%.1f"|format(r.accuracy) }}</td>
    </tr>
    {% else %}
    <tr><td colspan="5" style="text-align:center;color:#999;padding:30px">暂无数据</td></tr>
    {% endfor %}
  </table>

  <div class="page-bar">
    {% if page > 1 %}
    <a href="?page={{ page-1 }}&start={{ start_str }}&end={{ end_str }}">◀ 上一页</a>
    {% endif %}
    {% for p in range([1, page-2]|max, [total_pages+1, page+3]|min) %}
    <a href="?page={{ p }}&start={{ start_str }}&end={{ end_str }}" class="{{ 'active' if p==page else '' }}">{{ p }}</a>
    {% endfor %}
    {% if page < total_pages %}
    <a href="?page={{ page+1 }}&start={{ start_str }}&end={{ end_str }}">下一页 ▶</a>
    {% endif %}
  </div>
</div>

<script>
async function saveMqttConfig() {
  const cfg = {
    broker:    document.getElementById('mqBroker').value.trim(),
    port:      document.getElementById('mqPort').value.trim(),
    topic:     document.getElementById('mqTopic').value.trim(),
    username:  document.getElementById('mqUser').value.trim(),
    client_id: document.getElementById('mqClientId').value.trim()
  };
  const pass = document.getElementById('mqPass').value;
  if (pass) cfg.password = pass;
  const sp = document.getElementById('mqSaveStatus');
  sp.textContent = '保存中…';
  sp.className = 'blue';
  try {
    const r = await fetch('/api/mqtt_config', {
      method: 'POST',
      headers: {'Content-Type':'application/json'},
      body: JSON.stringify(cfg)
    });
    const j = await r.json();
    if (j.ok) {
      sp.textContent = '✅ 已保存';
      sp.className = 'green';
      // 同步更新 pub topic 框
      document.getElementById('pubTopic').value = cfg.topic;
    } else {
      sp.textContent = '❌ ' + (j.error||'失败');
      sp.className = 'red';
    }
  } catch(e) {
    sp.textContent = '❌ 网络错误';
    sp.className = 'red';
  }
}

async function mqttPublish() {
  const topic   = document.getElementById('pubTopic').value.trim();
  const payload = document.getElementById('pubPayload').value.trim();
  const st = document.getElementById('mqttStatus');
  st.textContent = '发布中…';
  st.className = 'blue';
  const body = { topic };
  if (payload) body.payload = payload;
  try {
    const r = await fetch('/mqtt_pub', {
      method: 'POST',
      headers: {'Content-Type':'application/json'},
      body: JSON.stringify(body)
    });
    const j = await r.json();
    if (j.ok) {
      st.textContent = '✅ 发布成功  topic=' + j.topic + '  payload=' + j.payload.substring(0,80);
      st.className = 'green';
    } else {
      st.textContent = '❌ 失败：' + (j.error||'未知');
      st.className = 'red';
    }
  } catch(e) {
    st.textContent = '❌ 网络错误：' + e;
    st.className = 'red';
  }
}
</script>
</body></html>
"""

GAODE_MAP_HTML = """
<!DOCTYPE html>
<html lang="zh-CN">
<head><meta charset="utf-8"><title>高德地图轨迹</title>
<style>body,html{margin:0;padding:0;height:100%}#map{height:100vh}</style>
<script src="https://webapi.amap.com/maps?v=2.0&key=YOUR_GAODE_KEY"></script>
</head>
<body>
<div id="map"></div>
<script>
var points = __POINTS__;
var map = new AMap.Map('map',{zoom:12});
var path = points.map(function(p){return new AMap.LngLat(p[1],p[0]);});
if(path.length>0){
  new AMap.Polyline({path:path,strokeColor:'#FF0000',strokeWeight:4,map:map});
  map.setFitView();
}
</script>
</body></html>
"""

BAIDU_MAP_HTML = """
<!DOCTYPE html>
<html lang="zh-CN">
<head><meta charset="utf-8"><title>百度地图轨迹</title>
<style>body,html{margin:0;padding:0;height:100%}#bmap{height:100vh;width:100%}</style>
<script src="https://api.map.baidu.com/api?v=3.0&ak=YOUR_BAIDU_KEY"></script>
</head>
<body>
<div id="bmap"></div>
<script>
var points = __POINTS__;
var map = new BMap.Map("bmap");
var path = points.map(function(p){return new BMap.Point(p[1],p[0]);});
if(path.length>0){
  map.centerAndZoom(path[0],12);
  var polyline = new BMap.Polyline(path,{strokeColor:'red',strokeWeight:4,strokeOpacity:0.8});
  map.addOverlay(polyline);
}
</script>
</body></html>
"""

GOOGLE_MAP_HTML = """
<!DOCTYPE html>
<html lang="zh-CN">
<head><meta charset="utf-8"><title>谷歌地图轨迹</title>
<style>body,html{margin:0;padding:0;height:100%}#gmap{height:100vh}</style>
<script src="https://maps.googleapis.com/maps/api/js?key=YOUR_GOOGLE_KEY"></script>
</head>
<body>
<div id="gmap"></div>
<script>
var points = __POINTS__;
var coords = points.map(function(p){return {lat:p[0],lng:p[1]};});
var map = new google.maps.Map(document.getElementById('gmap'),{zoom:12,center:coords[0]||{lat:0,lng:0}});
if(coords.length>0){
  new google.maps.Polyline({path:coords,geodesic:true,strokeColor:'#FF0000',strokeOpacity:1,strokeWeight:3,map:map});
  var bounds = new google.maps.LatLngBounds();
  coords.forEach(function(c){bounds.extend(c);});
  map.fitBounds(bounds);
}
</script>
</body></html>
"""

# ─── 主入口 ──────────────────────────────────────────────────────────────────
if __name__ == "__main__":
    init_db()
    print(f"[SERVER] Starting on {HOST}:{PORT}")
    app.run(host=HOST, port=PORT, debug=False)
