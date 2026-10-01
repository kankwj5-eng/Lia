package org.lia.accessibility

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.lia.accessibility.vision.WorldVisionActivity
import org.lia.accessibility.voice.OwnerVoiceAuthenticator
import org.lia.accessibility.voice.SherpaSpeakerEngine
import org.lia.accessibility.voice.VoiceModelProvisioner
import org.lia.accessibility.voice.VoiceProfileStore
import org.lia.accessibility.voice.VoiceSampleRecorder
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var recordButton: Button
    private lateinit var saveButton: Button
    private lateinit var testButton: Button

    private val worker = Executors.newSingleThreadExecutor()
    private val recorder = VoiceSampleRecorder()
    private val samples = mutableListOf<FloatArray>()

    private lateinit var provisioner: VoiceModelProvisioner
    private lateinit var store: VoiceProfileStore
    private var engine: SherpaSpeakerEngine? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        provisioner = VoiceModelProvisioner(this)
        store = VoiceProfileStore(this)

        statusText = findViewById(R.id.statusText)
        recordButton = findViewById(R.id.recordSampleButton)
        saveButton = findViewById(R.id.saveVoiceButton)
        testButton = findViewById(R.id.testVoiceButton)

        recordButton.setOnClickListener { recordSample() }
        saveButton.setOnClickListener { saveProfile() }
        testButton.setOnClickListener { testVoice() }

        findViewById<Button>(R.id.worldVisionButton).setOnClickListener {
            startActivity(Intent(this, WorldVisionActivity::class.java))
        }

        findViewById<Button>(R.id.accessibilitySettingsButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        refreshButtons()
        status(
            if (store.hasProfile()) {
                "Tu identidad de voz ya está registrada. Puedes probarla o continuar configurando Lía."
            } else {
                "Registra entre 3 y 5 muestras. La primera vez Lía preparará el modelo local de voz."
            }
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

    override fun onDestroy() {
        engine?.close()
        worker.shutdownNow()
        samples.forEach { it.fill(0f) }
        super.onDestroy()
    }
}
