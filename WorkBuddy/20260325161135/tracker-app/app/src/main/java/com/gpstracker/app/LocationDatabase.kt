package com.gpstracker.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.*

data class LocationData(
    val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val accuracy: Float?,
    val speed: Float?,
    val bearing: Float?,
    val provider: String?,
    val clientTime: Long,
    val uploaded: Boolean = false,
    val serverTime: Long? = null
) {
    val clientTimeString: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(clientTime))
}

class LocationDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "gps_tracker.db"
        private const val DB_VERSION = 1
        private const val TABLE_NAME = "locations"

        @Volatile
        private var instance: LocationDatabase? = null

        fun getInstance(context: Context): LocationDatabase {
            return instance ?: synchronized(this) {
                instance ?: LocationDatabase(context.applicationContext).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_NAME (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                altitude REAL,
                accuracy REAL,
                speed REAL,
                bearing REAL,
                provider TEXT,
                client_time INTEGER NOT NULL,
                uploaded INTEGER DEFAULT 0,
                server_time INTEGER
            )
        """)
        db.execSQL("CREATE INDEX idx_client_time ON $TABLE_NAME(client_time)")
        db.execSQL("CREATE INDEX idx_uploaded ON $TABLE_NAME(uploaded)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }

    fun insertLocation(loc: LocationData): Long {
        val values = ContentValues().apply {
            put("latitude", loc.latitude)
            put("longitude", loc.longitude)
            put("altitude", loc.altitude)
            put("accuracy", loc.accuracy)
            put("speed", loc.speed)
            put("bearing", loc.bearing)
            put("provider", loc.provider)
            put("client_time", loc.clientTime)
            put("uploaded", if (loc.uploaded) 1 else 0)
        }
        return writableDatabase.insert(TABLE_NAME, null, values)
    }

    fun getLastLocation(): LocationData? {
        return readableDatabase
            .query(TABLE_NAME, null, null, null, null, null, "id DESC", "1")
            .use { cursor ->
                if (cursor.moveToFirst()) cursorToLocationData(cursor) else null
            }
    }

    fun getLastLocations(limit: Int): List<LocationData> {
        val list = mutableListOf<LocationData>()
        readableDatabase
            .query(TABLE_NAME, null, null, null, null, null, "id DESC", limit.toString())
            .use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(cursorToLocationData(cursor))
                }
            }
        return list
    }

    fun getPendingLocations(): List<LocationData> {
        val list = mutableListOf<LocationData>()
        readableDatabase
            .query(TABLE_NAME, null, "uploaded = 0", null, null, null, "id ASC", "100")
            .use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(cursorToLocationData(cursor))
                }
            }
        return list
    }

    fun markAsUploaded(id: Long) {
        writableDatabase.execSQL(
            "UPDATE $TABLE_NAME SET uploaded = 1, server_time = ? WHERE id = ?",
            arrayOf(System.currentTimeMillis(), id)
        )
    }

    private fun cursorToLocationData(cursor: android.database.Cursor): LocationData {
        return LocationData(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            latitude = cursor.getDouble(cursor.getColumnIndexOrThrow("latitude")),
            longitude = cursor.getDouble(cursor.getColumnIndexOrThrow("longitude")),
            altitude = cursor.getDoubleOrNull("altitude"),
            accuracy = cursor.getFloatOrNull("accuracy"),
            speed = cursor.getFloatOrNull("speed"),
            bearing = cursor.getFloatOrNull("bearing"),
            provider = cursor.getStringOrNull("provider"),
            clientTime = cursor.getLong(cursor.getColumnIndexOrThrow("client_time")),
            uploaded = cursor.getInt(cursor.getColumnIndexOrThrow("uploaded")) == 1,
            serverTime = cursor.getLongOrNull("server_time")
        )
    }

    private fun android.database.Cursor.getDoubleOrNull(column: String): Double? {
        val idx = getColumnIndex(column)
        return if (idx >= 0 && !isNull(idx)) getDouble(idx) else null
    }

    private fun android.database.Cursor.getFloatOrNull(column: String): Float? {
        val idx = getColumnIndex(column)
        return if (idx >= 0 && !isNull(idx)) getFloat(idx) else null
    }

    private fun android.database.Cursor.getLongOrNull(column: String): Long? {
        val idx = getColumnIndex(column)
        return if (idx >= 0 && !isNull(idx)) getLong(idx) else null
    }

    private fun android.database.Cursor.getStringOrNull(column: String): String? {
        val idx = getColumnIndex(column)
        return if (idx >= 0 && !isNull(idx)) getString(idx) else null
    }
}
