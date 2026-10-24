//! Organizer recovery file, encrypted with a passphrase the user chooses at export time:
//!
//! ```text
//! "MNTR" ‖ 0x01 ‖ salt(16) ‖ m_cost(u32 BE) ‖ t_cost ‖ p_cost ‖ nonce(24) ‖ XChaCha20-Poly1305(ct)
//! key = Argon2id(passphrase, salt, params); AD = everything before the nonce
//! ```

use zeroize::Zeroizing;

use crate::crypto::{self, NONCE_LEN, random_bytes};
use crate::error::StoreError;
use crate::kdf::{KdfParams, SALT_LEN, derive_key};

pub const RECOVERY_MAGIC: &[u8; 4] = b"MNTR";
pub const RECOVERY_EXTENSION: &str = "manotu-recovery";
const VERSION: u8 = 1;
const HEADER_LEN: usize = 4 + 1 + SALT_LEN + 12;
/// Refuse anything larger (a recovery file holds keys and drafts, not map data).
const MAX_RECOVERY_BYTES: usize = 64 * 1024 * 1024;

pub fn seal_recovery(
    plaintext: &[u8],
    passphrase: &str,
    params: KdfParams,
) -> Result<Vec<u8>, StoreError> {
    let salt = random_bytes::<SALT_LEN>()?;
    let key = derive_key(passphrase, &salt, params)?;
    let mut header = Vec::with_capacity(HEADER_LEN);
    header.extend_from_slice(RECOVERY_MAGIC);
    header.push(VERSION);
    header.extend_from_slice(&salt);
    header.extend_from_slice(&params.to_bytes());
    let (nonce, ct) = crypto::seal(&key, &header, plaintext)?;
    let mut out = header;
    out.extend_from_slice(&nonce);
    out.extend_from_slice(&ct);
    Ok(out)
}

pub fn open_recovery(bytes: &[u8], passphrase: &str) -> Result<Zeroizing<Vec<u8>>, StoreError> {
    if bytes.len() > MAX_RECOVERY_BYTES
        || bytes.len() < HEADER_LEN + NONCE_LEN + 16
        || &bytes[..4] != RECOVERY_MAGIC
        || bytes[4] != VERSION
    {
        return Err(StoreError::MalformedRecovery);
    }
    let salt: [u8; SALT_LEN] = bytes[5..5 + SALT_LEN].try_into().expect("length checked");
    let params = KdfParams::from_bytes(&bytes[5 + SALT_LEN..HEADER_LEN])
        .ok_or(StoreError::MalformedRecovery)?;
    let key = derive_key(passphrase, &salt, params).map_err(|e| match e {
        StoreError::WeakPassphrase => StoreError::WrongKey,
        e => e,
    })?;
    crypto::open(
        &key,
        &bytes[..HEADER_LEN],
        &bytes[HEADER_LEN..HEADER_LEN + NONCE_LEN],
        &bytes[HEADER_LEN + NONCE_LEN..],
    )
}
