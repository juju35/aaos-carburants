package me.jstephan.carburants.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat

fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** The car's position from LocationManager (fused, else GPS, else network), on the main thread. */
class LocationTracker(context: Context, private val onLocation: (Location) -> Unit) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(LocationManager::class.java)
    private val listener = LocationListener { onLocation(it) }
    private var started = false

    /** False when the permission is missing or no provider is enabled. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (started) return true
        if (!hasLocationPermission(appContext)) return false
        val providers = providers()
        val provider = providers.firstOrNull() ?: return false

        providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }
            ?.let(onLocation)
        manager.getCurrentLocation(provider, null, appContext.mainExecutor) { loc -> loc?.let(onLocation) }
        manager.requestLocationUpdates(provider, UPDATE_MS, UPDATE_METERS, listener, Looper.getMainLooper())
        started = true
        return true
    }

    fun stop() {
        if (!started) return
        manager.removeUpdates(listener)
        started = false
    }

    private fun providers(): List<String> =
        listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

    private companion object {
        const val UPDATE_MS = 15_000L
        const val UPDATE_METERS = 100f
    }
}
