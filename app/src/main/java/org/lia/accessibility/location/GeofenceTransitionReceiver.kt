package org.lia.accessibility.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return

        val transition = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "enter"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "exit"
            Geofence.GEOFENCE_TRANSITION_DWELL -> "dwell"
            else -> "unknown"
        }

        val ids = event.triggeringGeofences?.map { it.requestId }.orEmpty()

        context.sendBroadcast(
            Intent(ACTION_GEOFENCE_EVENT)
                .setPackage(context.packageName)
                .putExtra(EXTRA_TRANSITION, transition)
                .putStringArrayListExtra(EXTRA_IDS, ArrayList(ids))
        )
    }

    companion object {
        const val ACTION_GEOFENCE_EVENT =
            "org.lia.accessibility.action.GEOFENCE_EVENT"
        const val EXTRA_TRANSITION = "transition"
        const val EXTRA_IDS = "ids"
    }
}
