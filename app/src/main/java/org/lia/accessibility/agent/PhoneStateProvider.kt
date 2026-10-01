package org.lia.accessibility.agent

import android.app.KeyguardManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.view.accessibility.AccessibilityWindowInfo
import org.lia.accessibility.accessibility.AccessibilityTreeReader
import org.lia.accessibility.accessibility.LiaAccessibilityService
import org.lia.accessibility.accessibility.ScreenSnapshot

data class PhoneState(
    val foregroundPackage: String?,
    val windowTitle: String?,
    val keyboardVisible: Boolean,
    val orientation: String,
    val screenInteractive: Boolean,
    val deviceLocked: Boolean,
    val internetValidated: Boolean,
    val batteryPercent: Int?
) {
    fun compactText(): String = buildString {
        append("app=").append(foregroundPackage ?: "desconocida")
        append("; ventana=").append(windowTitle ?: "sin título")
        append("; teclado=").append(if (keyboardVisible) "visible" else "oculto")
        append("; orientación=").append(orientation)
        append("; pantalla=").append(if (screenInteractive) "activa" else "apagada")
        append("; bloqueo=").append(if (deviceLocked) "sí" else "no")
        append("; internet=").append(if (internetValidated) "conectado" else "sin validar")
        batteryPercent?.let { append("; batería=").append(it).append('%') }
    }
}

class PhoneStateProvider(
    private val service: LiaAccessibilityService
) {
    private val treeReader = AccessibilityTreeReader()
    private val power = service.getSystemService(PowerManager::class.java)
    private val keyguard = service.getSystemService(KeyguardManager::class.java)
    private val connectivity = service.getSystemService(ConnectivityManager::class.java)
    private val battery = service.getSystemService(BatteryManager::class.java)

    fun capture(screen: ScreenSnapshot? = null): PhoneState {
        val activeScreen = screen ?: service.rootInActiveWindow?.let { root ->
            treeReader.capture(
                root,
                service.windows.firstOrNull { it.isActive }
            )
        }

        return PhoneState(
            foregroundPackage = activeScreen?.packageName,
            windowTitle = activeScreen?.windowTitle,
            keyboardVisible = service.windows.any {
                it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
            },
            orientation = orientationName(service.resources.configuration.orientation),
            screenInteractive = power?.isInteractive == true,
            deviceLocked = keyguard?.isDeviceLocked == true,
            internetValidated = hasValidatedInternet(),
            batteryPercent = batteryPercent()
        )
    }

    private fun hasValidatedInternet(): Boolean {
        val manager = connectivity ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun batteryPercent(): Int? {
        val manager = battery ?: return null
        val value = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return value.takeIf { it in 0..100 }
    }

    private fun orientationName(value: Int): String =
        when (value) {
            Configuration.ORIENTATION_LANDSCAPE -> "horizontal"
            Configuration.ORIENTATION_PORTRAIT -> "vertical"
            else -> "desconocida"
        }
}
