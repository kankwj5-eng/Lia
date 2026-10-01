package org.lia.accessibility.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import org.lia.accessibility.device.DeviceEventMonitor
import org.lia.accessibility.device.HapticFeedback
import org.lia.accessibility.device.HapticSignal
import org.lia.accessibility.safety.HardwareShortcut
import org.lia.accessibility.safety.HardwareShortcutDetector
import org.lia.accessibility.security.ActionRisk
import org.lia.accessibility.system.AndroidSystemController
import org.lia.accessibility.system.SystemCommand
import org.lia.accessibility.system.SystemCommandResult
import org.lia.accessibility.vision.AccessibilityScreenshotProvider
import org.lia.accessibility.vision.ScreenOcrReader
import org.lia.accessibility.voice.VoiceVerification

class LiaAccessibilityService : AccessibilityService() {
    private val shortcuts = HardwareShortcutDetector()
    private lateinit var haptics: HapticFeedback
    private lateinit var androidController: AuthorizedAndroidController
    private lateinit var systemController: AndroidSystemController
    private lateinit var screenshotProvider: AccessibilityScreenshotProvider
    private lateinit var screenOcrReader: ScreenOcrReader
    private var deviceEvents: DeviceEventMonitor? = null

    @Volatile
    private var lastUiEventAtEpochMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()

        haptics = HapticFeedback(this)
        androidController = AuthorizedAndroidController(this)
        systemController = AndroidSystemController(this)
        screenshotProvider = AccessibilityScreenshotProvider(this)
        screenOcrReader = ScreenOcrReader()

        deviceEvents = DeviceEventMonitor(
            context = this,
            onBatteryLow = { percent ->
                haptics.signal(HapticSignal.ERROR)
                emitDeviceWarning(
                    "Tu batería tiene " + percent + " por ciento. Conecta el teléfono al cargador."
                )
            },
            onInternetChanged = { online ->
                if (!online) {
                    emitDeviceWarning(
                        "Lía detectó que el teléfono se quedó sin conexión a internet. " +
                            "Las funciones locales siguen disponibles."
                    )
                }
            }
        ).also { it.start() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) {
            lastUiEventAtEpochMs = System.currentTimeMillis()
        }
    }

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        when (shortcuts.onKeyEvent(event)) {
            HardwareShortcut.SOS -> {
                haptics.signal(HapticSignal.SOS)
                sendBroadcast(
                    Intent(ACTION_SOS_TRIGGERED)
                        .setPackage(packageName)
                        .putExtra(EXTRA_SOURCE, "volume_triple_press")
                )
            }

            HardwareShortcut.ACTIVATE_LIA -> {
                haptics.signal(HapticSignal.LISTENING)
                sendBroadcast(
                    Intent(ACTION_ACTIVATE_LIA)
                        .setPackage(packageName)
                        .putExtra(EXTRA_SOURCE, "bluetooth_or_headset_button")
                )
            }

            null -> Unit
        }

        // Observar sin consumir mantiene el comportamiento normal de volumen/media.
        return false
    }

    internal fun observeScreen(voice: VoiceVerification): ScreenObservationResult =
        androidController.observe(voice)

    internal fun readScreenVisually(
        voice: VoiceVerification,
        callback: (Result<String>) -> Unit
    ) {
        when (val observation = androidController.observe(voice)) {
            is ScreenObservationResult.Denied -> {
                callback(Result.failure(SecurityException(observation.reason)))
                return
            }

            is ScreenObservationResult.Allowed -> Unit
        }

        screenshotProvider.capture { screenshot ->
            screenshot
                .onSuccess { bitmap ->
                    screenOcrReader.read(bitmap, callback)
                }
                .onFailure { error ->
                    callback(Result.failure(error))
                }
        }
    }

    internal fun performAuthorizedAction(
        voice: VoiceVerification,
        action: AndroidUiAction,
        risk: ActionRisk = ActionRisk.ROUTINE,
        secondFactorSatisfied: Boolean = false,
        explicitConfirmation: Boolean = false
    ): UiExecutionResult =
        androidController.execute(
            voice = voice,
            action = action,
            risk = risk,
            secondFactorSatisfied = secondFactorSatisfied,
            explicitConfirmation = explicitConfirmation
        )

    internal fun performSystemCommand(
        voice: VoiceVerification,
        command: SystemCommand,
        secondFactorSatisfied: Boolean = false,
        explicitConfirmation: Boolean = false
    ): SystemCommandResult =
        systemController.execute(
            voice = voice,
            command = command,
            secondFactorSatisfied = secondFactorSatisfied,
            explicitConfirmation = explicitConfirmation
        )

    internal fun screenChangedSince(result: UiExecutionResult): Boolean =
        androidController.verifyStateChanged(result)

    internal fun lastUiEventAt(): Long = lastUiEventAtEpochMs

    internal fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    internal fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    internal fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    internal fun tap(x: Float, y: Float, durationMs: Long = 80L): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build(),
            null,
            null
        )
    }

    internal fun swipe(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long = 350L
    ): Boolean {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }

        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build(),
            null,
            null
        )
    }

    private fun emitDeviceWarning(message: String) {
        sendBroadcast(
            Intent(ACTION_DEVICE_WARNING)
                .setPackage(packageName)
                .putExtra(EXTRA_MESSAGE, message)
        )
    }

    override fun onDestroy() {
        deviceEvents?.stop()
        deviceEvents = null
        if (::screenOcrReader.isInitialized) {
            screenOcrReader.close()
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_SOS_TRIGGERED = "org.lia.accessibility.action.SOS_TRIGGERED"
        const val ACTION_ACTIVATE_LIA = "org.lia.accessibility.action.ACTIVATE_LIA"
        const val ACTION_DEVICE_WARNING = "org.lia.accessibility.action.DEVICE_WARNING"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_MESSAGE = "message"
    }
}
