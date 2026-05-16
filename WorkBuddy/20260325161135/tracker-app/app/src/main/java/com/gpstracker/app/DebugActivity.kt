package com.gpstracker.app

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.gpstracker.app.MqttService.ConnectionState

class DebugActivity : AppCompatActivity() {

    private lateinit var locationManager: LocationManager
    private lateinit var etLat: EditText
    private lateinit var etLng: EditText
    private lateinit var tvResult: TextView
    private lateinit var tvMqttStatus: TextView
    private lateinit var tvMqttLog: TextView
    private lateinit var tvMqttBadge: TextView

    private val mqttHandler = Handler(Looper.getMainLooper())
    private var mqttUpdateRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        etLat = findViewById(R.id.etLat)
        etLng = findViewById(R.id.etLng)
        tvResult = findViewById(R.id.tvResult)
        tvMqttStatus = findViewById(R.id.tvMqttStatus)
        tvMqttLog = findViewById(R.id.tvMqttLog)
        tvMqttBadge = findViewById(R.id.tvMqttBadge)

        findViewById<Button>(R.id.btnGetCurrentLocation).setOnClickListener {
            fetchBestLocation(fillFields = true, doUpload = false)
        }

        findViewById<Button>(R.id.btnUploadManual).setOnClickListener {
            uploadManualLocation()
        }

