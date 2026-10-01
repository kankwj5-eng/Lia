package org.lia.accessibility.location

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

data class SafePlace(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = 150f
)

class GeofenceCoordinator(private val context: Context) {
    private val client = LocationServices.getGeofencingClient(context)

    fun hasRequiredPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val background = Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        return fine && background
    }

    @Suppress("MissingPermission")
    fun addSafePlace(
        place: SafePlace,
        onSuccess: () -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        if (!hasRequiredPermission()) {
            onFailure(SecurityException("Faltan permisos de ubicación para geocercas"))
            return
        }

        val geofence = Geofence.Builder()
            .setRequestId(place.id)
            .setCircularRegion(place.latitude, place.longitude, place.radiusMeters)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(
                Geofence.GEOFENCE_TRANSITION_ENTER or
                    Geofence.GEOFENCE_TRANSITION_EXIT or
                    Geofence.GEOFENCE_TRANSITION_DWELL
            )
            .setLoiteringDelay(60_000)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(
                GeofencingRequest.INITIAL_TRIGGER_ENTER or
                    GeofencingRequest.INITIAL_TRIGGER_DWELL
            )
            .addGeofence(geofence)
            .build()

        client.addGeofences(request, pendingIntent())
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener(onFailure)
    }

    fun removeSafePlace(id: String, onComplete: (Boolean) -> Unit) {
        client.removeGeofences(listOf(id))
            .addOnSuccessListener { onComplete(true) }
            .addOnFailureListener { onComplete(false) }
    }

    private fun pendingIntent(): PendingIntent {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        flags = flags or if (Build.VERSION.SDK_INT >= 31) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }

        val intent = Intent(context, GeofenceTransitionReceiver::class.java)
        return PendingIntent.getBroadcast(context, 4010, intent, flags)
    }
}
