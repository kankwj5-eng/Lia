package org.lia.accessibility.device

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

enum class HapticSignal {
    LISTENING,
    COMPLETED,
    ERROR,
    SOS
}

class HapticFeedback(private val context: Context) {
    fun signal(type: HapticSignal) {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        val timings = when (type) {
            HapticSignal.LISTENING -> longArrayOf(0, 70)
            HapticSignal.COMPLETED -> longArrayOf(0, 70, 80, 70)
            HapticSignal.ERROR -> longArrayOf(0, 400)
            HapticSignal.SOS -> longArrayOf(0, 500, 120, 500, 120, 700)
        }
        vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
    }
}