        findViewById<Button>(R.id.btnUploadCurrent).setOnClickListener {
            fetchBestLocation(fillFields = true, doUpload = true)
        }
    }

    override fun onResume() {
        super.onResume()
        startMqttUiUpdate()
        // 注册 MQTT 状态变化回调（立即感知连接/断开）
        MqttService.onStateChanged = { updateMqttStatus() }
        // 注册 MQTT 消息回调
        MqttService.onMessageReceived = { payload ->
            runOnUiThread { appendMqttLog("收到: $payload") }
        }
        // 首次立即更新一次
        updateMqttStatus()
    }

    override fun onPause() {
        super.onPause()
        stopMqttUiUpdate()
        // 注销回调，防止覆盖 MainActivity 的回调
        MqttService.onStateChanged = null
        MqttService.onMessageReceived = null
    }

    private fun startMqttUiUpdate() {
        mqttUpdateRunnable = object : Runnable {
            override fun run() {
                updateMqttStatus()
                mqttHandler.postDelayed(this, 2000)
            }
        }
        mqttHandler.post(mqttUpdateRunnable!!)
    }

    private fun stopMqttUiUpdate() {
        mqttUpdateRunnable?.let { mqttHandler.removeCallbacks(it) }
        mqttUpdateRunnable = null
    }

    private fun updateMqttStatus() {
        val state = MqttService.connectionState
        val (text, color) = when (state) {
            MqttService.ConnectionState.CONNECTED    -> "已连接 ✓" to 0xFF4CAF50.toInt()
            MqttService.ConnectionState.CONNECTING   -> "连接中..." to 0xFFFFC107.toInt()
            MqttService.ConnectionState.DISCONNECTED -> {
                val msg = MqttService.connectionMessage
                val display = if (!msg.isNullOrBlank() && msg != "未连接")
                    "未连接 ($msg)" else "未连接 ✗"
                display to 0xFFE94560.toInt()
            }
        }
        tvMqttBadge.text = text
        tvMqttBadge.setTextColor(color)

        // 构建详细诊断信息
        val attemptInfo = if (MqttService.connectAttemptCount > 0) {
            val lastTry = MqttService.lastConnectAttemptTime
            val elapsed = if (lastTry > 0) {
                val sec = (System.currentTimeMillis() - lastTry) / 1000
                "${sec}秒前"
            } else "未知"
            "尝试次数: ${MqttService.connectAttemptCount} | 最后尝试: $elapsed"
        } else "尚未尝试连接"

        // 检查 Service 是否存活
        val isServiceRunning = isServiceRunning(MqttService::class.java)
        val serviceInfo = "Service: ${if (isServiceRunning) "运行中" else "未运行"}"

        tvMqttStatus.text = "Topic: ${MqttService.TOPIC}\nBroker: ${MqttService.BROKER}\n$attemptInfo\n$serviceInfo"
    }

    /** 检查指定 Service 是否正在运行 */
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        return manager.getRunningServices(Integer.MAX_VALUE)
            .any { it.service.className == serviceClass.name }
    }

    private fun appendMqttLog(line: String) {
        val current = tvMqttLog.text.toString()
        val lines = current.split("\n").toMutableList()
        lines.add(0, line)
        while (lines.size > 10) lines.removeAt(lines.lastIndex)
        tvMqttLog.text = lines.joinToString("\n")
    }

    /**
     * 获取"最佳"当前位置，策略与 LocationService 一致：
     *  - 同时查询 GPS + NETWORK 最后缓存位置，选精度最好的那个
     *  - 精度阈值：GPS ≤50m，NETWORK ≤500m（地铁/室内兜底）
     *  - 若缓存满足精度，直接使用；否则发起一次性单次回调获取新鲜坐标
     *
     * @param fillFields 是否把坐标填入输入框
     * @param doUpload   是否上传
     */
    private fun fetchBestLocation(fillFields: Boolean, doUpload: Boolean) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "请授予位置权限", Toast.LENGTH_SHORT).show()
            return
        }

        tvResult.text = "正在获取位置（GPS + 网络同时尝试）..."

        // 先检查缓存
        val candidates = mutableListOf<Location>()
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    val last = locationManager.getLastKnownLocation(provider)
                    if (last != null) candidates.add(last)
                }
            } catch (_: SecurityException) {}
        }

        val best = candidates.filter { loc ->
            val acc = if (loc.hasAccuracy()) loc.accuracy else Float.MAX_VALUE
            val threshold = if (loc.provider == LocationManager.GPS_PROVIDER) 50f else 500f
            acc <= threshold
        }.minByOrNull { it.accuracy }

        if (best != null) {
            handleResult(best, fillFields, doUpload)
            return
        }

        // 缓存不满足精度，发起一次性实时请求
        val received = mutableSetOf<String>()
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val acc = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE
                val threshold = if (location.provider == LocationManager.GPS_PROVIDER) 50f else 500f
                if (acc <= threshold && received.add("done")) {
                    try { locationManager.removeUpdates(this) } catch (_: Exception) {}
                    runOnUiThread { handleResult(location, fillFields, doUpload) }
                }
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        var registered = 0
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                    registered++
                }
            } catch (_: SecurityException) {}
        }

        if (registered == 0) {
            tvResult.text = "GPS 和网络定位均不可用，请检查位置权限或开启位置服务"
        } else {
            tvResult.text = "实时获取中（已注册 $registered 个定位源）..."
        }
    }

    private fun handleResult(location: Location, fillFields: Boolean, doUpload: Boolean) {
        val providerLabel = when (location.provider) {
            LocationManager.GPS_PROVIDER     -> "🛰️GPS"
            LocationManager.NETWORK_PROVIDER -> "📶网络/基站"
            else                             -> "📍${location.provider}"
        }
        val accStr = if (location.hasAccuracy()) "精度${location.accuracy.toInt()}m" else "精度未知"

        if (fillFields) {
            etLat.setText(String.format("%.6f", location.latitude))
            etLng.setText(String.format("%.6f", location.longitude))
        }

        tvResult.text = "[$providerLabel $accStr] ${String.format("%.6f", location.latitude)}, ${String.format("%.6f", location.longitude)}"

        if (doUpload) {
            tvResult.append("\n正在上传...")
            ApiClient.getInstance(this).uploadManualLocation(
                location.latitude, location.longitude,
                object : ApiClient.Callback {
                    override fun onSuccess() {
                        runOnUiThread {
                            tvResult.text = "[$providerLabel $accStr] 上传成功!\n" +
                                "纬度: ${String.format("%.6f", location.latitude)}\n" +
                                "经度: ${String.format("%.6f", location.longitude)}"
                            Toast.makeText(this@DebugActivity, "上传成功", Toast.LENGTH_SHORT).show()
                        }
                    }
                    override fun onError(message: String) {
                        runOnUiThread { tvResult.append("\n上传失败: $message") }
                    }
                }
            )
        }
    }

    private fun uploadManualLocation() {
        val latStr = etLat.text.toString()
        val lngStr = etLng.text.toString()

        if (latStr.isBlank() || lngStr.isBlank()) {
            Toast.makeText(this, "请输入经纬度", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val lat = latStr.toDouble()
            val lng = lngStr.toDouble()
            tvResult.text = "正在上传..."
            ApiClient.getInstance(this).uploadManualLocation(lat, lng, object : ApiClient.Callback {
                override fun onSuccess() {
                    runOnUiThread {
                        tvResult.text = "上传成功!\n纬度: $lat\n经度: $lng"
                        Toast.makeText(this@DebugActivity, "上传成功", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onError(message: String) {
                    runOnUiThread { tvResult.text = "上传失败: $message" }
                }
            })
        } catch (e: NumberFormatException) {
            tvResult.text = "请输入有效的数字"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { locationManager.removeUpdates(object : LocationListener {
            override fun onLocationChanged(location: Location) {}
        }) } catch (_: Exception) {}
    }
}
