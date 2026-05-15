package cn.nasi.tracker

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class UploadManager(private val context: Context) {

    private val db = DatabaseHelper(context)

    /** 上传单条记录（手动构造），返回是否成功 */
    suspend fun uploadRecord(record: LocationRecord): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("api_key", Constants.API_KEY)
                put("latitude", record.latitude)
                put("longitude", record.longitude)
                put("accuracy", record.accuracy)
                put("timestamp", record.timestamp)
            }
            val response = post(Constants.API_UPLOAD, json.toString())
            Log.d("UploadManager", "Response: $response")
            response != null
        } catch (e: Exception) {
            Log.e("UploadManager", "Upload failed: ${e.message}")
            false
        }
    }

    /** 保存到本地，并尝试上传；网络失败则保留未上传状态 */
    suspend fun saveAndUpload(record: LocationRecord) {
        val id = db.insert(record)
        val rec = record.copy(id = id)
        if (uploadRecord(rec)) {
            db.markUploaded(id)
        }
        // 同时尝试补传所有历史未上传
        retryPending()
    }

    /** 重试所有待上传记录 */
    suspend fun retryPending() = withContext(Dispatchers.IO) {
        val pending = db.getPending()
        for (rec in pending) {
            if (uploadRecord(rec)) {
                db.markUploaded(rec.id)
            }
        }
    }

    private fun post(urlStr: String, body: String): String? {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().readText()
            } else null
        } finally {
            conn.disconnect()
        }
    }
}
