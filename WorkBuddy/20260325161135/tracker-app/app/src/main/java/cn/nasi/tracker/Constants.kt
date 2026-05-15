package cn.nasi.tracker

object Constants {
    // 服务端配置
    const val SERVER_URL = "http://track.nasi.cn:55000"
    const val API_UPLOAD = "$SERVER_URL/api/upload"
    const val API_KEY = "NasiTracker2026SecretKey#X9mZ"  // 双方共享密钥，可按需修改

    // 位置上报触发条件
    const val INTERVAL_MS = 60_000L        // 1分钟
    const val DISTANCE_THRESHOLD = 200f    // 200米

    // 本地存储最近记录数
    const val MAX_DISPLAY_RECORDS = 20

    // MQTT 订阅配置
    const val MQTT_BROKER        = "tcp://xy.nasi.cn:1883"
    const val MQTT_TOPIC_SUB     = "SM09aZ09aZ/GPSTrackHost/info"
    const val MQTT_CLIENT_ID     = "NasiTrackerApp"

    // MQTT 指令 payload
    const val MQTT_CMD_NOW       = "now"    // 立刻上报一次位置
    const val MQTT_CMD_START     = "start"  // 启动持续上报
    const val MQTT_CMD_STOP      = "stop"   // 停止持续上报
}
