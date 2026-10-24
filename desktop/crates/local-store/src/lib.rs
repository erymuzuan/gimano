//! Encrypted local persistence for Manotu Desktop.
//!
//! ```text
//! data key (32 B) ── OS keychain (keyring)            ┐
//!                 └─ or Argon2id(passphrase, salt)   ├─► XChaCha20-Poly1305 per record
//!                                                     ┘   AD = "manotu-desktop-record-v1" ‖ kind ‖ id
//! SQLite file: meta(k, v) · records(kind, id, nonce, blob)
//! ```
//!
//! - Nothing is stored in plaintext: titles, coordinates, notes and expiry dates all live inside
//!   the encrypted blob. Only the record kind and a random id are visible on disk.
//! - The associated data binds a blob to its row, so swapping blobs between rows fails to decrypt.
//! - A key-check blob detects a wrong key before anything is read.
//! - SQLite runs with `journal_mode=DELETE`, `secure_delete=ON` and in-memory temp storage, so no
//!   WAL file or temp file keeps old pages (the blobs are encrypted in any case).
//! - Expired records are purged with [`Store::purge_expired`] (the app calls it on unlock) and are
//!   never returned by [`Store::get`] / [`Store::list`].
//! - There is no plaintext fallback: without a keychain the caller must use a passphrase.

mod crypto;
mod error;
mod kdf;
#[cfg(feature = "keychain")]
pub mod keychain;
mod recovery;
mod store;
#[cfg(test)]
mod tests;

pub use crypto::{DataKey, KEY_LEN, NONCE_LEN};
pub use error::StoreError;
pub use kdf::{KdfParams, SALT_LEN, derive_key};
pub use recovery::{RECOVERY_EXTENSION, RECOVERY_MAGIC, open_recovery, seal_recovery};
pub use store::{KeyMode, Record, Store, VaultState};
