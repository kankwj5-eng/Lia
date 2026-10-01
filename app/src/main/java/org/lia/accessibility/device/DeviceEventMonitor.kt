package org.lia.accessibility.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager

class DeviceEventMonitor(
    private val context: Context,
    private val onBatteryLow: (Int) -> Unit,
    private val onInternetChanged: (Boolean) -> Unit
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var started = false
    private var lastOnline: Boolean? = null
    private var lowBatteryAnnounced = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return

            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (level < 0 || scale <= 0) return

            val percent = level * 100 / scale
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

            if (percent <= LOW_BATTERY_PERCENT && !charging && !lowBatteryAnnounced) {
                lowBatteryAnnounced = true
                onBatteryLow(percent)
            } else if (percent > LOW_BATTERY_RESET_PERCENT || charging) {
                lowBatteryAnnounced = false
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateOnline(true)
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            updateOnline(
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            )
        }

        override fun onLost(network: Network) {
            updateOnline(false)
        }
    }

    fun start() {
        if (started) return
        started = true

        context.registerReceiver(
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        connectivity.registerDefaultNetworkCallback(networkCallback)

        val network = connectivity.activeNetwork
        val capabilities = network?.let(connectivity::getNetworkCapabilities)
        updateOnline(
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        )
    }

    fun stop() {
        if (!started) return
        started = false

        runCatching { context.unregisterReceiver(batteryReceiver) }
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
    }

    private fun updateOnline(online: Boolean) {
        if (lastOnline == online) return
        lastOnline = online
        onInternetChanged(online)
    }

    companion object {
        private const val LOW_BATTERY_PERCENT = 10
        private const val LOW_BATTERY_RESET_PERCENT = 15
    }
}
