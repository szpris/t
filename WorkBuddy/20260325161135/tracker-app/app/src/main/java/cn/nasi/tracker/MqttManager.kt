package cn.nasi.tracker

import android.content.Context
import android.util.Log
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

/**
 * MQTT 订阅管理器
 * 使用纯 Java paho client（无需 Android Service），在 TrackerService 内管理生命周期。
 * 订阅 Constants.MQTT_TOPIC_SUB，根据 payload 控制追踪行为：
 *   now   → 立刻上报一次当前位置
 *   start → 启动持续追踪
 *   stop  → 停止持续追踪
 */
class MqttManager(
    private val context: Context,
    private val onCommand: (cmd: String) -> Unit
) {
    companion object {
        private const val TAG = "MqttManager"
    }

    private var client: MqttClient? = null
    private var reconnectThread: Thread? = null
    @Volatile private var wantConnected = false

    fun connect() {
        wantConnected = true
        doConnect()
    }

    private fun doConnect() {
        if (client?.isConnected == true) return
        try {
            val c = MqttClient(
                Constants.MQTT_BROKER,
                Constants.MQTT_CLIENT_ID,
                MemoryPersistence()
            )
            val opts = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                connectionTimeout = 15
                keepAliveInterval = 60
            }
            c.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "MQTT connection lost: ${cause?.message}")
                    scheduleReconnect()
                }
                override fun messageArrived(topic: String, message: MqttMessage) {
                    val payload = message.toString().trim()
                    Log.i(TAG, "MQTT msg: topic=$topic payload=$payload")
                    onCommand(payload)
                }
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
            c.connect(opts)
            c.subscribe(Constants.MQTT_TOPIC_SUB, 1)
            client = c
            Log.i(TAG, "MQTT connected & subscribed: ${Constants.MQTT_BROKER} topic=${Constants.MQTT_TOPIC_SUB}")
        } catch (e: MqttException) {
            Log.e(TAG, "MQTT connect failed: ${e.message}")
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!wantConnected) return
        reconnectThread?.interrupt()
        reconnectThread = Thread {
            try {
                Thread.sleep(10_000)
                if (wantConnected) doConnect()
            } catch (_: InterruptedException) {}
        }.also { it.isDaemon = true; it.start() }
    }

    fun disconnect() {
        wantConnected = false
        reconnectThread?.interrupt()
        try {
            client?.takeIf { it.isConnected }?.disconnect(1000)
        } catch (_: Exception) {}
        client = null
    }
}
