package org.lia.accessibility

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import org.lia.accessibility.accessibility.AccessibilityBridgeResult
import org.lia.accessibility.accessibility.LiaAccessibilityBridge
import org.lia.accessibility.accessibility.LiaAccessibilityService
import org.lia.accessibility.ai.PlannerModelStore
import org.lia.accessibility.notifications.LiaNotificationListenerService
import org.lia.accessibility.vision.WorldVisionActivity
import org.lia.accessibility.voice.OwnerVoiceAuthenticator
import org.lia.accessibility.voice.SherpaSpeakerEngine
import org.lia.accessibility.voice.SherpaWhisperCommandTranscriber
import org.lia.accessibility.voice.VoiceModelProvisioner
import org.lia.accessibility.voice.VoiceProfileStore
import org.lia.accessibility.voice.VerifiedVoiceCommandProcessor
import org.lia.accessibility.voice.VerifiedVoiceCommandResult
import org.lia.accessibility.voice.VoiceVerification
import org.lia.accessibility.voice.VoiceSampleRecorder
import org.lia.accessibility.voice.WhisperCommandModelProvisioner
import org.lia.accessibility.voice.asVoiceIdentityVerifier
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var plannerStatusText: TextView
    private lateinit var commandStatusText: TextView
    private lateinit var assistantStatusText: TextView
    private lateinit var chatHistoryText: TextView
    private lateinit var chatInput: EditText
    private lateinit var recordButton: Button
    private lateinit var saveButton: Button
    private lateinit var testButton: Button
    private lateinit var importPlannerButton: Button
    private lateinit var prepareCommandSpeechButton: Button
    private lateinit var talkToLiaButton: Button
    private lateinit var cancelAgentButton: Button
    private lateinit var assistantRoleButton: Button
    private lateinit var sendChatButton: Button
    private lateinit var voiceProtectionSwitch: SwitchMaterial
    private lateinit var bubbleSwitch: SwitchMaterial
    private lateinit var voiceControlsGroup: View
    private lateinit var preferences: LiaPreferences

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var recorder: VoiceSampleRecorder
    private val samples = mutableListOf<FloatArray>()

    private lateinit var provisioner: VoiceModelProvisioner
    private lateinit var store: VoiceProfileStore
    private lateinit var plannerModelStore: PlannerModelStore
    private lateinit var commandModelProvisioner: WhisperCommandModelProvisioner
    private var engine: SherpaSpeakerEngine? = null
    private var commandTranscriber: SherpaWhisperCommandTranscriber? = null
    private var resumeVoiceCommandAfterMicPermission = false
    private var continueAccessibilitySetupOnResume = false
    private var continueNotificationSetupOnResume = false

    private val phrases = listOf(
        "Hola Lía, esta es mi voz.",
        "Lía, ayúdame cuando te necesite.",
        "Esta es mi voz en este dispositivo."
    )

    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && resumeVoiceCommandAfterMicPermission) {
                resumeVoiceCommandAfterMicPermission = false
                talkToLia()
            } else if (granted) {
                status("Micrófono autorizado. Pulsa registrar muestra otra vez.")
            } else {
                resumeVoiceCommandAfterMicPermission = false
                status("Lía necesita acceso al micrófono para registrar tu identidad de voz.")
                commandStatus("Sin permiso de micrófono no puedo escuchar órdenes.")
            }
        }

    private val assistantRoleRequest =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshAssistantStatus()
        refreshPermissionStatus()
        }

    private val plannerModelPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                importPlannerModel(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preferences = LiaPreferences(this)
        provisioner = VoiceModelProvisioner(this)
        store = VoiceProfileStore(this)
        recorder = VoiceSampleRecorder(this)
        plannerModelStore = PlannerModelStore(this)
        commandModelProvisioner = WhisperCommandModelProvisioner(this)

        statusText = findViewById(R.id.statusText)
        plannerStatusText = findViewById(R.id.plannerStatusText)
        commandStatusText = findViewById(R.id.commandStatusText)
        assistantStatusText = findViewById(R.id.assistantStatusText)
        chatHistoryText = findViewById(R.id.chatHistoryText)
        chatInput = findViewById(R.id.chatInput)
        recordButton = findViewById(R.id.recordSampleButton)
        saveButton = findViewById(R.id.saveVoiceButton)
        testButton = findViewById(R.id.testVoiceButton)
        importPlannerButton = findViewById(R.id.importPlannerButton)
        prepareCommandSpeechButton = findViewById(R.id.prepareCommandSpeechButton)
        talkToLiaButton = findViewById(R.id.talkToLiaButton)
        cancelAgentButton = findViewById(R.id.cancelAgentButton)
        assistantRoleButton = findViewById(R.id.assistantRoleButton)
        sendChatButton = findViewById(R.id.sendChatButton)
        voiceProtectionSwitch = findViewById(R.id.voiceProtectionSwitch)
        bubbleSwitch = findViewById(R.id.bubbleSwitch)
        voiceControlsGroup = findViewById(R.id.voiceControlsGroup)

        voiceProtectionSwitch.isChecked = preferences.voiceProtectionEnabled
        bubbleSwitch.isChecked = preferences.bubbleEnabled
        refreshVoiceProtectionUi()

        voiceProtectionSwitch.setOnCheckedChangeListener { _, enabled ->
            preferences.voiceProtectionEnabled = enabled
            refreshVoiceProtectionUi()
            if (enabled) {
                status(
                    if (store.hasProfile()) {
                        "Protección por voz activa. Tu perfil ya está listo."
                    } else {
                        "Protección por voz activa. Registra dos frases para crear tu perfil."
                    }
                )
            } else {
                commandStatus("Protección por voz desactivada. Lía puede escucharte sin comprobar identidad.")
            }
        }

        bubbleSwitch.setOnCheckedChangeListener { _, enabled ->
            preferences.bubbleEnabled = enabled
            LiaAccessibilityBridge.setBubbleEnabled(enabled)
        }

        recordButton.setOnClickListener { recordSample() }
        saveButton.setOnClickListener { saveProfile() }
        testButton.setOnClickListener { testVoice() }
        importPlannerButton.setOnClickListener {
            plannerModelPicker.launch(
                arrayOf(
                    "application/octet-stream",
                    "application/zip",
                    "*/*"
                )
            )
        }

        prepareCommandSpeechButton.setOnClickListener {
            prepareCommandSpeechModel()
        }

        talkToLiaButton.setOnClickListener {
            talkToLia()
        }

        sendChatButton.setOnClickListener {
            sendTypedGoal()
        }

        cancelAgentButton.setOnClickListener {
            val cancelled = LiaAccessibilityBridge.cancel()
            commandStatus(
                if (cancelled) {
                    "Solicitud de cancelación enviada."
                } else {
                    "El servicio de accesibilidad de Lía no está conectado."
                }
            )
        }

        assistantRoleButton.setOnClickListener {
            requestAssistantRole()
        }

        findViewById<Button>(R.id.worldVisionButton).setOnClickListener {
            startActivity(Intent(this, WorldVisionActivity::class.java))
        }

        findViewById<Button>(R.id.accessibilitySettingsButton).setOnClickListener {
            openAccessibilitySetup()
        }

        findViewById<Button>(R.id.notificationSettingsButton).setOnClickListener {
            openNotificationAccessSetup()
        }

        refreshButtons()
        refreshPlannerStatus()
        refreshCommandStatus()
        refreshAssistantStatus()

        if (preferences.voiceProtectionEnabled) {
            status(
                if (store.hasProfile()) {
                    "Tu perfil de voz está listo. Puedes probarlo o volver a registrarlo."
                } else {
                    "Con dos frases basta. La primera vez Lía preparará el reconocimiento local."
                }
            )
        }

        handleAssistantLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAssistantLaunchIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::assistantStatusText.isInitialized) {
            refreshAssistantStatus()
            if (::bubbleSwitch.isInitialized) {
                bubbleSwitch.isChecked = preferences.bubbleEnabled
            }
        }

        if (continueAccessibilitySetupOnResume) {
            continueAccessibilitySetupOnResume = false
            AlertDialog.Builder(this)
                .setTitle("Ahora activa Lía")
                .setMessage(
                    "Si ya tocaste ⋮ y elegiste “Permitir ajustes restringidos”, " +
                        "abre Accesibilidad y activa “Lía — Asistente de accesibilidad”."
                )
                .setPositiveButton("Abrir Accesibilidad") { _, _ ->
                    openAccessibilitySettings()
                }
                .setNegativeButton("Todavía no", null)
                .show()
        }

        if (continueNotificationSetupOnResume) {
            continueNotificationSetupOnResume = false
            AlertDialog.Builder(this)
                .setTitle("Ahora permite las notificaciones")
                .setMessage(
                    "Si ya elegiste “Permitir ajustes restringidos” en la información de Lía, " +
                        "abre el acceso a notificaciones y activa Lía."
                )
                .setPositiveButton("Abrir acceso") { _, _ ->
                    openNotificationSettings()
                }
                .setNegativeButton("Todavía no", null)
                .show()
        }

        refreshPermissionStatus()
    }

    private fun openAccessibilitySetup() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            openAccessibilitySettings()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Android bloqueó temporalmente este permiso")
            .setMessage(
                "Como Lía se instaló desde un APK, Android 13 o posterior puede " +
                    "bloquear el acceso de accesibilidad hasta que tú lo autorices.\n\n" +
                    "1. Abre la información de Lía.\n" +
                    "2. Toca ⋮ arriba a la derecha.\n" +
                    "3. Elige “Permitir ajustes restringidos”.\n" +
                    "4. Vuelve a Lía y activa su servicio de accesibilidad."
            )
            .setPositiveButton("Abrir información de Lía") { _, _ ->
                continueAccessibilitySetupOnResume = true
                openAppDetails()
            }
            .setNeutralButton("Ya lo permití") { _, _ ->
                openAccessibilitySettings()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun openAppDetails() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        )
        runCatching { startActivity(intent) }
            .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun openAccessibilitySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.onFailure {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun openNotificationAccessSetup() {
        if (isNotificationAccessGranted()) {
            commandStatus("El acceso a notificaciones ya está activo.")
            openNotificationSettings()
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            openNotificationSettings()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Android protege este acceso")
            .setMessage(
                "En apps instaladas desde un APK, Android puede ocultar el interruptor hasta que " +
                    "autorices los ajustes restringidos. Abre la información de Lía, toca ⋮ y elige " +
                    "“Permitir ajustes restringidos”. Después vuelve y activa el acceso a notificaciones."
            )
            .setPositiveButton("Abrir información de Lía") { _, _ ->
                continueNotificationSetupOnResume = true
                openAppDetails()
            }
            .setNeutralButton("Ya lo permití") { _, _ ->
                openNotificationSettings()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun openNotificationSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }.onFailure {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun isNotificationAccessGranted(): Boolean {
        val manager = getSystemService(NotificationManager::class.java) ?: return false
        return manager.isNotificationListenerAccessGranted(
            ComponentName(this, LiaNotificationListenerService::class.java)
        )
    }

    private fun refreshPermissionStatus() {
        if (!::assistantStatusText.isInitialized) return

        val notificationButton = findViewById<Button>(R.id.notificationSettingsButton)
        notificationButton.text = if (isNotificationAccessGranted()) {
            "Notificaciones activas"
        } else {
            "Activar lectura de notificaciones"
        }
    }

    private fun sendTypedGoal() {
        val goal = chatInput.text?.toString()?.trim().orEmpty()
        if (goal.isBlank()) {
            commandStatus("Escribe lo que quieres que haga Lía.")
            return
        }
        if (!plannerModelStore.isInstalled()) {
            commandStatus("Importa primero un cerebro local GGUF o LiteRT-LM.")
            return
        }
        if (!LiaAccessibilityBridge.isConnected()) {
            commandStatus("Activa primero el control de Android para que Lía pueda actuar.")
            return
        }

        appendChatLine("Tú", goal)
        chatInput.setText("")
        startTranscriptGoal(
            transcript = goal,
            verification = VoiceVerification(
                matched = true,
                score = 1f,
                threshold = 0f,
                message = "Orden escrita directamente dentro de Lía."
            )
        )
    }

    private fun appendChatLine(author: String, message: String) {
        if (!::chatHistoryText.isInitialized || message.isBlank()) return
        val current = chatHistoryText.text?.toString().orEmpty()
        val next = if (current.isBlank()) {
            "$author\n$message"
        } else {
            "$current\n\n$author\n$message"
        }
        chatHistoryText.text = next.takeLast(6_000)
    }

    private val agentStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != LiaAccessibilityService.ACTION_AGENT_STATE) return
            val status = intent.getStringExtra(LiaAccessibilityService.EXTRA_AGENT_STATUS).orEmpty()
            val message = intent.getStringExtra(LiaAccessibilityService.EXTRA_MESSAGE).orEmpty()
            if (message.isNotBlank()) {
                appendChatLine(
                    when (status) {
                        "completed" -> "Lía"
                        "authorization_required" -> "Lía · autorización"
                        "failed" -> "Lía · error"
                        "cancelled" -> "Lía"
                        else -> "Lía · agente"
                    },
                    message
                )
            }
            commandStatus(message.ifBlank { "El agente actualizó su estado." })
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            agentStateReceiver,
            IntentFilter(LiaAccessibilityService.ACTION_AGENT_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        runCatching { unregisterReceiver(agentStateReceiver) }
        super.onStop()
    }

    private fun handleAssistantLaunchIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_START_VOICE_COMMAND, false) != true) {
            return
        }

        intent.removeExtra(EXTRA_START_VOICE_COMMAND)
        commandStatus("Lía fue activada como asistente. Preparando escucha…")
        talkToLia()
    }

    private fun requestAssistantRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)

            if (roleManager?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) != true) {
                assistantStatus("Android no ofrece el rol de asistente en este dispositivo.")
                return
            }

            if (roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
                assistantStatus("Lía ya es el asistente del sistema.")
                return
            }

            assistantRoleRequest.launch(
                roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
            )
        } else {
            runCatching {
                startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            }.onFailure {
                assistantStatus("Abre los ajustes de asistencia de Android para elegir Lía.")
            }
        }
    }

    private fun refreshAssistantStatus() {
        val assistantActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        } else {
            false
        }
        val controlActive = LiaAccessibilityBridge.isConnected()

        assistantStatus(
            when {
                controlActive && assistantActive ->
                    "Control de Android activo · Lía también es tu asistente del sistema."
                controlActive ->
                    "Control de Android activo. El rol de asistente del sistema es opcional."
                else ->
                    "Falta activar el control de Android para que Lía pueda actuar dentro de otras apps."
            }
        )

        assistantRoleButton.text =
            if (assistantActive) {
                "Asistente del sistema activo"
            } else {
                "Usar Lía como asistente"
            }

        findViewById<Button>(R.id.accessibilitySettingsButton).text =
            if (controlActive) {
                "Control de Android activo"
            } else {
                "Activar control de Android"
            }
    }

    private fun prepareCommandSpeechModel() {
        prepareCommandSpeechButton.isEnabled = false
        talkToLiaButton.isEnabled = false
        commandStatus("Preparando reconocimiento de órdenes offline…")

        worker.execute {
            val result = runCatching {
                commandModelProvisioner.install { progress ->
                    runOnUiThread {
                        commandStatus(
                            "Descargando reconocimiento de órdenes: " +
                                progress.percent +
                                " %."
                        )
                    }
                }
            }

            runOnUiThread {
                result.onSuccess {
                    commandTranscriber?.close()
                    commandTranscriber = null
                    commandStatus(
                        "Reconocimiento de órdenes offline listo para español."
                    )
                }.onFailure { error ->
                    commandStatus(
                        "No pude preparar el reconocimiento de órdenes: " +
                            (error.message ?: "error desconocido")
                    )
                }

                prepareCommandSpeechButton.isEnabled = true
                talkToLiaButton.isEnabled = commandModelProvisioner.isInstalled()
            }
        }
    }

    private fun talkToLia() {
        if (!ensureMicrophonePermission(resumeVoiceCommand = true)) return

        val voiceProtection = preferences.voiceProtectionEnabled

        if (voiceProtection && !store.hasProfile()) {
            commandStatus("La protección por voz está activa. Registra dos frases o desactívala en la sección Voz.")
            return
        }

        if (voiceProtection && !provisioner.isInstalled()) {
            commandStatus("Falta preparar el reconocimiento de identidad de voz.")
            return
        }

        if (!commandModelProvisioner.isInstalled()) {
            commandStatus("Prepara primero la escucha offline. Solo se descarga una vez.")
            return
        }

        if (!plannerModelStore.isInstalled()) {
            commandStatus("Importa primero tu modelo local GGUF o LiteRT-LM.")
            return
        }

        if (!LiaAccessibilityBridge.isConnected()) {
            commandStatus("Activa el control de Android de Lía para que pueda ayudarte dentro de otras apps.")
            return
        }

        setCommandBusy(true)
        commandStatus("Te escucho… habla con naturalidad.")

        worker.execute {
            val audioResult = runCatching {
                recorder.record(COMMAND_DURATION_MS)
            }

            audioResult.onFailure { error ->
                runOnUiThread {
                    commandStatus(
                        "No pude escucharte: " +
                            (error.message ?: "inténtalo otra vez")
                    )
                    setCommandBusy(false)
                }
                return@execute
            }

            val audio = audioResult.getOrThrow()

            try {
                if (voiceProtection) {
                    val result = runCatching {
                        VerifiedVoiceCommandProcessor(
                            verifier = authenticator().asVoiceIdentityVerifier(),
                            speechToText = commandTranscriber()
                        ).process(audio)
                    }

                    runOnUiThread {
                        result.onSuccess(::handleVerifiedCommand)
                            .onFailure { error ->
                                commandStatus(
                                    "No pude procesar lo que dijiste: " +
                                        (error.message ?: "inténtalo otra vez")
                                )
                            }
                        setCommandBusy(false)
                    }
                } else {
                    val result = runCatching {
                        commandTranscriber().transcribe(audio)
                    }

                    runOnUiThread {
                        result.onSuccess { transcript ->
                            if (transcript.text.isBlank()) {
                                commandStatus("No alcancé a entenderte. Toca Hablar e inténtalo otra vez.")
                            } else {
                                startTranscriptGoal(
                                    transcript.text,
                                    VoiceVerification(
                                        matched = true,
                                        score = 1f,
                                        threshold = 0f,
                                        message = "Protección por voz desactivada por la persona usuaria."
                                    )
                                )
                            }
                        }.onFailure { error ->
                            commandStatus(
                                "No pude entender la orden: " +
                                    (error.message ?: "inténtalo otra vez")
                            )
                        }
                        setCommandBusy(false)
                    }
                }
            } finally {
                audio.fill(0f)
            }
        }
    }

    private fun handleVerifiedCommand(
        result: VerifiedVoiceCommandResult
    ) {
        when (result) {
            is VerifiedVoiceCommandResult.Accepted -> {
                startTranscriptGoal(
                    transcript = result.transcript.text,
                    verification = result.verification
                )
            }

            is VerifiedVoiceCommandResult.VoiceRejected -> {
                val score = (result.verification.score * 100).roundToInt()
                commandStatus(
                    "No estoy segura de que seas tú (" +
                        score +
                        " %). Inténtalo otra vez o desactiva Protección por voz."
                )
            }

            is VerifiedVoiceCommandResult.EmptyTranscript ->
                commandStatus(
                    "Reconocí tu voz, pero no entendí ninguna orden. Inténtalo otra vez."
                )

            is VerifiedVoiceCommandResult.Failed ->
                commandStatus(result.reason)
        }
    }

    private fun startTranscriptGoal(
        transcript: String,
        verification: VoiceVerification
    ) {
        commandStatus("Entendí: “$transcript”. Pensando…")

        when (
            val bridge = LiaAccessibilityBridge.startGoal(
                goal = transcript,
                verification = verification
            )
        ) {
            AccessibilityBridgeResult.Started -> Unit
            is AccessibilityBridgeResult.Unavailable ->
                commandStatus(bridge.reason)
        }
    }

    @Synchronized
    private fun commandTranscriber(): SherpaWhisperCommandTranscriber {
        commandTranscriber?.let { return it }

        val model = commandModelProvisioner.installedModel()
            ?: error("El reconocimiento de órdenes offline no está instalado.")

        return SherpaWhisperCommandTranscriber(model)
            .also { commandTranscriber = it }
    }

    private fun refreshCommandStatus() {
        val installed = commandModelProvisioner.isInstalled()

        commandStatus(
            if (installed) {
                "Escucha offline lista. Puedes hablar con Lía sin conexión."
            } else {
                "Falta preparar la escucha offline (~111 MB). Se descarga una sola vez."
            }
        )

        talkToLiaButton.isEnabled = installed
    }

    private fun setCommandBusy(busy: Boolean) {
        talkToLiaButton.isEnabled =
            !busy && commandModelProvisioner.isInstalled()
        prepareCommandSpeechButton.isEnabled = !busy
        cancelAgentButton.isEnabled = !busy || LiaAccessibilityBridge.isConnected()
    }

    private fun importPlannerModel(uri: Uri) {
        importPlannerButton.isEnabled = false
        plannerStatus("Verificando e instalando el modelo local de Lía…")

        worker.execute {
            val result = runCatching {
                val sourceName = displayName(uri) ?: "modelo-local"
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) {
                        "Android no permitió abrir el archivo seleccionado."
                    }
                    plannerModelStore.install(
                        sourceName = sourceName,
                        input = input
                    )
                }
            }

            runOnUiThread {
                result.onSuccess { info ->
                    val megabytes = info.sizeBytes / (1024L * 1024L)
                    plannerStatus(
                        "Conectado · " +
                            info.format.displayName +
                            " · " +
                            megabytes +
                            " MB"
                    )
                }.onFailure { error ->
                    plannerStatus(
                        "No pude instalar el modelo local: " +
                            (error.message ?: "error desconocido")
                    )
                }

                importPlannerButton.isEnabled = true
            }
        }
    }

    private fun displayName(uri: Uri): String? {
        var cursor: Cursor? = null

        return try {
            cursor = contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )

            if (cursor?.moveToFirst() == true) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                null
            }
        } finally {
            cursor?.close()
        }
    }

    private fun refreshPlannerStatus() {
        val model = plannerModelStore.installedModel()

        if (model == null) {
            plannerStatus(
                "Todavía no hay un modelo conectado. Importa el que ya descargaste."
            )
            return
        }

        val megabytes = model.sizeBytes / (1024L * 1024L)
        plannerStatus(
            "Conectado · " +
                model.format.displayName +
                " · " +
                megabytes +
                " MB"
        )
    }

    private fun recordSample() {
        if (!ensureMicrophonePermission()) return
        if (samples.size >= OwnerVoiceAuthenticator.MAX_SAMPLES) {
            status("Ya tienes suficientes frases. Guarda el perfil.")
            return
        }

        if (!provisioner.isInstalled()) {
            prepareModel()
            return
        }

        setBusy(true)
        val phrase = phrases[samples.size]
        status("Muestra " + (samples.size + 1) + ". Di: “" + phrase + "”")

        worker.execute {
            val result = runCatching {
                val audio = recorder.record()
                try {
                    authenticator().embeddingFromAudio(audio)
                } finally {
                    audio.fill(0f)
                }
            }

            runOnUiThread {
                result.onSuccess {
                    samples += it
                    status(
                        "Muestra " + samples.size + " registrada. " +
                            if (samples.size >= OwnerVoiceAuthenticator.MIN_SAMPLES) "Ya puedes guardar tu voz." else "Registra una frase más."
                    )
                }.onFailure {
                    status("No pude usar esa muestra: " + (it.message ?: "error desconocido"))
                }
                setBusy(false)
                refreshButtons()
            }
        }
    }

    private fun prepareModel() {
        setBusy(true)
        status("Preparando el reconocimiento de voz local. Esta descarga ocurre una sola vez…")

        worker.execute {
            val result = runCatching { provisioner.install() }
            runOnUiThread {
                result.onSuccess {
                    status("Modelo de voz verificado. Pulsa registrar muestra para comenzar.")
                }.onFailure {
                    status("No pude preparar el modelo: " + (it.message ?: "error desconocido"))
                }
                setBusy(false)
                refreshButtons()
            }
        }
    }

    private fun saveProfile() {
        if (samples.size !in OwnerVoiceAuthenticator.MIN_SAMPLES..OwnerVoiceAuthenticator.MAX_SAMPLES) {
            status("Necesito dos frases antes de guardar tu voz.")
            return
        }

        setBusy(true)
        worker.execute {
            val result = runCatching { authenticator().saveEnrollment(samples.toList()) }
            runOnUiThread {
                result.onSuccess { quality ->
                    if (quality.accepted) {
                        val consistency = (quality.averageSimilarity * 100).roundToInt()
                        samples.forEach { it.fill(0f) }
                        samples.clear()
                        status("Listo. Ya puedo reconocer mejor tu voz. Coincidencia del perfil: " + consistency + " %.")
                    } else {
                        status(quality.message)
                    }
                }.onFailure {
                    status("No pude guardar el perfil: " + (it.message ?: "error desconocido"))
                }
                setBusy(false)
                refreshButtons()
            }
        }
    }

    private fun testVoice() {
        if (!store.hasProfile()) {
            status("Primero registra tu identidad de voz.")
            return
        }
        if (!ensureMicrophonePermission()) return
        if (!provisioner.isInstalled()) {
            status("El modelo de voz no está disponible. Vuelve a prepararlo.")
            return
        }

        setBusy(true)
        status("Habla ahora con normalidad. Lía comprobará si eres la persona autorizada.")

        worker.execute {
            val result = runCatching {
                val audio = recorder.record()
                try {
                    authenticator().verifyAudio(audio)
                } finally {
                    audio.fill(0f)
                }
            }

            runOnUiThread {
                result.onSuccess { verification ->
                    val score = (verification.score * 100).roundToInt()
                    status(
                        if (verification.matched) {
                            "Voz reconocida. Coincidencia: " + score + " %."
                        } else {
                            "No estoy segura de que seas tú. Coincidencia: " + score + " %. Puedes probar otra vez."
                        }
                    )
                }.onFailure {
                    status("No pude verificar la voz: " + (it.message ?: "error desconocido"))
                }
                setBusy(false)
                refreshButtons()
            }
        }
    }

    private fun authenticator(): OwnerVoiceAuthenticator =
        OwnerVoiceAuthenticator(speakerEngine(), store)

    @Synchronized
    private fun speakerEngine(): SherpaSpeakerEngine {
        engine?.let { return it }
        check(provisioner.isInstalled()) { "El modelo de voz no está preparado" }
        return SherpaSpeakerEngine(provisioner.modelFile).also { engine = it }
    }

    private fun ensureMicrophonePermission(
        resumeVoiceCommand: Boolean = false
    ): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) return true

        resumeVoiceCommandAfterMicPermission = resumeVoiceCommand
        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        return false
    }

    private fun refreshVoiceProtectionUi() {
        if (!::voiceControlsGroup.isInitialized) return
        voiceControlsGroup.visibility =
            if (preferences.voiceProtectionEnabled) View.VISIBLE else View.GONE
    }

    private fun setBusy(busy: Boolean) {
        recordButton.isEnabled = !busy
        saveButton.isEnabled = !busy && samples.size >= OwnerVoiceAuthenticator.MIN_SAMPLES
        testButton.isEnabled = !busy && store.hasProfile()
    }

    private fun refreshButtons() {
        recordButton.isEnabled = samples.size < OwnerVoiceAuthenticator.MAX_SAMPLES
        saveButton.isEnabled = samples.size >= OwnerVoiceAuthenticator.MIN_SAMPLES
        testButton.isEnabled = store.hasProfile()
    }

    private fun status(text: String) {
        statusText.text = text
    }

    private fun plannerStatus(text: String) {
        plannerStatusText.text = text
    }

    private fun commandStatus(text: String) {
        commandStatusText.text = text
    }

    private fun assistantStatus(text: String) {
        assistantStatusText.text = text
    }

    override fun onDestroy() {
        commandTranscriber?.close()
        commandTranscriber = null
        engine?.close()
        worker.shutdownNow()
        samples.forEach { it.fill(0f) }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_START_VOICE_COMMAND =
            "org.lia.accessibility.extra.START_VOICE_COMMAND"

        private const val COMMAND_DURATION_MS = 7_000
    }
}
