package org.lia.accessibility

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.lia.accessibility.assistant.LiaRecognitionService
import org.lia.accessibility.assistant.LiaVoiceInteractionService
import org.lia.accessibility.assistant.LiaVoiceInteractionSessionService

@RunWith(AndroidJUnit4::class)
class AssistantManifestTest {
    private val context: Context =
        ApplicationProvider.getApplicationContext()

    @Test
    fun voiceInteractionServiceIsProtectedAndHasMetadata() {
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, LiaVoiceInteractionService::class.java),
            PackageManager.GET_META_DATA
        )

        assertEquals(
            Manifest.permission.BIND_VOICE_INTERACTION,
            info.permission
        )
        assertNotEquals(
            0,
            info.metaData.getInt("android.voice_interaction")
        )
    }

    @Test
    fun sessionAndRecognitionServicesAreProtected() {
        val session = context.packageManager.getServiceInfo(
            ComponentName(
                context,
                LiaVoiceInteractionSessionService::class.java
            ),
            PackageManager.GET_META_DATA
        )
        val recognition = context.packageManager.getServiceInfo(
            ComponentName(context, LiaRecognitionService::class.java),
            PackageManager.GET_META_DATA
        )

        assertEquals(
            Manifest.permission.BIND_VOICE_INTERACTION,
            session.permission
        )
        assertEquals(
            Manifest.permission.BIND_VOICE_INTERACTION,
            recognition.permission
        )
        assertNotEquals(
            0,
            recognition.metaData.getInt("android.speech")
        )
    }
}
