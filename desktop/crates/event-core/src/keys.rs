use ed25519_dalek::{Signer, SigningKey};
use zeroize::Zeroize;

use crate::codes::{decode_secret_code, encode_secret_code};
use crate::encoding::b64u;
use crate::error::Error;
use crate::validate::limits::{EVENT_ID_BYTES, ITEM_ID_BYTES};

pub const NONCE_LEN: usize = 24;

fn random<const N: usize>() -> Result<[u8; N], Error> {
    let mut b = [0u8; N];
    getrandom::getrandom(&mut b).map_err(|_| Error::Random)?;
    Ok(b)
}

pub fn random_nonce() -> Result<[u8; NONCE_LEN], Error> {
    random()
}

/// New event id: b64u of 16 random bytes.
pub fn new_event_id() -> Result<String, Error> {
    Ok(b64u(&random::<EVENT_ID_BYTES>()?))
}

/// New stop / session / route id: b64u of 8 random bytes.
pub fn new_item_id() -> Result<String, Error> {
    Ok(b64u(&random::<ITEM_ID_BYTES>()?))
}

/// The organizer's Ed25519 key for one event; never reuse it for another event.
/// The secret is zeroized on drop.
pub struct OrganizerKey(SigningKey);

impl OrganizerKey {
    pub fn generate() -> Result<Self, Error> {
        let mut seed = random::<32>()?;
        let key = Self::from_seed(&seed);
        seed.zeroize();
        Ok(key)
    }

    pub fn from_seed(seed: &[u8; 32]) -> Self {
        Self(SigningKey::from_bytes(seed))
    }

    /// The 32-byte seed, for the encrypted local key store only.
    pub fn to_seed(&self) -> [u8; 32] {
        self.0.to_bytes()
    }

    pub fn public_key(&self) -> [u8; 32] {
        self.0.verifying_key().to_bytes()
    }

    pub(crate) fn sign(&self, msg: &[u8]) -> [u8; 64] {
        self.0.sign(msg).to_bytes()
    }
}

impl std::fmt::Debug for OrganizerKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("OrganizerKey").finish_non_exhaustive()
    }
}

/// The 32-byte XChaCha20-Poly1305 pack key. Zeroized on drop.
#[derive(Clone, PartialEq, Eq)]
pub struct PackKey([u8; 32]);

impl PackKey {
    pub fn generate() -> Result<Self, Error> {
        Ok(Self(random()?))
    }

    pub fn from_bytes(bytes: [u8; 32]) -> Self {
        Self(bytes)
    }

    pub fn as_bytes(&self) -> &[u8; 32] {
        &self.0
    }

    pub fn to_secret_code(&self) -> String {
        encode_secret_code(&self.0)
    }

    pub fn from_secret_code(code: &str) -> Result<Self, Error> {
        decode_secret_code(code).map(Self)
    }
}

impl Drop for PackKey {
    fn drop(&mut self) {
        self.0.zeroize();
    }
}

impl std::fmt::Debug for PackKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("PackKey").finish_non_exhaustive()
    }
}
