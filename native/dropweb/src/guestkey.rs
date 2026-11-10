//! The guest's endpoint key is derived from the link secret, so the sender can list the guest's endpoint ids with the relay
//! before anyone opens the link. The same derivation lives in Kotlin (`GuestDropKeys`); both are tested against the same vectors.

use hkdf::Hkdf;
use sha2::Sha256;

const SALT: &[u8] = b"fileapex-drop-guest-v1";

pub const GUEST_KEY_COUNT: u8 = 2;

/// The 32-byte Ed25519 seed for guest key `index` of a link secret.
pub fn guest_seed(secret: &str, index: u8) -> [u8; 32] {
    let hkdf = Hkdf::<Sha256>::new(Some(SALT), secret.as_bytes());
    let mut seed = [0u8; 32];
    hkdf.expand(format!("guest-key-{index}").as_bytes(), &mut seed).expect("32 bytes is a valid HKDF length");
    seed
}

/// The endpoint id (64 lowercase hex) that guest key `index` connects as.
pub fn guest_public_id(secret: &str, index: u8) -> String {
    iroh::SecretKey::from_bytes(&guest_seed(secret, index)).public().to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    const SECRET: &str = "0123456789abcdef0123456789abcdef";

    fn hex(bytes: &[u8]) -> String {
        bytes.iter().map(|b| format!("{b:02x}")).collect()
    }

    // Expected values computed with an independent Python HKDF and the RFC 8032 Ed25519 arithmetic.
    #[test]
    fn seeds_match_the_independent_vectors() {
        assert_eq!(hex(&guest_seed(SECRET, 0)), "8d05f6ba357ef5a50cb7ed4e283b78840d1127beca0c52082ff50c0d6c3fca45");
        assert_eq!(hex(&guest_seed(SECRET, 1)), "f502b0c914e070346b582a6e658db9e372407db92d3249385c12bea2bfbaffc2");
        assert_eq!(
            hex(&guest_seed("ffffffffffffffffffffffffffffffff", 0)),
            "701e928b7ef8c3740fdfcb2d8691a5ca0d6e8e38aa527e618a21d6b3e064755b"
        );
    }

    #[test]
    fn endpoint_ids_match_the_independent_vectors() {
        assert_eq!(guest_public_id(SECRET, 0), "e514c51615215bfb30da3cddddfdfc6c8b5b897be4896a037ff706906ff05775");
        assert_eq!(guest_public_id(SECRET, 1), "98229a0168d0901ec5b81c6a8f20664c1d534c40c3de0fcf730fcfdbc51348d2");
        assert_eq!(
            guest_public_id("ffffffffffffffffffffffffffffffff", 0),
            "76579ab6f2c94d339d51b53916f11ee25d36695bd39a1b7cc59138ad89df0389"
        );
    }

    #[test]
    fn different_indexes_and_secrets_give_different_keys() {
        assert_ne!(guest_seed(SECRET, 0), guest_seed(SECRET, 1));
        assert_ne!(guest_seed(SECRET, 0), guest_seed("ffffffffffffffffffffffffffffffff", 0));
        assert_eq!(guest_public_id(SECRET, 0).len(), 64);
    }
}
