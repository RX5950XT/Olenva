package app.olenva.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the OpenRouter key encrypted with an Android Keystore AES key; the plaintext never touches disk. */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("secret", Context.MODE_PRIVATE)
    private val _apiKey = MutableStateFlow(load())
    val apiKey: StateFlow<String?> = _apiKey

    fun save(key: String) {
        val trimmed = key.trim()
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val enc = cipher.doFinal(trimmed.toByteArray())
        prefs.edit {
            putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            putString("key", Base64.encodeToString(enc, Base64.NO_WRAP))
        }
        _apiKey.value = trimmed
    }

    fun clear() {
        prefs.edit { clear() }
        _apiKey.value = null
    }

    private fun load(): String? {
        val iv = prefs.getString("iv", null) ?: return null
        val data = prefs.getString("key", null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)))
        } catch (e: Exception) {
            Log.w("SecretStore", "stored key unreadable, clearing: ${e.javaClass.simpleName}")
            prefs.edit { clear() }
            null
        }
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "olenva_api_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
