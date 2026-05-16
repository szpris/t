package com.gpstracker.app

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.sqrt

class LocationService : Service(), SensorEventListener {

    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var database: LocationDatabase
    private lateinit var apiClient: ApiClient
    private lateinit var prefs: SharedPreferences

    private val binder = LocalBinder()

    @Volatile
    private var lastUploadedLocation: Location? = null
    @Volatile
    private var lastUploadTimeMs: Long = 0L

    /** 正在上传中的记录 ID 集合，防止 uploadLoop 重复提交 */
    private val uploadingIds = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    // ── 运动检测 ──────────────────────────────────────────────────────────────
    /**
     * 最近一次检测到运动的时间戳（毫秒）。
     * 加速度变化超过 MOTION_THRESHOLD 时更新。
     * 超过 MOTION_LINGER_MS 没有更新则认为静止。
     */
    @Volatile
    private var lastMotionDetectedMs: Long = 0L

    /** 上一帧加速度向量（用于差分计算抖动幅度） */
    private val lastAccel = FloatArray(3)
    private var accelInitialized = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handleLocationUpdate(location)
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager   = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        database = LocationDatabase.getInstance(this)
        apiClient = ApiClient.getInstance(this)
        prefs = getSharedPreferences("gps_tracker_settings", Context.MODE_PRIVATE)
        createNotificationChannel()

