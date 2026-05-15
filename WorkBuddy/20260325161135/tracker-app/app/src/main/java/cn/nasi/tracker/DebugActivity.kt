package cn.nasi.tracker

import android.annotation.SuppressLint
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class DebugActivity : AppCompatActivity() {

    private lateinit var btnTestUpload: Button
    private lateinit var etLat: EditText
    private lateinit var etLng: EditText
    private lateinit var btnManualUpload: Button
    private lateinit var tvDebugLog: TextView

    private val uploadManager by lazy { UploadManager(this) }
    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)
        title = "调试界面"

        btnTestUpload    = findViewById(R.id.btnTestUpload)
        etLat            = findViewById(R.id.etLat)
        etLng            = findViewById(R.id.etLng)
        btnManualUpload  = findViewById(R.id.btnManualUpload)
        tvDebugLog       = findViewById(R.id.tvDebugLog)

        btnTestUpload.setOnClickListener { testUploadCurrentLocation() }
        btnManualUpload.setOnClickListener { uploadManualLocation() }
    }

    @SuppressLint("MissingPermission")
    private fun testUploadCurrentLocation() {
        log("正在获取当前位置…")
        fusedClient.lastLocation.addOnSuccessListener { location ->
            if (location == null) {
                log("无法获取当前位置")
                return@addOnSuccessListener
            }
            val record = LocationRecord(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracy = location.accuracy,
                timestamp = System.currentTimeMillis()
            )
            scope.launch {
                log("上传中: lat=${record.latitude}, lng=${record.longitude}")
                val ok = uploadManager.uploadRecord(record)
                if (ok) {
                    DatabaseHelper(this@DebugActivity).also { db ->
                        val id = db.insert(record)
                        db.markUploaded(id)
                    }
                }
                log(if (ok) "✓ 上传成功" else "✗ 上传失败（已存本地）")
            }
        }
    }

    private fun uploadManualLocation() {
        val latStr = etLat.text.toString().trim()
        val lngStr = etLng.text.toString().trim()
        if (latStr.isEmpty() || lngStr.isEmpty()) {
            log("请输入纬度和经度")
            return
        }
        val lat = latStr.toDoubleOrNull()
        val lng = lngStr.toDoubleOrNull()
        if (lat == null || lng == null) {
            log("格式错误，请输入有效数字")
            return
        }
        val record = LocationRecord(
            latitude = lat,
            longitude = lng,
            accuracy = 0f,
            timestamp = System.currentTimeMillis()   // 自动补充当前时间
        )
        scope.launch {
            log("手动上传: lat=$lat, lng=$lng, time=${sdf.format(Date(record.timestamp))}")
            val ok = uploadManager.uploadRecord(record)
            if (ok) {
                DatabaseHelper(this@DebugActivity).also { db ->
                    val id = db.insert(record)
                    db.markUploaded(id)
                }
            }
            log(if (ok) "✓ 上传成功" else "✗ 上传失败（已存本地）")
        }
    }

    private fun log(msg: String) {
        tvDebugLog.append("[${sdf.format(Date())}] $msg\n")
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
