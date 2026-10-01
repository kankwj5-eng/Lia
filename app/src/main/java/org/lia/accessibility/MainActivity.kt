package org.lia.accessibility

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.lia.accessibility.accessibility.AccessibilityBridgeResult
import org.lia.accessibility.accessibility.LiaAccessibilityBridge
import org.lia.accessibility.ai.PlannerModelStore
import org.lia.accessibility.vision.WorldVisionActivity
import org.lia.accessibility.voice.OwnerVoiceAuthenticator
import org.lia.accessibility.voice.SherpaSpeakerEngine
import org.lia.accessibility.voice.SherpaWhisperCommandTranscriber
import org.lia.accessibility.voice.VoiceModelProvisioner
import org.lia.accessibility.voice.VoiceProfileStore
import org.lia.accessibility.voice.VerifiedVoiceCommandProcessor
import org.lia.accessibility.voice.VerifiedVoiceCommandResult
import org.lia.accessibility.voice.VoiceSampleRecorder
import org.lia.accessibility.voice.WhisperCommandModelProvisioner
import org.lia.accessibility.voice.asVoiceIdentityVerifier
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var plannerStatusText: TextView
    private lateinit var commandStatusText: TextView
    private lateinit var recordButton: Button
    private lateinit var saveButton: Button
    private lateinit var testButton: Button
    private lateinit var importPlannerButton: Button
    private lateinit var prepareCommandSpeechButton: Button
    private lateinit var talkToLiaButton: Button
    private lateinit var cancelAgentButton: Button

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var recorder: VoiceSampleRecorder
    private val samples = mutableListOf<FloatArray>()

    private lateinit var provisioner: VoiceModelProvisioner
    private lateinit var store: VoiceProfileStore
    private lateinit var plannerModelStore: PlannerModelStore
    private lateinit var commandModelProvisioner: WhisperCommandModelProvisioner
    private var engine: SherpaSpeakerEngine? = null
    private var commandTranscriber: SherpaWhisperCommandTranscriber? = null

    private val phrases = listOf(
        "Hola Lía, esta es mi voz.",
        "Lía, ayúdame a usar mi teléfono.",
        "Quiero que reconozcas mi voz.",
        "Lía, acompáñame y ayúdame cuando te necesite.",
        "Esta es mi voz y autorizo a Lía en este dispositivo."
    )

    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) status("Micrófono autorizado. Pulsa registrar muestra otra vez.")
            else status("Lía necesita acceso al micrófono para registrar tu identidad de voz.")
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

        provisioner = VoiceModelProvisioner(this)
        store = VoiceProfileStore(this)
        recorder = VoiceSampleRecorder(this)
        plannerModelStore = PlannerModelStore(this)
        commandModelProvisioner = WhisperCommandModelProvisioner(this)

        statusText = findViewById(R.id.statusText)
        plannerStatusText = findViewById(R.id.plannerStatusText)
        commandStatusText = findViewById(R.id.commandStatusText)
        recordButton = findViewById(R.id.recordSampleButton)
        saveButton = findViewById(R.id.saveVoiceButton)
        testButton = findViewById(R.id.testVoiceButton)
        importPlannerButton = findViewById(R.id.importPlannerButton)
        prepareCommandSpeechButton = findViewById(R.id.prepareCommandSpeechButton)
        talkToLiaButton = findViewById(R.id.talkToLiaButton)
        cancelAgentButton = findViewById(R.id.cancelAgentButton)

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

        findViewById<Button>(R.id.worldVisionButton).setOnClickListener {
            startActivity(Intent(this, WorldVisionActivity::class.java))
        }

        findViewById<Button>(R.id.accessibilitySettingsButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.notificationSettingsButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        refreshButtons()
        refreshPlannerStatus()
        refreshCommandStatus()

        status(
            if (store.hasProfile()) {
                "Tu identidad de voz ya está registrada. Puedes probarla o continuar configurando Lía."
            } else {
                "Registra entre 3 y 5 muestras. La primera vez Lía preparará el modelo local de voz."
            }
        )
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
        if (!ensureMicrophonePermission()) return

        if (!store.hasProfile()) {
            commandStatus("Primero registra y guarda tu identidad de voz.")
            return
        }

        if (!provisioner.isInstalled()) {
            commandStatus("Primero prepara el reconocimiento de identidad de voz.")
            return
        }

        if (!commandModelProvisioner.isInstalled()) {
            commandStatus("Primero prepara el reconocimiento de órdenes offline.")
            return
        }

        if (!plannerModelStore.isInstalled()) {
            commandStatus("Primero importa el cerebro local .litertlm.")
            return
        }

        if (!LiaAccessibilityBridge.isConnected()) {
            commandStatus("Activa primero el control de Android de Lía.")
            return
        }

        setCommandBusy(true)
        commandStatus("Te escucho. Di tu orden con normalidad.")

        worker.execute {
            val result = runCatching {
                val audio = recorder.record(COMMAND_DURATION_MS)
                try {
                    VerifiedVoiceCommandProcessor(
                        verifier = authenticator().asVoiceIdentityVerifier(),
                        speechToText = commandTranscriber()
                    ).process(audio)
                } finally {
                    audio.fill(0f)
                }
            }

            runOnUiThread {
                result.onSuccess { command ->
                    handleVerifiedCommand(command)
                }.onFailure { error ->
                    commandStatus(
                        "No pude procesar la orden: " +
                            (error.message ?: "error desconocido")
                    )
                }

                setCommandBusy(false)
            }
        }
    }

    private fun handleVerifiedCommand(
        result: VerifiedVoiceCommandResult
    ) {
        when (result) {
            is VerifiedVoiceCommandResult.Accepted -> {
                val transcript = result.transcript.text
                commandStatus("Entendí: “" + transcript + "”. Ejecutando…")

                when (
                    val bridge = LiaAccessibilityBridge.startGoal(
                        goal = transcript,
                        verification = result.verification
                    )
                ) {
                    AccessibilityBridgeResult.Started -> Unit
                    is AccessibilityBridgeResult.Unavailable ->
                        commandStatus(bridge.reason)
                }
            }

            is VerifiedVoiceCommandResult.VoiceRejected -> {
                val score = (result.verification.score * 100).roundToInt()
                commandStatus(
                    "Voz rechazada. Coincidencia: " +
                        score +
                        " %. No ejecutaré la orden."
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
                "Reconocimiento de órdenes offline listo."
            } else {
                "Reconocimiento de órdenes: falta descargar el modelo multilingüe (~111 MB)."
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
                val sourceName = displayName(uri) ?: "planner.litertlm"
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
                        "Modelo local instalado: " +
                            megabytes +
                            " MB. SHA-256: " +
                            info.sha256.take(12) +
                            "…"
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
                "Cerebro local: no instalado. Puedes importar un archivo .litertlm."
            )
            return
        }

        val megabytes = model.sizeBytes / (1024L * 1024L)
        plannerStatus(
            "Cerebro local listo: " +
                megabytes +
                " MB · " +
                model.sha256.take(12) +
                "…"
        )
    }

    private fun recordSample() {
        if (!ensureMicrophonePermission()) return
        if (samples.size >= OwnerVoiceAuthenticator.MAX_SAMPLES) {
            status("Ya tienes 5 muestras. Guarda el perfil.")
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
                            if (samples.size >= 3) "Ya puedes guardar o registrar hasta 5." else "Continúa con otra muestra."
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
            status("Necesito entre 3 y 5 muestras antes de guardar.")
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
                        status("Identidad de voz guardada y cifrada. Coherencia: " + consistency + " %.")
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
                            "Voz rechazada. Coincidencia: " + score + " %. " + verification.message
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

    private fun ensureMicrophonePermission(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) return true

        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        return false
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
        statusText.announceForAccessibility(text)
    }

    private fun plannerStatus(text: String) {
        plannerStatusText.text = text
        plannerStatusText.announceForAccessibility(text)
    }

    private fun commandStatus(text: String) {
        commandStatusText.text = text
        commandStatusText.announceForAccessibility(text)
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
        private const val COMMAND_DURATION_MS = 7_000
    }
}
