package com.polylingua.edge.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores cloud API keys (Gemini, Claude, OpenAI-compatible) encrypted with an AES-256-GCM key
 * that lives in the Android Keystore and never leaves the device. Keys are not part of app
 * backups (allowBackup=false), settings exports, logs or diagnostics.
 */
@Singleton
class SecureKeyStore @Inject constructor(@ApplicationContext context: Context) {

    enum class Slot(val prefKey: String, val label: String) {
        GEMINI("k_gemini", "Gemini API key"),
        CLAUDE("k_claude", "Claude API key"),
        OPENAI("k_openai", "OpenAI-compatible API key"),
    }

    private val prefs = context.getSharedPreferences("polylingua_secrets", Context.MODE_PRIVATE)

    private val _revision = MutableStateFlow(0)
    /** Increments whenever a key is saved or removed. */
    val revision: StateFlow<Int> = _revision

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    @Synchronized
    fun put(slot: Slot, value: String?) {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) {
            prefs.edit().remove(slot.prefKey).apply()
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val ct = cipher.doFinal(v.toByteArray(Charsets.UTF_8))
            val blob = ByteArray(1 + iv.size + ct.size)
            blob[0] = iv.size.toByte()
            System.arraycopy(iv, 0, blob, 1, iv.size)
            System.arraycopy(ct, 0, blob, 1 + iv.size, ct.size)
            prefs.edit().putString(slot.prefKey, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
        }
        _revision.value = _revision.value + 1
    }

    @Synchronized
    fun get(slot: Slot): String? {
        val stored = prefs.getString(slot.prefKey, null) ?: return null
        return try {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val ivLen = blob[0].toInt()
            val iv = blob.copyOfRange(1, 1 + ivLen)
            val ct = blob.copyOfRange(1 + ivLen, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (t: Throwable) {
            // Keystore key invalidated (e.g. device credential reset): the key must be re-entered.
            DiagLog.w("PolyLinguaKeys", "Stored ${slot.label} could not be decrypted; cleared", t)
            prefs.edit().remove(slot.prefKey).apply()
            null
        }
    }

    fun has(slot: Slot): Boolean = prefs.contains(slot.prefKey)

    /** "AIza…x3kQ" style hint for the settings screen. */
    fun masked(slot: Slot): String {
        val k = get(slot) ?: return ""
        return if (k.length <= 10) "•".repeat(k.length) else k.take(4) + "…" + k.takeLast(4)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "polylingua_api_keys_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
