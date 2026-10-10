package com.fileapex.security.tls

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayOutputStream
import java.security.KeyStore

object TlsPkcs12 {
    /**
     * In-memory PKCS#12 for handing the identity to the macOS native client. The 3DES profile imports on
     * every macOS release the app supports; the JDK default PBE profile does not on the older ones.
     */
    fun export(identity: TlsIdentity, password: CharArray): ByteArray {
        val store = KeyStore.getInstance("PKCS12-3DES-3DES", BouncyCastleProvider())
        store.load(null, null)
        store.setKeyEntry("fileapex", identity.privateKey, password, arrayOf(identity.certificate))
        return ByteArrayOutputStream().also { store.store(it, password) }.toByteArray()
    }
}
