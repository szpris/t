package cn.nasi.tracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, "tracker.db", null, 1) {

    companion object {
        private const val TABLE = "location_records"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE $TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                accuracy REAL NOT NULL,
                timestamp INTEGER NOT NULL,
                uploaded INTEGER NOT NULL DEFAULT 0
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun insert(record: LocationRecord): Long {
        val cv = ContentValues().apply {
            put("latitude", record.latitude)
            put("longitude", record.longitude)
            put("accuracy", record.accuracy)
            put("timestamp", record.timestamp)
            put("uploaded", if (record.uploaded) 1 else 0)
        }
        return writableDatabase.insert(TABLE, null, cv)
    }

    fun markUploaded(id: Long) {
        val cv = ContentValues().apply { put("uploaded", 1) }
        writableDatabase.update(TABLE, cv, "id=?", arrayOf(id.toString()))
    }

    fun getPending(): List<LocationRecord> {
        val list = mutableListOf<LocationRecord>()
        val cursor = readableDatabase.query(
            TABLE, null, "uploaded=0", null, null, null, "timestamp ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(fromCursor(it))
            }
        }
        return list
    }

    fun getRecent(limit: Int = 20): List<LocationRecord> {
        val list = mutableListOf<LocationRecord>()
        val cursor = readableDatabase.query(
            TABLE, null, null, null, null, null, "timestamp DESC", limit.toString()
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(fromCursor(it))
            }
        }
        return list
    }

    private fun fromCursor(c: android.database.Cursor) = LocationRecord(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        latitude = c.getDouble(c.getColumnIndexOrThrow("latitude")),
        longitude = c.getDouble(c.getColumnIndexOrThrow("longitude")),
        accuracy = c.getFloat(c.getColumnIndexOrThrow("accuracy")),
        timestamp = c.getLong(c.getColumnIndexOrThrow("timestamp")),
        uploaded = c.getInt(c.getColumnIndexOrThrow("uploaded")) == 1
    )
}
