package org.lia.accessibility.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.view.accessibility.AccessibilityEvent
import org.lia.accessibility.LiaPreferences
import org.lia.accessibility.MainActivity
import org.lia.accessibility.R
import org.lia.accessibility.agent.orchestration.LiaAgentCoordinator
import org.lia.accessibility.agent.runtime.LocalAgentOutcome
import org.lia.accessibility.agent.PhoneState
import org.lia.accessibility.agent.PhoneStateProvider
import org.lia.accessibility.conversation.ConversationSpeaker
import org.lia.accessibility.conversation.ConversationStore
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
    private lateinit var phoneStateProvider: PhoneStateProvider
    private lateinit var agentCoordinator: LiaAgentCoordinator
    private lateinit var conversationStore: ConversationStore
    private var deviceEvents: DeviceEventMonitor? = null
    private var bubbleView: View? = null
    private var bubbleWindowManager: WindowManager? = null
    private var bubbleLayoutParams: WindowManager.LayoutParams? = null

    @Volatile
    private var lastUiEventAtEpochMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        LiaAccessibilityBridge.attach(this)

        haptics = HapticFeedback(this)
        androidController = AuthorizedAndroidController(this)
        systemController = AndroidSystemController(this)
        screenshotProvider = AccessibilityScreenshotProvider(this)
        screenOcrReader = ScreenOcrReader()
        phoneStateProvider = PhoneStateProvider(this)
        agentCoordinator = LiaAgentCoordinator(this)
        conversationStore = ConversationStore(this)
        setFloatingBubbleEnabled(LiaPreferences(this).bubbleEnabled)

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

    internal fun capturePhoneState(): PhoneState =
        phoneStateProvider.capture()

    internal fun startLocalAgentGoal(
        goal: String,
        voice: VoiceVerification
    ) {
        if (!::agentCoordinator.isInitialized) {
            emitAgentState(
                status = "failed",
                message = "El coordinador local todavía no está disponible."
            )
            return
        }

        conversationStore.add(ConversationSpeaker.USER, goal)
        val conversationContext = conversationStore.contextForAgent(goal)

        agentCoordinator.start(
            goal = goal,
            voice = voice,
            conversationContext = conversationContext,
            onAgentSelected = { role ->
                emitAgentState(
                    status = "agent_selected",
                    message = "Agente " + role.displayName + " activo.",
                    agentRole = role.displayName
                )
            }
        ) { outcome ->
            when (outcome) {
                is LocalAgentOutcome.Completed -> {
                    conversationStore.add(ConversationSpeaker.LIA, outcome.result)
                    performSystemCommand(
                        voice = voice,
                        command = SystemCommand.Speak(outcome.result)
                    )
                    emitAgentState(
                        status = "completed",
                        message = outcome.result
                    )
                }

                is LocalAgentOutcome.NeedsAuthorization -> {
                    conversationStore.add(ConversationSpeaker.LIA, outcome.reason)
                    performSystemCommand(
                        voice = voice,
                        command = SystemCommand.Speak(outcome.reason)
                    )
                    emitAgentState(
                        status = "authorization_required",
                        message = outcome.reason,
                        toolName = outcome.call.name,
                        risk = outcome.risk.name
                    )
                }

                is LocalAgentOutcome.Failed -> {
                    performSystemCommand(
                        voice = voice,
                        command = SystemCommand.Speak(outcome.reason)
                    )
                    emitAgentState(
                        status = "failed",
                        message = outcome.reason
                    )
                }

                is LocalAgentOutcome.Cancelled -> {
                    emitAgentState(
                        status = "cancelled",
                        message = "Tarea cancelada."
                    )
                }
            }
        }
    }

    internal fun cancelLocalAgentGoal() {
        if (::agentCoordinator.isInitialized) {
            agentCoordinator.cancel()
        }
    }

    internal fun setFloatingBubbleEnabled(enabled: Boolean) {
        if (enabled) {
            showFloatingBubble()
        } else {
            hideFloatingBubble()
        }
    }

    private fun showFloatingBubble() {
        if (bubbleView != null) return

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val size = dp(60)
        val margin = dp(14)

        val button = ImageButton(this).apply {
            contentDescription = "Hablar con Lía"
            setImageResource(R.drawable.ic_lia_spark)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            elevation = dp(10).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(168, 133, 255))
                setStroke(dp(1), Color.argb(90, 255, 255, 255))
            }
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - margin
            y = resources.displayMetrics.heightPixels / 3
        }

        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var dragged = false
        val dragThreshold = dp(8)

        button.setOnClickListener {
            launchVoiceFromBubble()
        }

        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downX = event.rawX
                    downY = event.rawY
                    dragged = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > dragThreshold ||
                        kotlin.math.abs(dy) > dragThreshold
                    ) {
                        dragged = true
                    }
                    params.x = (startX + dx).coerceAtLeast(0)
                    params.y = (startY + dy).coerceAtLeast(0)
                    runCatching {
                        windowManager.updateViewLayout(view, params)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragged) {
                        view.performClick()
                    }
                    true
                }

                else -> false
            }
        }

        runCatching {
            windowManager.addView(button, params)
        }.onSuccess {
            bubbleWindowManager = windowManager
            bubbleLayoutParams = params
            bubbleView = button
        }
    }

    private fun hideFloatingBubble() {
        val view = bubbleView ?: return
        runCatching {
            bubbleWindowManager?.removeView(view)
        }
        bubbleView = null
        bubbleLayoutParams = null
        bubbleWindowManager = null
    }

    private fun launchVoiceFromBubble() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                .putExtra(MainActivity.EXTRA_START_VOICE_COMMAND, true)
        )
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

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

    private fun emitAgentState(
        status: String,
        message: String,
        toolName: String? = null,
        risk: String? = null,
        agentRole: String? = null
    ) {
        sendBroadcast(
            Intent(ACTION_AGENT_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_AGENT_STATUS, status)
                .putExtra(EXTRA_MESSAGE, message)
                .apply {
                    toolName?.let { putExtra(EXTRA_TOOL_NAME, it) }
                    risk?.let { putExtra(EXTRA_RISK, it) }
                    agentRole?.let { putExtra(EXTRA_AGENT_ROLE, it) }
                }
        )
    }

    override fun onDestroy() {
        hideFloatingBubble()
        LiaAccessibilityBridge.detach(this)
        deviceEvents?.stop()
        deviceEvents = null
        if (::agentCoordinator.isInitialized) {
            agentCoordinator.close()
        }
        if (::screenOcrReader.isInitialized) {
            screenOcrReader.close()
        }
        if (::systemController.isInitialized) {
            systemController.close()
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_SOS_TRIGGERED = "org.lia.accessibility.action.SOS_TRIGGERED"
        const val ACTION_ACTIVATE_LIA = "org.lia.accessibility.action.ACTIVATE_LIA"
        const val ACTION_DEVICE_WARNING = "org.lia.accessibility.action.DEVICE_WARNING"
        const val ACTION_AGENT_STATE = "org.lia.accessibility.action.AGENT_STATE"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_AGENT_STATUS = "agent_status"
        const val EXTRA_TOOL_NAME = "tool_name"
        const val EXTRA_RISK = "risk"
        const val EXTRA_AGENT_ROLE = "agent_role"
    }
}
