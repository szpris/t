package com.gpstracker.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingsActivity"

        private const val MIN_DISTANCE_MIN = 10f   // 最小位移下限：10米
        private const val MIN_DISTANCE_MAX = 500f  // 最小位移上限：500米
        private const val MIN_INTERVAL_MIN = 1f    // 最小间隔下限：1分钟
        private const val MIN_INTERVAL_MAX = 30f   // 最小间隔上限：30分钟
    }

    private lateinit var etServerUrl: android.widget.EditText
    private lateinit var etApiKey: android.widget.EditText
    private lateinit var seekMinDistance: SeekBar
    private lateinit var seekMinInterval: SeekBar
    private lateinit var tvMinDistance: TextView
    private lateinit var tvMinInterval: TextView
    private lateinit var tvDeviceId: TextView
    private lateinit var switchMotionDetect: SwitchCompat
    private lateinit var btnSave: android.widget.Button

    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences("gps_tracker_settings", Context.MODE_PRIVATE)

        initViews()
        loadSettings()
        setupSeekBars()
    }

    private fun initViews() {
        etServerUrl    = findViewById(R.id.etServerUrl)
        etApiKey       = findViewById(R.id.etApiKey)
        seekMinDistance = findViewById(R.id.seekMinDistance)
        seekMinInterval = findViewById(R.id.seekMinInterval)
        tvMinDistance  = findViewById(R.id.tvMinDistance)
        tvMinInterval  = findViewById(R.id.tvMinInterval)
        tvDeviceId     = findViewById(R.id.tvDeviceId)
        switchMotionDetect = findViewById(R.id.switchMotionDetect)
        btnSave        = findViewById(R.id.btnSave)

        btnSave.setOnClickListener { saveSettings() }

        // 显示当前设备名
        tvDeviceId.text = ApiClient.getInstance(this).getDeviceId()

        // 点击"重置"：清除已存设备ID，重新从系统读取
        findViewById<TextView>(R.id.btnResetDeviceId).setOnClickListener {
            ApiClient.getInstance(this).setDeviceId("")          // 清空
            val newId = ApiClient.getInstance(this).getDeviceId() // 重新解析
            tvDeviceId.text = newId
            Toast.makeText(this, "设备名已重置为: $newId", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadSettings() {
        etServerUrl.setText(ApiClient.getInstance(this).getServerUrl())
        etApiKey.setText(ApiClient.getInstance(this).getApiKey())

        val minDist = prefs.getFloat("min_distance", 200f)
        val minInterval = prefs.getFloat("min_interval", 2f)
        val motionDetectEnabled = prefs.getBoolean("motion_detect_enabled", true)

        // SeekBar 范围映射（使用 490f 确保浮点除法）
        seekMinDistance.progress = ((minDist - MIN_DISTANCE_MIN) / (MIN_DISTANCE_MAX - MIN_DISTANCE_MIN) * 490f).toInt()
        seekMinInterval.progress = ((minInterval - MIN_INTERVAL_MIN) / (MIN_INTERVAL_MAX - MIN_INTERVAL_MIN) * 28f).toInt()
        switchMotionDetect.isChecked = motionDetectEnabled

        updateDistanceLabel(minDist)
        updateIntervalLabel(minInterval)
    }

    private fun setupSeekBars() {
        // 最小位移 SeekBar：10~500米
        seekMinDistance.max = 490
        seekMinDistance.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val dist = MIN_DISTANCE_MIN + (progress / 490f) * (MIN_DISTANCE_MAX - MIN_DISTANCE_MIN)
                updateDistanceLabel(dist)
                if (fromUser) markAsChanged()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 最小间隔 SeekBar：1~30分钟
        seekMinInterval.max = 28
        seekMinInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val interval = MIN_INTERVAL_MIN + (progress / 28f) * (MIN_INTERVAL_MAX - MIN_INTERVAL_MIN)
                updateIntervalLabel(interval)
                if (fromUser) markAsChanged()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 运动检测开关
        switchMotionDetect.setOnCheckedChangeListener { _, _ ->
            markAsChanged()
        }
    }

    private fun updateDistanceLabel(dist: Float) {
        tvMinDistance.text = when {
            dist < 50 -> "${dist.toInt()} 米"
            else -> "${dist.toInt()} 米"
        }
        tvMinDistance.setTextColor(
            when {
                dist <= 50 -> android.graphics.Color.parseColor("#FF9800")
                dist <= 200 -> android.graphics.Color.parseColor("#E94560")
                else -> android.graphics.Color.parseColor("#E94560")
            }
        )
    }

    private fun updateIntervalLabel(interval: Float) {
        tvMinInterval.text = "${interval.toInt()} 分钟"
    }

    private fun saveSettings() {
        val serverUrl = etServerUrl.text.toString().trim()
        val apiKey = etApiKey.text.toString().trim()

        if (serverUrl.isEmpty()) {
            etServerUrl.error = "请输入服务器地址"
            return
        }
        if (apiKey.isEmpty()) {
            etApiKey.error = "请输入 API 密钥"
            return
        }

        val minDist = MIN_DISTANCE_MIN + (seekMinDistance.progress / 490f) * (MIN_DISTANCE_MAX - MIN_DISTANCE_MIN)
        val minInterval = MIN_INTERVAL_MIN + (seekMinInterval.progress / 28f) * (MIN_INTERVAL_MAX - MIN_INTERVAL_MIN)
        val motionDetect = switchMotionDetect.isChecked

        // 保存到 Settings
        prefs.edit()
            .putFloat("min_distance", minDist)
            .putFloat("min_interval", minInterval)
            .putBoolean("motion_detect_enabled", motionDetect)
            .apply()

        // 保存到 ApiClient
        ApiClient.getInstance(this).setServerUrl(serverUrl)
        ApiClient.getInstance(this).setApiKey(apiKey)

        Log.d(TAG, "Settings saved: serverUrl=$serverUrl, minDist=${minDist.toInt()}m, minInterval=${minInterval.toInt()}min, motionDetect=$motionDetect")

        val motionStr = if (motionDetect) "开启（静止不上传）" else "关闭（始终按距离/时间上传）"
        hasUnsavedChanges = false  // 重置未保存标记

        AlertDialog.Builder(this)
            .setTitle("设置已保存")
            .setMessage("配置已更新，正在运行的服务将立即使用新设置。\n\n最小位移：${minDist.toInt()}米\n运动中间隔：${minInterval.toInt()}分钟\n运动检测：$motionStr")
            .setPositiveButton("确定") { _, _ -> finish() }
            .setNegativeButton("继续修改", null)
            .show()
    }

    private var hasUnsavedChanges = false

    fun onBack(v: android.view.View) {
        if (hasUnsavedChanges) {
            AlertDialog.Builder(this)
                .setTitle("提示")
                .setMessage("您修改了设置但尚未保存，确定要退出吗？")
                .setPositiveButton("退出") { _, _ -> finish() }
                .setNegativeButton("留在本页", null)
                .show()
        } else {
            finish()
        }
    }

    private fun markAsChanged() {
        hasUnsavedChanges = true
    }
}
