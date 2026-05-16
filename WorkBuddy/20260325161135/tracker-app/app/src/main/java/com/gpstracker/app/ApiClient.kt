package com.gpstracker.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class ApiClient private constructor(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("gps_tracker_prefs", Context.MODE_PRIVATE)
    private val executor = Executors.newCachedThreadPool()

    companion object {
        @Volatile
        private var instance: ApiClient? = null

        fun getInstance(context: Context): ApiClient {
            return instance ?: synchronized(this) {
                instance ?: ApiClient(context.applicationContext).also { instance = it }
            }
        }
    }

    fun getServerUrl(): String = prefs.getString("server_url", "http://track.nasi.cn:55000") ?: ""
    fun getApiKey(): String = prefs.getString("api_key", "GPS_TRACKER_API_KEY_2024_CHANGE_ME") ?: ""

    /**
     * 获取设备 ID，优先级：
     * 1. 已持久化的 device_id（保证 ID 不变）
     * 2. 系统蓝牙名 / 手机名称（Settings.Global.DEVICE_NAME）
     * 3. 用户自定义名称（Settings.Secure.BLUETOOTH_NAME，部分机型）
     * 4. 兜底：Build.MANUFACTURER + Build.MODEL + UUID 前8位（唯一且可读）
     *
     * 首次生成后写入 SharedPreferences，后续不再重新生成。
     */
    fun getDeviceId(): String {
        val saved = prefs.getString("device_id", null)
        if (!saved.isNullOrBlank() && saved != "device_001") return saved

        val deviceName = resolveDeviceName()
        prefs.edit().putString("device_id", deviceName).apply()
        return deviceName
    }

    private fun resolveDeviceName(): String {
        // 1. 尝试读取系统设备名（蓝牙名/手机名，用户可在设置里修改的那个）
        val globalName = try {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }

        if (globalName != null) return sanitize(globalName)

        // 2. 部分机型（小米/OPPO/vivo 等）蓝牙名在 bluetooth_name
        val btName = try {
            Settings.Secure.getString(context.contentResolver, "bluetooth_name")
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }

        if (btName != null) return sanitize(btName)

        // 3. 兜底：厂商 + 型号 + UUID 前8位，保证每台机器不同
        val model = "${Build.MANUFACTURER}_${Build.MODEL}".replace(" ", "_")
        val uid   = UUID.randomUUID().toString().substring(0, 8)
        return sanitize("${model}_$uid")
    }

    /** 去除非法字符，只保留字母/数字/下划线/短横线，最长40字符 */
    private fun sanitize(name: String): String =
        name.replace(Regex("[^\\w\\-]"), "_").take(40)

    fun setServerUrl(url: String) = prefs.edit().putString("server_url", url).apply()
    fun setApiKey(key: String) = prefs.edit().putString("api_key", key).apply()
    fun setDeviceId(id: String) = prefs.edit().putString("device_id", id).apply()

    interface Callback {
        fun onSuccess()
        fun onError(message: String)
    }

    fun uploadLocation(location: LocationData, callback: Callback) {
        executor.execute {
            try {
                val url = URL("${getServerUrl()}/api/upload")
                val conn = url.openConnection() as HttpURLConnection

                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("X-API-Key", getApiKey())
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.doOutput = true

                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                val json = JSONObject().apply {
                    put("device_id", getDeviceId())
                    put("lat", location.latitude)
                    put("lng", location.longitude)
                    put("altitude", location.altitude)
                    put("accuracy", location.accuracy)
                    put("speed", location.speed)
                    put("bearing", location.bearing)
                    put("provider", location.provider)
                    put("client_time", dateFormat.format(Date(location.clientTime)))
                }

                OutputStreamWriter(conn.outputStream).use { writer ->
                    writer.write(json.toString())
                    writer.flush()
                }

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    callback.onSuccess()
                } else {
                    val error = try {
                        BufferedReader(conn.errorStream.reader()).readText()
                    } catch (e: Exception) {
                        "HTTP $responseCode"
                    }
                    callback.onError(error)
                }
                conn.disconnect()
            } catch (e: Exception) {
                callback.onError(e.message ?: "Unknown error")
            }
        }
    }

    fun uploadManualLocation(lat: Double, lng: Double, callback: Callback) {
        val location = LocationData(
            latitude = lat,
            longitude = lng,
            altitude = null,
            accuracy = null,
            speed = null,
            bearing = null,
            provider = "manual",
            clientTime = System.currentTimeMillis()
        )
        uploadLocation(location, callback)
    }
}