        Log.d(TAG, "Service created with minDist=${getMinDistance()}m, minInterval=${getMinInterval()}min, motionDetect=${isMotionDetectEnabled()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP  -> { stopTracking(); return START_NOT_STICKY }
            ACTION_START -> {
                // 同步设置 isRunning，使 MainActivity 在 onStartCommand 返回前调用 updateServiceStatus() 能读到正确状态
                isRunning = true
                startTracking()
            }
            // MQTT now 指令：获取一次位置并上报，不影响当前追踪状态
            MqttService.ACTION_MQTT_NOW -> {
                Log.i(TAG, "MQTT NOW command received")
                uploadOnceViaMqtt()
                return START_STICKY
            }
            null         -> {
                Log.d(TAG, "onStartCommand: intent=null, restarting tracking")
                startTracking()
            }
            else -> startTracking()
        }
        return START_STICKY
    }

    /** MQTT now 指令触发的一次性定位上报 */
    private fun uploadOnceViaMqtt() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "MQTT now: no location permission")
            return
        }
        try {
            // 1. 优先用最近一次已知位置（2分钟内）
            val lastKnown = lastUploadedLocation
            if (lastKnown != null && System.currentTimeMillis() - lastUploadTimeMs < 120_000L) {
                Log.i(TAG, "MQTT now: using cached location")
                doMqttUpload(lastKnown)
                return
            }
            // 2. GPS 优先，30s 无果切网络
            var gpsGot = false
            val gpsListener = object : LocationListener {
                private var fired = false
                override fun onLocationChanged(location: Location) {
                    if (fired) return
                    fired = true
                    locationManager.removeUpdates(this)
                    gpsGot = true
                    Log.i(TAG, "MQTT now: GPS got location")
                    doMqttUpload(location)
                }
                override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                override fun onProviderEnabled(p: String) {}
                override fun onProviderDisabled(p: String) {}
            }
            val useGps = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val useNet = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            if (useGps) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1_000L, 0f,
                    gpsListener, Looper.getMainLooper()
                )
                android.os.Handler(Looper.getMainLooper()).postDelayed({
                    if (!gpsGot) {
                        locationManager.removeUpdates(gpsListener)
                        if (useNet) {
                            Log.i(TAG, "MQTT now: GPS timeout, switching to network")
                            val netListener = object : LocationListener {
                                private var fired = false
                                override fun onLocationChanged(location: Location) {
                                    if (fired) return
                                    fired = true
                                    locationManager.removeUpdates(this)
                                    doMqttUpload(location)
                                }
                                override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                                override fun onProviderEnabled(p: String) {}
                                override fun onProviderDisabled(p: String) {}
                            }
                            locationManager.requestLocationUpdates(
                                LocationManager.NETWORK_PROVIDER, 1_000L, 0f,
                                netListener, Looper.getMainLooper()
                            )
                            android.os.Handler(Looper.getMainLooper()).postDelayed({
                                locationManager.removeUpdates(netListener)
                            }, 15_000L)
                        }
                    }
                }, 30_000L)
            } else if (useNet) {
                val netListener = object : LocationListener {
                    private var fired = false
                    override fun onLocationChanged(location: Location) {
                        if (fired) return
                        fired = true
                        locationManager.removeUpdates(this)
                        doMqttUpload(location)
                    }
                    override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                }
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 1_000L, 0f,
                    netListener, Looper.getMainLooper()
                )
                android.os.Handler(Looper.getMainLooper()).postDelayed({
                    locationManager.removeUpdates(netListener)
                }, 15_000L)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "MQTT now: SecurityException", e)
        }
    }

    private fun doMqttUpload(location: Location) {
        val now = System.currentTimeMillis()
        val locData = LocationData(
            latitude  = location.latitude,
            longitude = location.longitude,
            altitude  = if (location.hasAltitude()) location.altitude else null,
            accuracy  = if (location.hasAccuracy()) location.accuracy else null,
            speed     = if (location.hasSpeed()) location.speed else null,
            bearing   = if (location.hasBearing()) location.bearing else null,
            provider  = location.provider ?: "mqtt_now",
            clientTime = now,
            uploaded  = false
        )
        val id = database.insertLocation(locData)
        doUpload(locData.copy(id = id), location, now, updateLastLocation = false)
        Log.i(TAG, "MQTT now: upload triggered")
    }

    private fun getMinDistance(): Float =
        prefs.getFloat("min_distance", 200f)

    private fun getMinInterval(): Float =
        prefs.getFloat("min_interval", 2f)

    /** 是否启用运动检测（默认开启） */
    private fun isMotionDetectEnabled(): Boolean =
        prefs.getBoolean("motion_detect_enabled", true)

    private fun startTracking() {
        // 防止重复启动（系统 START_STICKY 重建时可能重复调用）
        if (isRunning) {
            Log.d(TAG, "startTracking: already running, skip duplicate start")
            // 仍需重新调用 startForeground 以满足系统要求
            try { startForeground(NOTIFICATION_ID, createNotification("📍 GPS追踪运行中", "正在获取位置...")) } catch (_: Exception) {}
            return
        }

        isRunning = true
        // 初始化时假设手机有运动，避免刚启动就因"静止"而不上传第一个点
        lastMotionDetectedMs = System.currentTimeMillis()

        val notification = createNotification("📍 GPS追踪运行中", "正在获取位置...")
        startForeground(NOTIFICATION_ID, notification)

        // 注册加速度传感器
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accel != null) {
            sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL)
            Log.d(TAG, "Accelerometer registered")
        } else {
            Log.w(TAG, "No accelerometer found, motion detection disabled")
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    GPS_UPDATE_INTERVAL_MS,
                    0f,
                    locationListener,
                    Looper.getMainLooper()
                )
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    GPS_UPDATE_INTERVAL_MS,
                    0f,
                    locationListener,
                    Looper.getMainLooper()
                )
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied", e)
            }
        }

        Thread { uploadLoop() }.start()
        Log.d(TAG, "Tracking started")
    }

    private fun stopTracking() {
        isRunning = false
        try {
            locationManager.removeUpdates(locationListener)
            sensorManager.unregisterListener(this)
        } catch (e: Exception) { }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "Tracking stopped")
    }

    private fun handleLocationUpdate(location: Location) {
        // ── GPS 精度过滤 ───────────────────────────────────────────────────
        // GPS 卫星定位：精度要求严格（≤50m）
        // 网络/基站定位：精度要求宽松（≤500m），保证地铁/隧道等无GPS场景也能上报
        val accuracy = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE
        val isGps = (location.provider == LocationManager.GPS_PROVIDER)
        val maxAccuracy = if (isGps) MIN_ACCURACY_GPS else MIN_ACCURACY_NETWORK

        if (accuracy > maxAccuracy) {
            Log.d(TAG, "Skip: accuracy too poor (${accuracy.toInt()}m > ${maxAccuracy.toInt()}m, provider=${location.provider})")
            updateNotification(
                "📡 ${location.provider ?: "unknown"} 精度: ${accuracy.toInt()}m",
                "精度不足，等待更好信号..."
            )
            return
        }

        val now = System.currentTimeMillis()
        val minDist = getMinDistance()
        val minIntervalMin = getMinInterval()
        val minIntervalMs = (minIntervalMin * 60_000).toLong()

        val motionDetect = isMotionDetectEnabled()
        val moving = isMoving(now)

        val clientTimeStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(now))

        val motionStatus = if (motionDetect) (if (moving) " 🚶运动中" else " 🛑静止") else ""
        val providerIcon = when (location.provider) {
            LocationManager.GPS_PROVIDER     -> "🛰️GPS"
            LocationManager.NETWORK_PROVIDER -> "📶网络"
            else                             -> "📍${location.provider}"
        }
        updateNotification(
            "📍 ${String.format("%.5f", location.latitude)}, ${String.format("%.5f", location.longitude)}",
            "$providerIcon  精度:${String.format("%.0f", accuracy)}m  $clientTimeStr$motionStatus"
        )

        // 只有满足上传条件时才写入DB并上传，避免积压大量待上传记录
        if (shouldUpload(location, now, minDist, minIntervalMs, motionDetect, moving)) {
            val locData = LocationData(
                latitude  = location.latitude,
                longitude = location.longitude,
                altitude  = if (location.hasAltitude()) location.altitude else null,
                accuracy  = if (location.hasAccuracy()) location.accuracy else null,
                speed     = if (location.hasSpeed()) location.speed else null,
                bearing   = if (location.hasBearing()) location.bearing else null,
                provider  = location.provider,
                clientTime = now,
                uploaded  = false
            )

            val id = database.insertLocation(locData)
            val saved = locData.copy(id = id)

            doUpload(saved, location, now, updateLastLocation = true)
        }
    }

    /**
     * 上传决策逻辑：
     *
     * 开启运动检测时：
     *   - 手机静止（isMoving=false）→ 不上传（避免生成无意义重复坐标点）
     *   - 手机运动 + 位移 >= minDist → 立即上传（捕捉轨迹转折）
     *   - 手机运动 + 距上次 >= minInterval → 立即上传（保证轨迹密度）
     *
     * 关闭运动检测时（降级兜底）：
     *   - 位移 >= minDist OR 距上次 >= minInterval → 上传（原有逻辑）
     *
     * 首次上传（无历史记录）→ 始终上传
     */
    private fun shouldUpload(
        location: Location,
        nowMs: Long,
        minDist: Float,
        minIntervalMs: Long,
        motionDetectEnabled: Boolean,
        isMoving: Boolean
    ): Boolean {
        val lastLoc  = lastUploadedLocation
        val lastTime = lastUploadTimeMs

        // 首次上传
        if (lastLoc == null || lastTime == 0L) {
            Log.d(TAG, "shouldUpload: true (first upload)")
            return true
        }

        val distance  = lastLoc.distanceTo(location)
        val elapsedMs = nowMs - lastTime

        return if (motionDetectEnabled) {
            if (!isMoving) {
                Log.d(TAG, "shouldUpload: false (stationary, dist=${distance.toInt()}m, elapsed=${elapsedMs/1000}s)")
                false
            } else {
                val upload = distance >= minDist || elapsedMs >= minIntervalMs
                Log.d(TAG, "shouldUpload: $upload (moving, dist=${distance.toInt()}m/${minDist.toInt()}m, elapsed=${elapsedMs/1000}s/${minIntervalMs/1000}s)")
                upload
            }
        } else {
            val upload = distance >= minDist || elapsedMs >= minIntervalMs
            Log.d(TAG, "shouldUpload: $upload (no-motion-detect, dist=${distance.toInt()}m/${minDist.toInt()}m, elapsed=${elapsedMs/1000}s/${minIntervalMs/1000}s)")
            upload
        }
    }

    // ── 传感器回调 ──────────────────────────────────────────────────────────
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        if (!accelInitialized) {
            lastAccel[0] = x; lastAccel[1] = y; lastAccel[2] = z
            accelInitialized = true
            return
        }

        // 计算帧间加速度变化量（过滤掉静止时的重力恒量，只看实际晃动）
        val dx = x - lastAccel[0]
        val dy = y - lastAccel[1]
        val dz = z - lastAccel[2]
        val delta = sqrt(dx * dx + dy * dy + dz * dz)

        lastAccel[0] = x; lastAccel[1] = y; lastAccel[2] = z

        if (delta >= MOTION_THRESHOLD) {
            lastMotionDetectedMs = System.currentTimeMillis()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * 手机是否处于运动状态：
     * MOTION_LINGER_MS（30秒）内有加速度超阈值变化则视为运动。
     */
    private fun isMoving(nowMs: Long): Boolean =
        (nowMs - lastMotionDetectedMs) <= MOTION_LINGER_MS

    private fun doUpload(locData: LocationData, location: Location, nowMs: Long, updateLastLocation: Boolean = false) {
        // 防止重复上传同一条记录
        if (!uploadingIds.add(locData.id)) {
            Log.d(TAG, "doUpload: skip #${locData.id}, already uploading")
            return
        }

        apiClient.uploadLocation(locData, object : ApiClient.Callback {
            override fun onSuccess() {
                uploadingIds.remove(locData.id)
                database.markAsUploaded(locData.id)
                if (updateLastLocation) {
                    lastUploadedLocation = location
                    lastUploadTimeMs = nowMs
                }
                Log.d(TAG, "Upload success: #${locData.id} ${locData.latitude},${locData.longitude}")
            }

            override fun onError(message: String) {
                uploadingIds.remove(locData.id)
                Log.w(TAG, "Upload failed: #${locData.id} $message")
            }
        })
    }

    private fun uploadLoop() {
        while (isRunning) {
            try {
                Thread.sleep(UPLOAD_CHECK_INTERVAL_MS)
                if (!isRunning) break

                // 只重试真正失败（未上传、且当前不在上传中）的记录
                val pending = database.getPendingLocations()
                    .filter { it.id !in uploadingIds }

                if (pending.isEmpty()) continue

                Log.d(TAG, "uploadLoop: retrying ${pending.size} failed locations")
                for (loc in pending) {
                    if (!isRunning) break
                    // 构造一个仅用于重试的 Location，不更新 lastUploadedLocation
                    val l = android.location.Location("retry").apply {
                        latitude  = loc.latitude
                        longitude = loc.longitude
                    }
                    // updateLastLocation = false，不影响正常上传的位移判断
                    doUpload(loc, l, System.currentTimeMillis(), updateLastLocation = false)
                    Thread.sleep(3000)
                }
            } catch (e: InterruptedException) {
                Log.d(TAG, "uploadLoop: interrupted, exiting")
                break
            } catch (t: Throwable) {
                // 捕获所有 Throwable（含 Error），防止 uploadLoop 线程无声死亡
                Log.e(TAG, "Upload loop error", t)
                // 发生未知错误后短暂等待，避免高频重试
                try { Thread.sleep(10_000L) } catch (_: InterruptedException) { break }
            }
        }
        Log.d(TAG, "uploadLoop: exited")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GPS追踪服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "后台持续定位追踪"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(title: String, content: String) {
        try {
            notificationManager.notify(NOTIFICATION_ID, createNotification(title, content))
        } catch (e: Exception) { }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false   // 保底：系统 kill 时也重置状态
        Log.d(TAG, "Service destroyed")
    }

    inner class LocalBinder : Binder() {
        fun getService(): LocationService = this@LocationService
    }

    companion object {
        const val TAG = "LocationService"

        const val ACTION_START = "com.gpstracker.app.START"
        const val ACTION_STOP  = "com.gpstracker.app.STOP"

        const val CHANNEL_ID      = "gps_tracker_channel"
        const val NOTIFICATION_ID = 1001

        // GPS 硬件更新频率（30秒，固定不变）
        const val GPS_UPDATE_INTERVAL_MS = 30_000L
        // 重试检查间隔
        const val UPLOAD_CHECK_INTERVAL_MS = 60_000L  // 1分钟检查一次

        // ── GPS 精度过滤 ──────────────────────────────────────────────────
        /**
         * GPS 卫星定位精度阈值（米）：严格过滤，只保留高质量卫星定位。
         * 超过此值说明 GPS 信号弱，丢弃该点。
         */
        const val MIN_ACCURACY_GPS = 50f

        /**
         * 网络/基站定位精度阈值（米）：宽松过滤，兜底地铁/隧道/室内场景。
         * 网络定位通常精度在 50~300m，设 500m 保证大部分网络定位可以上报。
         */
        const val MIN_ACCURACY_NETWORK = 500f

        // ── 运动检测参数 ────────────────────────────────────────────────────
        /**
         * 加速度帧间变化量阈值（m/s²）。
         * 低于此值视为静止抖动（如放在桌上），超过则视为有运动。
         * 0.5 ≈ 轻微拿起/移动手机；可根据实测调整。
         */
        const val MOTION_THRESHOLD = 0.5f

        /**
         * 运动残留时长（毫秒）。
         * 最后一次检测到运动后，保持"运动中"状态的时间。
         * 设为 30 秒：停下来 30 秒后才认为已静止，避免走走停停时频繁切状态。
         */
        const val MOTION_LINGER_MS = 30_000L

        @Volatile
        var isRunning = false
            private set
    }
}
