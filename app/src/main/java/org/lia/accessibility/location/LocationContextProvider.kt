package org.lia.accessibility.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.Locale
import java.util.concurrent.Executors

data class LocationContext(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val address: String?
)

class LocationContextProvider(private val context: Context) {
    private val fused = LocationServices.getFusedLocationProviderClient(context)
    private val geocoder = Geocoder(context, Locale.getDefault())
    private val executor = Executors.newSingleThreadExecutor()

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun currentLocation(callback: (Result<LocationContext>) -> Unit) {
        if (!hasLocationPermission()) {
            callback(Result.failure(SecurityException("Falta permiso de ubicación")))
            return
        }

        val token = CancellationTokenSource()
        fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token)
            .addOnSuccessListener { location ->
                if (location == null) {
                    callback(Result.failure(IllegalStateException("No hay una ubicación disponible")))
                    return@addOnSuccessListener
                }
                reverseGeocode(location.latitude, location.longitude) { address ->
                    callback(
                        Result.success(
                            LocationContext(
                                location.latitude,
                                location.longitude,
                                location.accuracy,
                                address
                            )
                        )
                    )
                }
            }
            .addOnFailureListener { callback(Result.failure(it)) }
    }

    private fun reverseGeocode(lat: Double, lon: Double, callback: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            geocoder.getFromLocation(lat, lon, 1) { addresses ->
                callback(addresses.firstOrNull()?.getAddressLine(0))
            }
        } else {
            executor.execute {
                @Suppress("DEPRECATION")
                val address = runCatching {
                    geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()?.getAddressLine(0)
                }.getOrNull()
                callback(address)
            }
        }
    }
}
