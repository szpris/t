package com.gpstracker.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvMqttStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnDebug: Button
    private lateinit var btnSettings: Button
    private lateinit var tvCurrentLocation: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var locationAdapter: LocationAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            doOnCreate(savedInstanceState)
        } catch (t: Throwable) {
            Log.e(TAG, "FATAL in onCreate: ${t.javaClass.simpleName}: ${t.message}", t)
            // 写崩溃日志到内部存储，供无 ADB 时排查
            try {
                openFileOutput("crash.log", Context.MODE_PRIVATE).use { fos ->
                    fos.write("${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                        .format(java.util.Date())}\n${t.javaClass.simpleName}: ${t.message}\n"
                        .toByteArray())
                }
            } catch (_: Throwable) {}
            Toast.makeText(this, "启动异常: ${t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun doOnCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        setContentView(R.layout.activity_main)

        initViews()
        setupClickListeners()
        updateServiceStatus()
        loadLocationHistory()

        // ── App 启动时自动开启服务 ─────────────────────────────────────────
        // 1. MQTT 前台服务（常驻，接收远程指令）
        try {
            MqttService.start(this)
            Log.d(TAG, "MqttService.start() called")
        } catch (e: Throwable) {
            Log.e(TAG, "MqttService.start() failed: ${e.message}", e)
            Toast.makeText(this, "MQTT 服务启动失败: ${e.message}", Toast.LENGTH_LONG).show()
        }

        // 2. GPS 追踪服务（已有权限则自动启动，无需用户手动点击）
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (hasFine && hasCoarse) {
            Log.d(TAG, "Auto-starting GPS tracking on launch")
            autoStartLocationService()
        }

        // 首次启动时检查电池优化豁免（国内手机后台保活的关键）
        checkBatteryOptimization()
    }

    /** 自动启动 GPS 追踪（不弹 Toast，避免打扰） */
    private fun autoStartLocationService() {
        val intent = Intent(this, LocationService::class.java).apply {
            action = LocationService.ACTION_START
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "autoStartLocationService failed", e)
        }
    }

    /**
     * 检查并引导用户将 App 加入电池优化白名单。
     * 未豁免时，系统（尤其是小米/华为/OPPO/vivo）会在屏幕关闭后主动杀掉前台服务。
     */
    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return   // 已豁免，无需提示

        AlertDialog.Builder(this)
            .setTitle("⚡ 允许后台运行（重要）")
            .setMessage(
                "检测到 GPS Tracker 未被加入电池优化白名单。\n\n" +
                "手机可能在锁屏后自动停止追踪服务。\n\n" +
                "建议：点击「去设置」→ 选择「不限制」或「允许后台活动」，" +
                "确保长时间追踪不中断。"
            )
            .setPositiveButton("去设置") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // 部分厂商不支持直接跳转，打开通用电池设置
                    try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    catch (_: Exception) {}
                }
            }
            .setNegativeButton("暂不") { _, _ -> }
            .show()
    }

    private fun initViews() {
        tvStatus      = findViewById(R.id.tvStatus)
        tvMqttStatus  = findViewById(R.id.tvMqttStatus)
        btnStart      = findViewById(R.id.btnStart)
        btnStop           = findViewById(R.id.btnStop)
        btnRefresh        = findViewById(R.id.btnRefresh)
        btnDebug          = findViewById(R.id.btnDebug)
        btnSettings       = findViewById(R.id.btnSettings)
        tvCurrentLocation = findViewById(R.id.tvCurrentLocation)
        recyclerView      = findViewById(R.id.recyclerView)

        recyclerView.layoutManager = LinearLayoutManager(this)
        locationAdapter = LocationAdapter()
        recyclerView.adapter = locationAdapter
    }

    private fun setupClickListeners() {
        btnStart.setOnClickListener {
            Log.d(TAG, "btnStart clicked")
            requestPermissionsAndStart()
        }

        btnStop.setOnClickListener {
            Log.d(TAG, "btnStop clicked")
            stopLocationService()
        }

        btnRefresh.setOnClickListener {
            Log.d(TAG, "btnRefresh clicked")
            loadLocationHistory()
            updateCurrentLocation()
            Toast.makeText(this, "已刷新", Toast.LENGTH_SHORT).show()
        }

        btnDebug.setOnClickListener {
            Log.d(TAG, "btnDebug clicked")
            startActivity(Intent(this, DebugActivity::class.java))
        }

        btnSettings.setOnClickListener {
            Log.d(TAG, "btnSettings clicked")
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun requestPermissionsAndStart() {
        val required = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startLocationService()
        } else {
            Log.d(TAG, "Requesting permissions: $missing")
            ActivityCompat.requestPermissions(
                this,
                missing.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.isNotEmpty() &&
                    grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                Log.d(TAG, "Permissions granted, starting service")
                startLocationService()
            } else {
                Log.w(TAG, "Permissions denied")
                AlertDialog.Builder(this)
                    .setTitle("需要位置权限")
                    .setMessage("GPS追踪需要位置权限才能工作。\n\n请前往 设置 → 应用 → GPS Tracker → 权限 手动开启。")
                    .setPositiveButton("去设置") { _, _ ->
                        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.fromParts("package", packageName, null)
                        }
                        startActivity(intent)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    private fun startLocationService() {
        Log.d(TAG, "startLocationService")
        val intent = Intent(this, LocationService::class.java).apply {
            action = LocationService.ACTION_START
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            updateServiceStatus()   // 立即更新 UI 状态
            Toast.makeText(this, "✓ 定位服务已启动", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service", e)
            Toast.makeText(this, "启动失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopLocationService() {
        Log.d(TAG, "stopLocationService")
        val intent = Intent(this, LocationService::class.java).apply {
            action = LocationService.ACTION_STOP
        }
        startService(intent)
        updateServiceStatus()   // 立即更新 UI 状态
        Toast.makeText(this, "定位服务已停止", Toast.LENGTH_SHORT).show()
    }

    private fun updateServiceStatus() {
        val prefs = getSharedPreferences("gps_tracker_settings", Context.MODE_PRIVATE)
        val minDist = prefs.getFloat("min_distance", 200f).toInt()
        val minInterval = prefs.getFloat("min_interval", 2f).toInt()

        if (LocationService.isRunning) {
            tvStatus.text = "● 运行中  ${minDist}米/${minInterval}分钟"
            tvStatus.setTextColor(Color.parseColor("#4CAF50"))
            btnStart.isEnabled = false
            btnStop.isEnabled  = true
        } else {
            tvStatus.text = "○ 已停止  ${minDist}米/${minInterval}分钟"
            tvStatus.setTextColor(Color.parseColor("#F44336"))
            btnStart.isEnabled = true
            btnStop.isEnabled  = false
        }
    }

    /** 根据 MqttService.connectionState 更新 MQTT 状态行（无需等回调，直接读 SharedPreferences 持久化状态） */
    private fun updateMqttStatus() {
        val state = MqttService.connectionState
        val (text, color) = when (state) {
            MqttService.ConnectionState.CONNECTED    -> "📡 MQTT 已连接" to "#4CAF50"
            MqttService.ConnectionState.CONNECTING   -> "📡 MQTT 连接中..." to "#FF9800"
            MqttService.ConnectionState.DISCONNECTED -> {
                // DISCONNECTED 时显示具体原因（如"连接失败: Connection refused"）
                val msg = MqttService.connectionMessage
                val base = if (!msg.isNullOrBlank() && msg != "未连接") msg else "未连接"
                val attemptInfo = if (MqttService.connectAttemptCount > 0) {
                    " | 已尝试${MqttService.connectAttemptCount}次"
                } else ""
                "📡 MQTT $base$attemptInfo" to "#E94560"
            }
        }
        tvMqttStatus.text = text
        tvMqttStatus.setTextColor(Color.parseColor(color))
    }

    private fun loadLocationHistory() {
        try {
            val history = LocationDatabase.getInstance(this).getLastLocations(20)
            locationAdapter.setData(history)
            Log.d(TAG, "Loaded ${history.size} records")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load history", e)
        }
    }

    private fun updateCurrentLocation() {
        try {
            val last = LocationDatabase.getInstance(this).getLastLocation()
            if (last != null) {
                tvCurrentLocation.text =
                    "纬度: ${String.format("%.6f", last.latitude)}\n" +
                    "经度: ${String.format("%.6f", last.longitude)}\n" +
                    "时间: ${last.clientTimeString}\n" +
                    "状态: ${if (last.uploaded) "✓ 已上传" else "⏳ 待上传"}"
            } else {
                tvCurrentLocation.text = "暂无位置数据"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update location", e)
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        updateCurrentLocation()
        loadLocationHistory()
        updateMqttStatus()

        // ── 如果 MQTT 已经是 DISCONNECTED 且有错误信息，立即告知用户 ──────────
        if (MqttService.connectionState == MqttService.ConnectionState.DISCONNECTED) {
            val msg = MqttService.connectionMessage
            if (!msg.isNullOrBlank() && msg != "未连接" && msg != "服务已停止") {
                Toast.makeText(this, "⚠️ MQTT: $msg", Toast.LENGTH_LONG).show()
            }
        }

        // 注册 MQTT 回调，实时感知连接状态和消息
        MqttService.onStateChanged = { state ->
            runOnUiThread {
                updateMqttStatus()
                // 连接失败时弹出 Toast，告知具体原因
                if (state == MqttService.ConnectionState.DISCONNECTED &&
                    !MqttService.connectionMessage.isNullOrBlank() &&
                    MqttService.connectionMessage != "未连接") {
                    Toast.makeText(this, "⚠️ MQTT: ${MqttService.connectionMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
        MqttService.onMessageReceived = { msg ->
            runOnUiThread {
                // MQTT 收到指令时也刷新一下追踪状态（因为 MQTT 可能触发 start/stop）
                updateServiceStatus()
                updateCurrentLocation()
                loadLocationHistory()
                Toast.makeText(this, "📡 MQTT: $msg", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 注销回调，防止内存泄漏
        MqttService.onStateChanged = null
        MqttService.onMessageReceived = null
    }
}
