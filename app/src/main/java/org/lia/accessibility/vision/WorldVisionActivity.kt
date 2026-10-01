package org.lia.accessibility.vision

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.lia.accessibility.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class WorldVisionActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var resultView: TextView
    private lateinit var analyzer: WorldFrameAnalyzer
    private lateinit var cameraExecutor: ExecutorService

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else show("Lía necesita permiso de cámara para describir el entorno.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_world_vision)

        previewView = findViewById(R.id.previewView)
        resultView = findViewById(R.id.visionResult)
        cameraExecutor = Executors.newSingleThreadExecutor()
        analyzer = WorldFrameAnalyzer(
            onResult = { result -> runOnUiThread { show(result.spokenSummary(), announce = true) } },
            onError = { error -> runOnUiThread { show("No pude analizar la escena: " + error.message) } }
        )

        findViewById<Button>(R.id.analyzeButton).setOnClickListener {
            show("Analizando…")
            analyzer.requestAnalysis()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, analyzer) }

            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun show(message: String, announce: Boolean = false) {
        resultView.text = message
        if (announce) resultView.announceForAccessibility(message)
    }

    override fun onDestroy() {
        analyzer.close()
        cameraExecutor.shutdownNow()
        super.onDestroy()
    }
}
