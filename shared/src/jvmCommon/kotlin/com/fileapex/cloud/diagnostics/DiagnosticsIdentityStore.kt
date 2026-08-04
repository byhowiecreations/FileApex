package com.fileapex.cloud.diagnostics

import com.fileapex.di.FileApexServices
import java.util.Base64
import org.bouncycastle.math.ec.rfc7748.X25519

/** SSOT for local diagnostics X25519 private key material. */
object DiagnosticsIdentityStore {
    fun privateKeyBase64OrNull(): String? {
        return FileApexServices.settings.diagnosticsPrivateKeyBase64().ifBlank { null }
    }

    fun ensureKeyPair(): DiagnosticsCrypto.KeyPairMaterial {
        privateKeyBase64OrNull()?.let { existing ->
            val privateKey = DiagnosticsCrypto.decodePrivateKey(existing)
            return DiagnosticsCrypto.KeyPairMaterial(
                privateKey = privateKey,
                publicKey = derivePublicKey(privateKey)
            )
        }
        val generated = DiagnosticsCrypto.generateKeyPair()
        FileApexServices.settings.setDiagnosticsPrivateKeyBase64(
            Base64.getEncoder().encodeToString(generated.privateKey)
        )
        return generated
    }

    fun clearLocalKey() {
        FileApexServices.settings.setDiagnosticsPrivateKeyBase64("")
    }

    private fun derivePublicKey(privateKey: ByteArray): ByteArray {
        val publicKey = ByteArray(X25519.POINT_SIZE)
        X25519.generatePublicKey(privateKey, 0, publicKey, 0)
        return publicKey
    }
}
