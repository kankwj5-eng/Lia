package org.lia.accessibility.voice

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class VoiceProfile(
    val embeddings: List<FloatArray>,
    val threshold: Float,
    val createdAtEpochMs: Long
)

class VoiceProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasProfile(): Boolean = prefs.contains(KEY_PROFILE)

    fun save(profile: VoiceProfile) {
        require(profile.embeddings.size in 3..5)
        val clear = serialize(profile)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(AAD)
            val encrypted = cipher.doFinal(clear)
            val packed = ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { out ->
                    out.writeInt(cipher.iv.size)
                    out.write(cipher.iv)
                    out.writeInt(encrypted.size)
                    out.write(encrypted)
                }
                bytes.toByteArray()
            }
            prefs.edit().putString(KEY_PROFILE, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
        } finally {
            clear.fill(0)
        }
    }

    fun load(): VoiceProfile? {
        val encoded = prefs.getString(KEY_PROFILE, null) ?: return null
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        val pair = DataInputStream(ByteArrayInputStream(packed)).use { input ->
            val iv = ByteArray(input.readInt()).also { input.readFully(it) }
            val encrypted = ByteArray(input.readInt()).also { input.readFully(it) }
            iv to encrypted
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, pair.first))
        cipher.updateAAD(AAD)
        val clear = cipher.doFinal(pair.second)
        return try {
            deserialize(clear)
        } finally {
            clear.fill(0)
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_PROFILE).apply()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun serialize(profile: VoiceProfile): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(FORMAT_VERSION)
                out.writeLong(profile.createdAtEpochMs)
                out.writeFloat(profile.threshold)
                out.writeInt(profile.embeddings.size)
                profile.embeddings.forEach { embedding ->
                    out.writeInt(embedding.size)
                    embedding.forEach(out::writeFloat)
                }
            }
            bytes.toByteArray()
        }

    private fun deserialize(bytes: ByteArray): VoiceProfile =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == FORMAT_VERSION)
            val createdAt = input.readLong()
            val threshold = input.readFloat()
            val count = input.readInt()
            require(count in 3..5)
            val embeddings = ArrayList<FloatArray>(count)
            repeat(count) {
                val dim = input.readInt()
                require(dim in 1..4096)
                embeddings += FloatArray(dim) { input.readFloat() }
            }
            VoiceProfile(embeddings, threshold, createdAt)
        }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    companion object {
        private const val PREFS_NAME = "lia_voice_identity"
        private const val KEY_PROFILE = "owner_profile"
        private const val KEY_ALIAS = "lia_owner_voice_key_v1"
        private const val FORMAT_VERSION = 1
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val AAD = "lia-owner-voice-v1".encodeToByteArray()
    }
}
