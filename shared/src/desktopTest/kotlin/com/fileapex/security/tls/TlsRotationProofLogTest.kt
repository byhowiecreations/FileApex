package com.fileapex.security.tls

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** No rotation proof is the normal state of a device that never re-keyed; only a proof that cannot be read is worth a log line. */
class TlsRotationProofLogTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun captured(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString()
    }

    @Test fun noProofFileIsNotAFailureAndLogsNothing() {
        val store = TlsIdentityStore(File(temp.root, "none"), emptyList())
        val identity = store.getOrCreate()
        val out = captured { assertNull(store.currentRotationProof(identity)) }
        assertTrue(out, out.isEmpty())
    }

    @Test fun anUnreadableProofIsLoggedAndTreatedAsNoProof() {
        val dir = File(temp.root, "bad")
        val store = TlsIdentityStore(dir, emptyList())
        val identity = store.getOrCreate()
        File(dir, "tls_rotation.v1").writeText("not a proof")
        val out = captured { assertNull(store.currentRotationProof(identity)) }
        assertTrue(out, out.contains("the TLS rotation proof failed"))
    }
}
