package com.bhavish.jarvis.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.bhavish.jarvis.provider.ProviderId
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts each provider key before it reaches app-private preferences. */
class KeyVault(context: Context) {
    private val prefs = context.getSharedPreferences("encrypted_provider_keys", Context.MODE_PRIVATE)
    private val alias = "jarvis_provider_keys_v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    fun save(provider: ProviderId, value: String) {
        val clean = value.trim()
        if (clean.isEmpty()) {
            prefs.edit().remove(provider.name).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + ciphertext
        prefs.edit().putString(provider.name, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
    }

    fun read(provider: ProviderId): String {
        val encoded = prefs.getString(provider.name, null) ?: return ""
        return try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, payload.copyOfRange(0, 12)))
            String(cipher.doFinal(payload.copyOfRange(12, payload.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    fun configured(provider: ProviderId): Boolean = prefs.contains(provider.name)
}
