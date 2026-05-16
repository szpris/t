package com.gpstracker.app

import android.util.Log
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

/**
 * MQTT 订阅管理器
 * 在 LocationService 生命周期内管理 MQTT 长连接。
 * 订阅固定 topic，收到 payload 后通过回调通知上层：
 *   now   → 立刻上报一次当前位置
 *   start → 启动持续追踪
 *   stop  → 停止持续追踪
 */
class MqttManager(private val onCommand: (cmd: String) -> Unit) {

    companion object {
        private const val TAG        = "MqttManager"
        const val BROKER             = "tcp://xy.nasi.cn:51883"
        const val TOPIC              = "SM09aZ09aZ/GPSTrackHost/info"
        const val CLIENT_ID          = "GPSTrackerApp"
        private const val RECONNECT_DELAY_MS = 10_000L

        // 控制指令
        const val CMD_NOW   = "now"
        const val CMD_START = "start"
        const val CMD_STOP  = "stop"
    }

    @Volatile private var client: MqttClient? = null
    @Volatile private var wantConnected = false
    private var reconnectThread: Thread? = null

    /** 建立连接并订阅（在子线程调用，会阻塞直到连接完成或失败） */
    fun connect() {
        wantConnected = true
        connectInternal()
    }

    private fun connectInternal() {
        if (client?.isConnected == true) return
        try {
            val c = MqttClient(BROKER, CLIENT_ID, MemoryPersistence())
            val opts = MqttConnectOptions().apply {
                isAutomaticReconnect = false   // 我们自己管理重连
                isCleanSession = true
                connectionTimeout = 15
                keepAliveInterval = 60
            }
            c.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "Connection lost: ${cause?.message}")
                    scheduleReconnect()
                }
                override fun messageArrived(topic: String, message: MqttMessage) {
                    val payload = message.toString().trim()
                    Log.i(TAG, "Message: topic=$topic  payload=$payload")
                    onCommand(payload)
                }
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
            c.connect(opts)
            c.subscribe(TOPIC, 1)
            client = c
            Log.i(TAG, "Connected & subscribed  broker=$BROKER  topic=$TOPIC")
        } catch (e: MqttException) {
            Log.e(TAG, "Connect failed (${e.reasonCode}): ${e.message}")
            scheduleReconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Connect error: ${e.message}")
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!wantConnected) return
        reconnectThread?.interrupt()
        reconnectThread = Thread {
            try {
                Thread.sleep(RECONNECT_DELAY_MS)
                if (wantConnected) connectInternal()
            } catch (_: InterruptedException) {}
        }.also { it.isDaemon = true; it.start() }
    }

    fun disconnect() {
        wantConnected = false
        reconnectThread?.interrupt()
        try { client?.takeIf { it.isConnected }?.disconnect(1000) } catch (_: Exception) {}
        try { client?.close(true) } catch (_: Exception) {}
        client = null
        Log.i(TAG, "Disconnected")
    }
}
