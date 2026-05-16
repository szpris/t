package com.gpstracker.app

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import org.eclipse.paho.client.mqttv3.*

/**
 * 独立的 MQTT 前台服务（常驻）。
 *
 * App 打开时自动启动，之后即使 App 退到后台或 Activity 关闭，
 * 此服务仍然保持 MQTT 连接，持续监听指令并响应：
 *   now   → 立即获取位置并上报
 *   start → 启动 LocationService 追踪
 *   stop  → 停止 LocationService 追踪
 *
 * LocationService 本身不再持有 MQTT 连接，彻底解耦。
 *
 * 息屏保活策略：
 *   1. WakeLock (PARTIAL) — 防止 CPU 在发心跳时被挂起
 *   2. keepAliveInterval = 30s — 比系统省电定时器更短，确保心跳能正常发出
 *   3. NetworkCallback — 网络恢复时立即重连，不等 10s 重连定时器
 *   4. cleanSession = false + QoS 2 — 断线期间 broker 保留消息，重连后不丢指令
 */
class MqttService : Service() {

    enum class ConnectionState { CONNECTED, CONNECTING, DISCONNECTED }

    companion object {
        private const val TAG       = "MqttService"
        const val CHANNEL_ID        = "mqtt_channel"
        const val NOTIFICATION_ID   = 2001

        const val BROKER  = "tcp://xy.nasi.cn:51883"
        const val TOPIC   = "SM09aZ09aZ/GPSTrackHost/info"
        const val CLIENT  = "GPSTrackerApp"

        const val CMD_NOW   = "now"
        const val CMD_START = "start"
        const val CMD_STOP  = "stop"

        private const val RECONNECT_MS      = 10_000L
        /** keepAlive 间隔（秒）。设为 30s：短于大多数运营商的 NAT 超时，也短于系统省电唤醒周期 */
        private const val KEEP_ALIVE_SEC    = 30
        private const val PREFS_NAME        = "mqtt_state"
        private const val KEY_STATE         = "state"
        private const val KEY_MESSAGE       = "message"

        /** 供 LocationService 识别 MQTT 触发的单次上报 */
        const val ACTION_MQTT_NOW = "com.gpstracker.app.MQTT_NOW"

        // ── 收到的消息记录（用于 DebugActivity 展示）────────────────────────
        val messageLog = java.util.concurrent.ConcurrentLinkedDeque<String>()

        // ── 回调给外部（DebugActivity / 其他组件）──────────────────────────
        var onMessageReceived: ((String) -> Unit)? = null
        /** MQTT 连接状态变化回调，供 Activity 注册实时感知连接状态 */
        var onStateChanged: ((ConnectionState) -> Unit)? = null

        /**
         * 连接状态（持久化到 SharedPreferences，确保 Activity 在回调注册前
         * 就能读到正确状态，不必依赖回调时序）。
         */
        var connectionState: ConnectionState = ConnectionState.DISCONNECTED
            private set

        /**
         * 连接状态对应的消息（用于显示错误详情）。
         * Activity 可直接读取，不必等回调。
         */
        var connectionMessage: String = "未连接"
            private set

        /** 是否已经初始化过（防止 Service 重建时覆盖已有的错误信息） */
        @Volatile private var hasInitialized = false

        /** 最后尝试连接的时间戳（毫秒） */
        @Volatile var lastConnectAttemptTime: Long = 0
            private set

        /** 连接尝试次数 */
        @Volatile var connectAttemptCount: Int = 0
            private set

        private fun persistState(state: ConnectionState, message: String) {
            connectionState = state
            connectionMessage = message
            try {
                val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs?.edit()
                    ?.putString(KEY_STATE, state.name)
                    ?.putString(KEY_MESSAGE, message)
                    ?.apply()
            } catch (_: Throwable) {}
            // 触发回调，让 Activity 实时感知状态变化
            onStateChanged?.invoke(state)
        }

        fun initFromPrefs(ctx: Context) {
            if (hasInitialized) return
            hasInitialized = true
            try {
                val prefs = ctx.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val stateName = prefs.getString(KEY_STATE, ConnectionState.DISCONNECTED.name)!!
                val msg       = prefs.getString(KEY_MESSAGE, "未连接")!!
                connectionState  = ConnectionState.valueOf(stateName)
                connectionMessage = msg
            } catch (_: Throwable) {}
        }

        /** 全局 Context 引用（由 Service 在 onCreate 时注入） */
        var appContext: Context? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, MqttService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MqttService::class.java))
        }
    }

    @Volatile private var client: MqttClient? = null
    @Volatile private var wantConnected = true
    private var reconnectThread: Thread? = null

    // ── 息屏保活：WakeLock ─────────────────────────────────────────────────
    private var wakeLock: PowerManager.WakeLock? = null

    // ── 网络变化监听：网络恢复时立即重连 ──────────────────────────────────
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        try {
            doOnCreate()
        } catch (t: Throwable) {
            Log.e(TAG, "FATAL in onCreate: ${t.message}", t)
        }
    }

    private fun doOnCreate() {
        super.onCreate()
        MqttService.appContext = applicationContext
        MqttService.initFromPrefs(applicationContext)

        acquireWakeLock()
        registerNetworkCallback()

        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("📡 MQTT 连接中..."))
        Handler(Looper.getMainLooper()).postDelayed({
            try { connect() } catch (t: Throwable) {
                Log.e(TAG, "connect() crashed: ${t.message}", t)
                persistState(ConnectionState.DISCONNECTED, "连接异常: ${t.message}")
            }
        }, 2000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        wantConnected = false
        reconnectThread?.interrupt()
        unregisterNetworkCallback()
        releaseWakeLock()
        try { client?.disconnect(1000) } catch (_: Exception) {}
        try { client?.close(true) }      catch (_: Exception) {}
        persistState(ConnectionState.DISCONNECTED, "服务已停止")
        Log.i(TAG, "Service destroyed")
    }

    // ── WakeLock：保持 CPU 唤醒，防止心跳线程被系统暂停 ──────────────────
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "GPSTracker:MqttWakeLock"
            ).also {
                it.setReferenceCounted(false)
                it.acquire()   // 不设超时，由 onDestroy 释放
            }
            Log.d(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "acquireWakeLock failed: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            Log.d(TAG, "WakeLock released")
        } catch (_: Exception) {}
    }

    // ── NetworkCallback：网络恢复时立即重连 ──────────────────────────────
    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "Network available, triggering reconnect")
                    // 网络恢复时，如果 MQTT 未连接则立即重连
                    if (connectionState != ConnectionState.CONNECTED) {
                        Handler(Looper.getMainLooper()).post {
                            try { connect() } catch (t: Throwable) {
                                Log.e(TAG, "reconnect after network: ${t.message}", t)
                            }
                        }
                    }
                }
                override fun onLost(network: Network) {
                    Log.w(TAG, "Network lost")
                }
            }
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(req, cb)
            networkCallback = cb
            Log.d(TAG, "NetworkCallback registered")
        } catch (e: Exception) {
            Log.e(TAG, "registerNetworkCallback failed: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
                networkCallback = null
            }
        } catch (_: Exception) {}
    }

    // ── MQTT 连接 ────────────────────────────────────────────────────────────
    private fun connect() {
        if (client?.isConnected == true) return
        connectAttemptCount++
        lastConnectAttemptTime = System.currentTimeMillis()
        persistState(ConnectionState.CONNECTING, "连接中...")
        try {
            // cleanSession=false：断线期间 broker 保留未投递的 QoS 2 消息，重连后补发
            val c = MqttClient(BROKER, CLIENT, org.eclipse.paho.client.mqttv3.persist.MemoryPersistence())
            val opts = MqttConnectOptions().apply {
                isAutomaticReconnect = false          // 由我们自己控制重连逻辑
                isCleanSession       = false          // 保留 session（配合 QoS 2 不丢消息）
                connectionTimeout    = 15
                keepAliveInterval    = KEEP_ALIVE_SEC // 30s，短于运营商 NAT 超时
            }
            c.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    Log.e(TAG, "Connection lost: ${cause?.message}", cause)
                    persistState(ConnectionState.DISCONNECTED, "连接断开 (${cause?.message ?: "未知原因"})")
                    scheduleReconnect()
                }
                override fun messageArrived(topic: String, message: MqttMessage) {
                    val payload = message.toString().trim()
                    Log.i(TAG, "Message [QoS${message.qos}]: $payload")
                    logMessage(payload)
                    handleCommand(payload)
                    onMessageReceived?.invoke(payload)
                }
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
            c.connect(opts)
            // subscribe 用 QoS 2：exactly-once，重连后 broker 不会重复推已投递的消息
            c.subscribe(TOPIC, 2)
            client = c
            persistState(ConnectionState.CONNECTED, "已连接")
            updateNotification("📡 MQTT 已连接")
            Log.i(TAG, "Connected: broker=$BROKER topic=$TOPIC qos=2 keepAlive=${KEEP_ALIVE_SEC}s")
        } catch (e: MqttException) {
            val msg = "连接失败 (${e.reasonCode}): ${e.message}"
            Log.e(TAG, msg, e)
            persistState(ConnectionState.DISCONNECTED, msg)
            scheduleReconnect()
        } catch (e: Exception) {
            val msg = "连接异常: ${e.message}"
            Log.e(TAG, msg, e)
            persistState(ConnectionState.DISCONNECTED, msg)
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!wantConnected) return
        reconnectThread?.interrupt()
        reconnectThread = Thread {
            try { Thread.sleep(RECONNECT_MS) } catch (_: InterruptedException) { return@Thread }
            if (wantConnected) {
                try { connect() } catch (t: Throwable) {
                    Log.e(TAG, "scheduleReconnect connect failed: ${t.message}", t)
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    // ── 指令处理 ────────────────────────────────────────────────────────────
    private fun handleCommand(payload: String) {
        val cmd = payload.lowercase().trim()
        Log.i(TAG, "Handle command: $cmd")
        when (cmd) {
            CMD_NOW -> {
                val intent = Intent(this, LocationService::class.java).apply {
                    action = ACTION_MQTT_NOW
                }
                startService(intent)
            }
            CMD_START -> {
                val intent = Intent(this, LocationService::class.java).apply {
                    action = LocationService.ACTION_START
                }
                startService(intent)
            }
            CMD_STOP -> {
                val intent = Intent(this, LocationService::class.java).apply {
                    action = LocationService.ACTION_STOP
                }
                startService(intent)
            }
            else -> Log.w(TAG, "Unknown command: $cmd")
        }
    }

    // ── 日志记录 ────────────────────────────────────────────────────────────
    private fun logMessage(payload: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        messageLog.addFirst("[$ts] $payload")
        while (messageLog.size > 50) { messageLog.removeLast() }
    }

    // ── 通知栏 ───────────────────────────────────────────────────────────────
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "MQTT 服务", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "保持 MQTT 长连接，接收远程指令"; setShowBadge(false) }
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("GPS Tracker")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_menu_share)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun updateNotification(text: String) {
        try {
            (getSystemService(NotificationManager::class.java))
                .notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {}
    }
}
