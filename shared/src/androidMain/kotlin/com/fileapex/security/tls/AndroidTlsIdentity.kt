package com.fileapex.security.tls

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.fileapex.data.settings.androidAppContextOrNull
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** AES-GCM key held in the Android Keystore wraps the identity file. Keystore faults fall back to plain. */
class AndroidKeystoreTlsVault(private val alias: String = ALIAS) : TlsSecretVault {
    override val scheme = SCHEME

    override fun seal(plain: ByteArray) = GcmBlob.seal(key(), plain)
    override fun open(sealed: ByteArray) = GcmBlob.open(key(), sealed)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val SCHEME = 1
        const val ALIAS = "fileapex_tls_wrap_v1"
        /** Wraps the settings secrets (PIN, private keys, tokens); separate from the TLS identity key. */
        const val SETTINGS_ALIAS = "fileapex_settings_wrap_v1"
    }
}

object AndroidTlsIdentity {
    /** Null before the application context exists. The directory is app-private and outside backup rules. */
    fun store(): TlsIdentityStore? {
        cached?.let { return it }
        val context = androidAppContextOrNull() ?: return null
        return synchronized(this) {
            cached ?: TlsIdentityStore(File(context.filesDir, "tls"), listOf(AndroidKeystoreTlsVault()))
                .also { cached = it }
        }
    }

    @Volatile
    private var cached: TlsIdentityStore? = null
}
