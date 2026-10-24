//! The data key in the OS key store (`keyring` 3: macOS Keychain, Windows Credential Manager,
//! Secret Service on Linux). If the platform has no usable store, callers get
//! [`StoreError::KeychainUnavailable`] and must fall back to a passphrase, never to plaintext.

use keyring::{Entry, Error as KeyringError};
use zeroize::Zeroizing;

use crate::crypto::{DataKey, KEY_LEN};
use crate::error::StoreError;

pub const SERVICE: &str = "app.manotu.desktop";
pub const ACCOUNT: &str = "local-data-key";

fn entry() -> Result<Entry, StoreError> {
    Entry::new(SERVICE, ACCOUNT).map_err(unavailable)
}

fn unavailable(e: KeyringError) -> StoreError {
    StoreError::KeychainUnavailable(e.to_string())
}

/// Reads the key; `Ok(None)` if no key was ever stored.
pub fn load() -> Result<Option<DataKey>, StoreError> {
    match entry()?.get_secret() {
        Ok(secret) => {
            let secret = Zeroizing::new(secret);
            let bytes: [u8; KEY_LEN] = secret.as_slice().try_into().map_err(|_| {
                StoreError::KeychainUnavailable("stored key has the wrong length".into())
            })?;
            Ok(Some(DataKey::from_bytes(bytes)))
        }
        Err(KeyringError::NoEntry) => Ok(None),
        Err(e) => Err(unavailable(e)),
    }
}

/// Generates and stores a new random key, then reads it back to prove the store works.
pub fn create() -> Result<DataKey, StoreError> {
    let key = DataKey::generate()?;
    let e = entry()?;
    e.set_secret(key.as_bytes()).map_err(unavailable)?;
    match load()? {
        Some(k) if k.as_bytes() == key.as_bytes() => Ok(key),
        _ => {
            let _ = e.delete_credential();
            Err(StoreError::KeychainUnavailable(
                "the key store did not keep the key".into(),
            ))
        }
    }
}

pub fn delete() -> Result<(), StoreError> {
    match entry()?.delete_credential() {
        Ok(()) | Err(KeyringError::NoEntry) => Ok(()),
        Err(e) => Err(unavailable(e)),
    }
}
