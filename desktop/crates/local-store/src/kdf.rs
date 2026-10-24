//! Argon2id passphrase derivation (RustCrypto `argon2`).

use argon2::{Algorithm, Argon2, Params, Version};
use zeroize::Zeroizing;

use crate::crypto::{DataKey, KEY_LEN};
use crate::error::StoreError;

pub const SALT_LEN: usize = 16;
pub const MIN_PASSPHRASE_CHARS: usize = 10;

/// Argon2id cost parameters, stored next to the salt so they can be raised later.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct KdfParams {
    /// Memory in KiB.
    pub m_cost: u32,
    pub t_cost: u32,
    pub p_cost: u32,
}

impl KdfParams {
    /// 64 MiB, 3 passes, 1 lane (OWASP's Argon2id baseline is 19 MiB / 2 passes; we go higher
    /// because this protects a whole vault offline).
    pub const DEFAULT: KdfParams = KdfParams {
        m_cost: 64 * 1024,
        t_cost: 3,
        p_cost: 1,
    };

    /// Cheap parameters for unit tests only.
    pub const INSECURE_FOR_TESTS: KdfParams = KdfParams {
        m_cost: 64,
        t_cost: 1,
        p_cost: 1,
    };

    pub(crate) fn to_bytes(self) -> [u8; 12] {
        let mut b = [0u8; 12];
        b[..4].copy_from_slice(&self.m_cost.to_be_bytes());
        b[4..8].copy_from_slice(&self.t_cost.to_be_bytes());
        b[8..].copy_from_slice(&self.p_cost.to_be_bytes());
        b
    }

    pub(crate) fn from_bytes(b: &[u8]) -> Option<Self> {
        if b.len() != 12 {
            return None;
        }
        let u = |i: usize| u32::from_be_bytes(b[i..i + 4].try_into().expect("4 bytes"));
        let p = KdfParams {
            m_cost: u(0),
            t_cost: u(4),
            p_cost: u(8),
        };
        // Refuse absurd parameters from a crafted file (memory bomb).
        (p.m_cost <= 4 * 1024 * 1024 && p.t_cost <= 64 && (1..=16).contains(&p.p_cost)).then_some(p)
    }
}

/// Derives the 32-byte key. The passphrase must have at least 10 characters.
pub fn derive_key(
    passphrase: &str,
    salt: &[u8; SALT_LEN],
    params: KdfParams,
) -> Result<DataKey, StoreError> {
    if passphrase.chars().count() < MIN_PASSPHRASE_CHARS {
        return Err(StoreError::WeakPassphrase);
    }
    let p = Params::new(params.m_cost, params.t_cost, params.p_cost, Some(KEY_LEN))
        .map_err(|_| StoreError::Kdf)?;
    let mut out = Zeroizing::new([0u8; KEY_LEN]);
    Argon2::new(Algorithm::Argon2id, Version::V0x13, p)
        .hash_password_into(passphrase.as_bytes(), salt, out.as_mut())
        .map_err(|_| StoreError::Kdf)?;
    Ok(DataKey(out))
}
