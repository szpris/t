package cn.nasi.tracker

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
 *
 * 息屏保活：keepAlive=30s + QoS2 + cleanSession=false + TrackerService 的 WakeLock
 * 网络恢复：ConnectivityManager.NetworkCallback 立即触发重连
 */
class MqttManager(
    private val context: Context,
    private val onCommand: (cmd: String) -> Unit
) {
    companion object {
        private const val TAG = "MqttManager"
        private const val KEEP_ALIVE_SEC = 30
        private const val QOS = 2
    }

    private var client: MqttClient? = null
    private var reconnectThread: Thread? = null
    @Volatile private var wantConnected = false
    @Volatile private var connectionState: ConnectionState = ConnectionState.DISCONNECTED

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

    fun connect() {
        wantConnected = true
        registerNetworkCallback()
        doConnect()
    }

    private fun doConnect() {
        if (client?.isConnected == true) return
        connectionState = ConnectionState.CONNECTING
        try {
            val c = MqttClient(
                Constants.MQTT_BROKER,
                Constants.MQTT_CLIENT_ID,
                MemoryPersistence()
            )
            val opts = MqttConnectOptions().apply {
                // isAutomaticReconnect 由我们手动实现（支持 QoS2 + cleanSession）
                isAutomaticReconnect = false
                isCleanSession = false          // 保留离线消息，QoS2 持久化
                connectionTimeout = 15
                keepAliveInterval = KEEP_ALIVE_SEC
            }
            c.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "MQTT connection lost: ${cause?.message}")
                    connectionState = ConnectionState.DISCONNECTED
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
            c.subscribe(Constants.MQTT_TOPIC_SUB, QOS)
            client = c
            connectionState = ConnectionState.CONNECTED
            Log.i(TAG, "MQTT connected & subscribed: QoS=$QOS cleanSession=false keepAlive=${KEEP_ALIVE_SEC}s")
        } catch (e: MqttException) {
            Log.e(TAG, "MQTT connect failed: ${e.message}")
            connectionState = ConnectionState.DISCONNECTED
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!wantConnected) return
        reconnectThread?.interrupt()
        reconnectThread = Thread {
            try {
                Thread.sleep(5_000)   // 重连等待缩短至 5s（原 10s）
                if (wantConnected) doConnect()
            } catch (_: InterruptedException) {}
        }.also { it.isDaemon = true; it.start() }
    }

    // 供 TrackerService 在网络恢复时调用，立即触发重连
    fun onNetworkAvailable() {
        if (connectionState != ConnectionState.CONNECTED) {
            Log.i(TAG, "Network available, reconnecting...")
            doConnect()
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Network available")
                if (connectionState != ConnectionState.CONNECTED) {
                    onNetworkAvailable()
                }
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                Log.d(TAG, "Network capabilities changed, hasInternet=$hasInternet")
            }
        }
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(req, networkCallback!!)
        Log.d(TAG, "NetworkCallback registered")
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
            networkCallback = null
        }
    }

    fun disconnect() {
        wantConnected = false
        reconnectThread?.interrupt()
        unregisterNetworkCallback()
        try {
            client?.takeIf { it.isConnected }?.disconnect(1000)
        } catch (_: Exception) {}
        client = null
        connectionState = ConnectionState.DISCONNECTED
    }

    fun getConnectionState(): ConnectionState = connectionState
}
