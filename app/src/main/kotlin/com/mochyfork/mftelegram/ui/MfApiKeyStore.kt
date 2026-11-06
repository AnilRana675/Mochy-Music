package com.mochyfork.mftelegram.ui

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

data class MfApiKeys(val apiId: Int, val apiHash: String)

interface MfApiKeyStore {
    fun read(): MfApiKeys?
    fun save(keys: MfApiKeys): Boolean
    fun clear()
}

@Singleton
class KeystoreMfApiKeyStore @Inject constructor(
    @ApplicationContext
    context: Context,
) : MfApiKeyStore {
    private val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
    private val key: SecretKey
        get() {
            val store = KeyStore.getInstance(AndroidKeyStore).apply { load(null) }
            val existing = store.getKey(KeyAlias, null) as? SecretKey
            if (existing != null) return existing
            return KeyGenerator.getInstance(KeyAlgorithm, AndroidKeyStore).apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KeyAlias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }.generateKey()
        }

    override fun read(): MfApiKeys? = preferences.getString(ValueKey, null)?.let { decode(it) }

    override fun save(keys: MfApiKeys): Boolean = try {
        preferences.edit().putString(ValueKey, encode("${keys.apiId}\n${keys.apiHash}")).apply()
        true
    } catch (error: Exception) {
        MfLog.error(error)
        false
    }

    override fun clear() {
        preferences.edit().clear().apply()
    }

    private object MfLog {
        fun error(error: Exception) {
            java.util.logging.Logger.getLogger("MfLog")
                .warning("API key storage failed: ${error.javaClass.name}")
        }
    }

    private fun encode(value: String): String {
        val cipher = Cipher.getInstance(CipherAlgorithm)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(value.toByteArray())
        val payload = ByteBuffer.allocate(cipher.iv.size + encrypted.size)
        payload.put(cipher.iv).put(encrypted)
        return Base64.encodeToString(payload.array(), Base64.NO_WRAP)
    }

    private fun decode(value: String): MfApiKeys? = runCatching {
        val payload = Base64.decode(value, Base64.NO_WRAP)
        val buffer = ByteBuffer.wrap(payload)
        val iv = ByteArray(IvSize).also(buffer::get)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance(CipherAlgorithm)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TagBits, iv))
        val parts = String(cipher.doFinal(encrypted), StandardCharsets.UTF_8).split('\n')
        MfApiKeys(parts[0].toInt(), parts[1])
    }.getOrNull()

    private companion object {
        const val AndroidKeyStore = "AndroidKeyStore"
        const val KeyAlias = "mf_telegram_api_keys"
        const val KeyAlgorithm = "AES"
        const val CipherAlgorithm = "AES/GCM/NoPadding"
        const val PreferencesName = "mf_telegram_api_keys"
        const val ValueKey = "encrypted_value"
        const val IvSize = 12
        const val TagBits = 128
    }
}

fun validMfApiKeys(apiId: String, apiHash: String): MfApiKeys? =
    apiId.toIntOrNull()?.takeIf { it > 0 }?.let { id ->
        apiHash.takeIf { it.matches(Regex("[0-9a-fA-F]{32}")) }?.let { MfApiKeys(id, it) }
    }

fun resolveMfApiKeys(inApp: MfApiKeys?, buildTime: MfApiKeys?): MfApiKeys? = inApp ?: buildTime
