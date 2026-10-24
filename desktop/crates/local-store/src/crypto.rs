use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use zeroize::{Zeroize, Zeroizing};

use crate::error::StoreError;

pub const KEY_LEN: usize = 32;
pub const NONCE_LEN: usize = 24;

/// The 32-byte local data key. Zeroized on drop; never serialized, logged or sent to the UI.
#[derive(Clone)]
pub struct DataKey(pub(crate) Zeroizing<[u8; KEY_LEN]>);

impl DataKey {
    pub fn generate() -> Result<Self, StoreError> {
        let mut b = [0u8; KEY_LEN];
        getrandom::getrandom(&mut b).map_err(|_| StoreError::Random)?;
        let key = Self::from_bytes(b);
        b.zeroize();
        Ok(key)
    }

    pub fn from_bytes(bytes: [u8; KEY_LEN]) -> Self {
        Self(Zeroizing::new(bytes))
    }

    pub fn as_bytes(&self) -> &[u8; KEY_LEN] {
        &self.0
    }
}

impl std::fmt::Debug for DataKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("DataKey").finish_non_exhaustive()
    }
}

pub(crate) fn random_bytes<const N: usize>() -> Result<[u8; N], StoreError> {
    let mut b = [0u8; N];
    getrandom::getrandom(&mut b).map_err(|_| StoreError::Random)?;
    Ok(b)
}

pub(crate) fn seal(
    key: &DataKey,
    ad: &[u8],
    plaintext: &[u8],
) -> Result<([u8; NONCE_LEN], Vec<u8>), StoreError> {
    let nonce = random_bytes::<NONCE_LEN>()?;
    let ct = XChaCha20Poly1305::new(key.as_bytes().into())
        .encrypt(
            XNonce::from_slice(&nonce),
            Payload {
                msg: plaintext,
                aad: ad,
            },
        )
        .map_err(|_| StoreError::WrongKey)?;
    Ok((nonce, ct))
}

pub(crate) fn open(
    key: &DataKey,
    ad: &[u8],
    nonce: &[u8],
    ciphertext: &[u8],
) -> Result<Zeroizing<Vec<u8>>, StoreError> {
    if nonce.len() != NONCE_LEN {
        return Err(StoreError::WrongKey);
    }
    XChaCha20Poly1305::new(key.as_bytes().into())
        .decrypt(
            XNonce::from_slice(nonce),
            Payload {
                msg: ciphertext,
                aad: ad,
            },
        )
        .map(Zeroizing::new)
        .map_err(|_| StoreError::WrongKey)
}
