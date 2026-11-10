package com.fileapex.drop

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters

/** Ed25519 through BouncyCastle's lightweight API: Android's bundled "BC" provider has no Ed25519. */
actual fun ed25519PublicKey(seed: ByteArray): ByteArray {
    require(seed.size == 32) { "an Ed25519 seed is 32 bytes" }
    return Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
}
