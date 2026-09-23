package it.vallequaranta.segnale.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper

/** Posizione GPS pura (senza Google Play Services), un aggiornamento al secondo. */
class GpsTracker(context: Context, private val onLocation: (Location) -> Unit) : LocationListener {
    private val lm = context.getSystemService(LocationManager::class.java)

    var last: Location? = null
        private set

    val enabled: Boolean get() = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)

    @SuppressLint("MissingPermission")
    fun start() {
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (e: SecurityException) {
            // permesso non concesso: la UI lo segnala
        }
    }

    fun stop() = lm.removeUpdates(this)

    override fun onLocationChanged(location: Location) {
        last = location
        onLocation(location)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
