package com.partstock.scanner

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the Mouser API key and the DigiKey Client ID / Secret on this phone only.
 *
 * They are typed in by the user (it is never in the source code or the APK). It is encrypted with
 * AES-GCM using a key that lives in the Android Keystore and can't be exported, and only the encrypted
 * text is written to disk. The file is excluded from backups (see res/xml/backup_rules.xml).
 */
class SecretStore(context: Context) {
    private val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun hasMouserKey(): Boolean = sp.contains(MOUSER)

    /** The saved key, or null if none is saved or it can no longer be decrypted. */
    fun getMouserKey(): String? {
        val stored = sp.getString(MOUSER, null) ?: return null
        return try {
            decrypt(stored)
        } catch (e: Exception) {
            null
        }
    }

    fun setMouserKey(key: String?) {
        if (key.isNullOrBlank()) {
            sp.edit().remove(MOUSER).apply()
        } else {
            sp.edit().putString(MOUSER, encrypt(key.trim())).apply()
        }
    }

    fun hasDigiKey(): Boolean = sp.contains(DIGIKEY_ID) && sp.contains(DIGIKEY_SECRET)

    /** Saved DigiKey Client ID and Secret, or null if either is missing or can't be decrypted. */
    fun getDigiKey(): Pair<String, String>? {
        val id = sp.getString(DIGIKEY_ID, null) ?: return null
        val secret = sp.getString(DIGIKEY_SECRET, null) ?: return null
        return try {
            decrypt(id) to decrypt(secret)
        } catch (e: Exception) {
            null
        }
    }

    /** Saves both, or removes both when either is blank. */
    fun setDigiKey(clientId: String?, clientSecret: String?) {
        if (clientId.isNullOrBlank() || clientSecret.isNullOrBlank()) {
            sp.edit().remove(DIGIKEY_ID).remove(DIGIKEY_SECRET).apply()
        } else {
            sp.edit()
                .putString(DIGIKEY_ID, encrypt(clientId.trim()))
                .putString(DIGIKEY_SECRET, encrypt(clientSecret.trim()))
                .apply()
        }
        DigiKeyApi.clearToken()
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    private companion object {
        const val FILE = "secure"
        const val MOUSER = "mouser_api_key"
        const val DIGIKEY_ID = "digikey_client_id"
        const val DIGIKEY_SECRET = "digikey_client_secret"
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "partstock_secret_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
