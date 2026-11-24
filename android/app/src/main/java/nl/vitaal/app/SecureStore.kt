package nl.vitaal.app

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Versleutelt de inlogsleutels (access- en refresh-token) met een AES-sleutel uit de Android Keystore.
 * De sleutel verlaat de beveiligde hardware niet; zonder dit toestel zijn de opgeslagen tokens onbruikbaar.
 * Oude, onversleutelde waarden (tot 1.1.0) worden bij het lezen meteen versleuteld opgeslagen.
 */
class SecureStore(private val prefs: SharedPreferences) {

    fun get(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        if (!stored.startsWith(PREFIX)) return stored.also { put(key, it) }
        return try {
            val all = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, IV_LENGTH))
            String(cipher.doFinal(all, IV_LENGTH, all.size - IV_LENGTH), Charsets.UTF_8)
        } catch (_: Exception) {
            null   // sleutel kwijt (bijv. na herstel op een ander toestel): opnieuw inloggen
        }
    }

    fun put(key: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(key, PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "vitalconnect_session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFIX = "enc1:"
        const val IV_LENGTH = 12
    }
}
