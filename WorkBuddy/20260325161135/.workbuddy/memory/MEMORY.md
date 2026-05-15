# MEMORY.md - 长期记忆

## NasiTracker 项目（2026-03-25）

### 项目结构
- `tracker-app/` - Android App 源码（Kotlin）
- `tracker-server/` - Python 服务端
- `DEPLOY.md` - 完整部署说明

### 关键配置
- API_KEY: `NasiTracker2026SecretKey#X9mZ`（App 和服务端须一致）
- 服务器：track.nasi.cn:55000（Debian 12）
- Web 管理账号：admin / NasiAdmin2026!
- App 包名：cn.nasi.tracker

### 编译 APK
推荐通过 GitHub Actions 云端编译（已内置 `.github/workflows/build.yml`）

## 注意：两个目录
- **WorkBuddy 工作区**：`c:\Users\pris\WorkBuddy\20260325161135\tracker-app\`（包名 cn.nasi.tracker，仅作参考）
- **真实 GitHub 仓库**：`C:\Users\pris\.qclaw\workspace\gps-tracker\android\`（包名 com.gpstracker.app，实际编译/推送的目录）
- GitHub Actions：https://github.com/szpris/gps-tracker-app/actions

### 服务端新增
- `server.py` 新增 MQTT 配置表（sqlite mqtt_config），默认 broker=xy.nasi.cn:1883，topic=SM09aZ09aZ/GPSTrackHost/info
- Web UI：统计卡片 → MQTT发布区（mqtt_pub）→ MQTT服务器设置 → 数据查询 → 导出按钮
- 新路由：`GET/POST /api/mqtt_config`（保存/读取配置）、`POST /mqtt_pub`（发布最新位置或自定义payload）
- 依赖新增：`paho-mqtt>=1.6.1`（部署时 `pip install paho-mqtt`）

### App 新增
- `Constants.kt`：新增 MQTT_BROKER/MQTT_TOPIC_SUB/MQTT_CLIENT_ID/CMD 常量
- 新文件 `MqttManager.kt`：纯 Java paho client，自动重连，接收 payload 回调
- `TrackerService.kt`：Service onCreate 时启动 MQTT 订阅，响应 now/start/stop 指令
  - `now`：立即单次获取并上报位置
  - `start`：启动持续追踪（幂等）
  - `stop`：停止持续追踪（幂等）
- `app/build.gradle`：新增 `org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5`，versionCode=2
- `settings.gradle`：新增 Eclipse paho maven 仓库
- `AndroidManifest.xml`：新增 WAKE_LOCK 权限
