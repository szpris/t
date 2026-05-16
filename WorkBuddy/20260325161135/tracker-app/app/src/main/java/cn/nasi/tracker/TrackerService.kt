package cn.nasi.tracker

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*

class TrackerService : Service() {

    companion object {
        private const val TAG = "TrackerService"
        const val CHANNEL_ID = "tracker_channel"
        const val NOTIF_ID = 1001
        const val ACTION_START = "cn.nasi.tracker.START"
        const val ACTION_STOP  = "cn.nasi.tracker.STOP"
        const val BROADCAST_LOCATION = "cn.nasi.tracker.NEW_LOCATION"
        const val EXTRA_RECORD = "record"
        var isRunning = false
    }

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var uploadManager: UploadManager
    private lateinit var mqttManager: MqttManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var lastLocation: Location? = null
    private var lastUploadTime = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    // ─── LocationCallback ─────────────────────────────────────────────────────
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onNewLocation(it) }
        }
    }

    // ─── 单次立刻上报用的 callback ────────────────────────────────────────────
    private val oneShotCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { loc ->
                Log.i(TAG, "MQTT now: got location, uploading...")
                val record = LocationRecord(
                    latitude  = loc.latitude,
                    longitude = loc.longitude,
                    accuracy  = loc.accuracy,
                    timestamp = System.currentTimeMillis()
                )
                sendBroadcast(Intent(BROADCAST_LOCATION).apply {
                    putExtra(EXTRA_RECORD, record)
                })
                scope.launch { uploadManager.saveAndUpload(record) }
            }
            // 只拿一次，立即移除
            fusedClient.removeLocationUpdates(this)
        }
    }

    // ─── 生命周期 ─────────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        fusedClient    = LocationServices.getFusedLocationProviderClient(this)
        uploadManager  = UploadManager(this)
        createNotificationChannel()

        // WakeLock：防止息屏后 CPU 暂停导致 MQTT 心跳超时
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TrackerService:MqttWakeLock"
        ).also {
            it.setReferenceCounted(false)
            it.acquire()
        }
        Log.i(TAG, "WakeLock acquired, MQTT connecting...")

        // 初始化 MQTT，常驻订阅（Service 存活期间始终连接）
        mqttManager = MqttManager(this) { payload ->
            handleMqttCommand(payload)
        }
        mqttManager.connect()
        Log.i(TAG, "TrackerService created, MQTT connecting...")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_STOP  -> stopTracking()
        }
        return START_STICKY
    }

    // ─── MQTT 指令处理 ────────────────────────────────────────────────────────
    private fun handleMqttCommand(cmd: String) {
        Log.i(TAG, "handleMqttCommand: $cmd")
        when (cmd.lowercase().trim()) {
            Constants.MQTT_CMD_NOW -> {
                // 立刻获取一次位置并上报（不影响持续追踪状态）
                try {
                    val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
                        .setMaxUpdates(1)
                        .setWaitForAccurateLocation(false)
                        .build()
                    fusedClient.requestLocationUpdates(req, oneShotCallback, Looper.getMainLooper())
                } catch (e: SecurityException) {
                    Log.e(TAG, "MQTT now: no location permission")
                }
            }
            Constants.MQTT_CMD_START -> {
                // 启动持续追踪（若已在运行则幂等）
                if (!isRunning) {
                    Log.i(TAG, "MQTT start: starting tracking")
                    startTracking()
                    // 通知 UI 状态变化
                    sendBroadcast(Intent(BROADCAST_LOCATION))
                }
            }
            Constants.MQTT_CMD_STOP -> {
                // 停止持续追踪（若未运行则幂等）
                if (isRunning) {
                    Log.i(TAG, "MQTT stop: stopping tracking")
                    stopTracking()
                }
            }
            else -> Log.w(TAG, "Unknown MQTT command: $cmd")
        }
    }

    // ─── 追踪控制 ─────────────────────────────────────────────────────────────
    private fun startTracking() {
        isRunning = true
        startForeground(NOTIF_ID, buildNotification("位置追踪运行中"))
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, Constants.INTERVAL_MS)
            .setMinUpdateDistanceMeters(1f)   // 每米都拿到回调，自己判断阈值
            .setWaitForAccurateLocation(false)
            .build()
        try {
            fusedClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) { /* 权限由Activity申请 */ }
    }

    private fun stopTracking() {
        isRunning = false
        fusedClient.removeLocationUpdates(locationCallback)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onNewLocation(location: Location) {
        val now = System.currentTimeMillis()
        val timeSinceLast = now - lastUploadTime
        val distSinceLast = lastLocation?.distanceTo(location) ?: Float.MAX_VALUE

        val shouldUpload = timeSinceLast >= Constants.INTERVAL_MS ||
                distSinceLast >= Constants.DISTANCE_THRESHOLD

        if (!shouldUpload) return

        lastUploadTime = now
        lastLocation = location

        val record = LocationRecord(
            latitude  = location.latitude,
            longitude = location.longitude,
            accuracy  = location.accuracy,
            timestamp = now
        )

        // 广播给 UI
        sendBroadcast(Intent(BROADCAST_LOCATION).apply {
            putExtra(EXTRA_RECORD, record)
        })

        // 上传
        scope.launch { uploadManager.saveAndUpload(record) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        fusedClient.removeLocationUpdates(locationCallback)
        fusedClient.removeLocationUpdates(oneShotCallback)
        mqttManager.disconnect()
        scope.cancel()
        // 释放 WakeLock
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        super.onDestroy()
    }

    // ─── 通知工具 ─────────────────────────────────────────────────────────────
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "位置追踪", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Tracker")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(intent)
            .setOngoing(true)
            .build()
    }
}
