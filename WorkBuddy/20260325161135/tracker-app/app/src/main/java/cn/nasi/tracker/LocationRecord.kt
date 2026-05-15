package cn.nasi.tracker

import java.io.Serializable

data class LocationRecord(
    val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val timestamp: Long,  // Unix毫秒
    val uploaded: Boolean = false
) : Serializable
