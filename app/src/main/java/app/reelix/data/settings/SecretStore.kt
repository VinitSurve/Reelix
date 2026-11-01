package app.reelix.data.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores small secrets such as an optional user-supplied API key. */
interface SecretStore {
    fun get(alias: String): String?
    fun put(alias: String, value: String)
    fun remove(alias: String)
}

/**
 * Encrypts secrets with an AES-GCM key held in the Android Keystore; only ciphertext is written to
 * private SharedPreferences. Secrets never leave the device and are excluded from backups.
 */
class KeystoreSecretStore(context: Context) : SecretStore {

    private val prefs = context.applicationContext.getSharedPreferences("reelix_secrets", Context.MODE_PRIVATE)

    override fun get(alias: String): String? {
        val stored = prefs.getString(alias, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, IV_LENGTH)
            val cipherText = bytes.copyOfRange(IV_LENGTH, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        }.getOrNull()
    }

    override fun put(alias: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(alias, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    override fun remove(alias: String) {
        prefs.edit().remove(alias).apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "reelix_secret_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
    }
}

/** Non-persistent store, used in tests. */
class InMemorySecretStore : SecretStore {
    private val values = ConcurrentHashMap<String, String>()
    override fun get(alias: String): String? = values[alias]
    override fun put(alias: String, value: String) { values[alias] = value }
    override fun remove(alias: String) { values.remove(alias) }
}

