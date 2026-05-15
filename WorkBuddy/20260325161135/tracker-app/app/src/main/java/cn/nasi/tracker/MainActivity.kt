package cn.nasi.tracker

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvCurrentLoc: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnRefresh: Button
    private lateinit var lvRecords: ListView

    private val adapter by lazy { ArrayAdapter<String>(this, android.R.layout.simple_list_item_1) }
    private val db by lazy { DatabaseHelper(this) }
    private val sdf = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 连续点击5次 tvStatus 打开调试界面
    private var statusClickCount = 0
    private var lastStatusClickTime = 0L

    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val record = intent.getSerializableExtra(TrackerService.EXTRA_RECORD) as? LocationRecord
            record?.let { updateCurrentLocation(it) }
            refreshList()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupListeners()
        checkPermissions()
        refreshList()
        registerReceiver(locationReceiver, IntentFilter(TrackerService.BROADCAST_LOCATION),
            RECEIVER_NOT_EXPORTED)
    }

    private fun bindViews() {
        tvStatus     = findViewById(R.id.tvStatus)
        tvCurrentLoc = findViewById(R.id.tvCurrentLoc)
        btnStart     = findViewById(R.id.btnStart)
        btnStop      = findViewById(R.id.btnStop)
        btnRefresh   = findViewById(R.id.btnRefresh)
        lvRecords    = findViewById(R.id.lvRecords)
        lvRecords.adapter = adapter
    }

    private fun setupListeners() {
        btnStart.setOnClickListener {
            startService(Intent(this, TrackerService::class.java).apply {
                action = TrackerService.ACTION_START
            })
            updateStatus("追踪中…")
        }
        btnStop.setOnClickListener {
            startService(Intent(this, TrackerService::class.java).apply {
                action = TrackerService.ACTION_STOP
            })
            updateStatus("已停止")
        }
        btnRefresh.setOnClickListener { refreshList() }

        // 连续点击5次状态栏 → 调试界面
        tvStatus.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastStatusClickTime > 3000) statusClickCount = 0
            lastStatusClickTime = now
            statusClickCount++
            if (statusClickCount >= 5) {
                statusClickCount = 0
                startActivity(Intent(this, DebugActivity::class.java))
            }
        }
    }

    private fun updateStatus(msg: String) {
        tvStatus.text = "状态: $msg  (连续点5次进调试)"
    }

    @SuppressLint("SetTextI18n")
    private fun updateCurrentLocation(r: LocationRecord) {
        tvCurrentLoc.text = "纬度: ${"%.6f".format(r.latitude)}\n" +
                "经度: ${"%.6f".format(r.longitude)}\n" +
                "精度: ${"%.1f".format(r.accuracy)}m\n" +
                "时间: ${sdf.format(Date(r.timestamp))}"
    }

    private fun refreshList() {
        scope.launch {
            val records = withContext(Dispatchers.IO) { db.getRecent(Constants.MAX_DISPLAY_RECORDS) }
            adapter.clear()
            records.forEachIndexed { index, r ->
                val uploaded = if (r.uploaded) "✓" else "…"
                adapter.add(
                    "${index + 1}. ${sdf.format(Date(r.timestamp))}  " +
                            "${"%.5f".format(r.latitude)}, ${"%.5f".format(r.longitude)}  $uploaded"
                )
            }
        }
    }

    // ─── 权限请求 ───────────────────────────────────────────────────────────────

    private fun checkPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 可按需处理结果
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(locationReceiver)
        scope.cancel()
    }
}
