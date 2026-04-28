package com.meshchat.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager

class LocationProvider(context: Context) {

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission")
    fun getLastLocation(): Location? = try {
        lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
    } catch (_: Exception) { null }

    fun geohash(lat: Double, lon: Double, precision: Int = 6): String {
        val base32 = "0123456789bcdefghjkmnpqrstuvwxyz"
        var minLat = -90.0;  var maxLat = 90.0
        var minLon = -180.0; var maxLon = 180.0
        val sb = StringBuilder()
        var bits = 0; var bitCount = 0; var isEven = true
        while (sb.length < precision) {
            val mid: Double
            if (isEven) {
                mid = (minLon + maxLon) / 2
                if (lon >= mid) { bits = (bits shl 1) or 1; minLon = mid } else { bits = bits shl 1; maxLon = mid }
            } else {
                mid = (minLat + maxLat) / 2
                if (lat >= mid) { bits = (bits shl 1) or 1; minLat = mid } else { bits = bits shl 1; maxLat = mid }
            }
            isEven = !isEven
            if (++bitCount == 5) { sb.append(base32[bits]); bits = 0; bitCount = 0 }
        }
        return sb.toString()
    }
}
