use std::path::{Path, PathBuf};

use chrono::{DateTime, Utc};
use rusqlite::{Connection, OptionalExtension, params};
use zeroize::Zeroizing;

use crate::crypto::{self, DataKey, random_bytes};
use crate::error::StoreError;
use crate::kdf::{KdfParams, SALT_LEN, derive_key};

const RECORD_AD: &[u8] = b"manotu-desktop-record-v1";
const CHECK_AD: &[u8] = b"manotu-desktop-key-check-v1";
const CHECK_PLAINTEXT: &[u8] = b"manotu-desktop-key-check";
const NO_EXPIRY: i64 = i64::MIN;

/// `(kind, id, nonce, blob)` as stored.
type RawRow = (String, String, Vec<u8>, Vec<u8>);

/// How the data key is protected.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KeyMode {
    /// Random key held by the OS key store (macOS Keychain, Windows Credential Manager,
    /// Secret Service on Linux).
    Keychain,
    /// Argon2id(passphrase); used when no secure key store exists.
    Passphrase,
}

impl KeyMode {
    fn as_str(self) -> &'static str {
        match self {
            KeyMode::Keychain => "keychain",
            KeyMode::Passphrase => "passphrase",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VaultState {
    /// No key has been set up yet (fresh install or after "delete all local data").
    Uninitialized,
    Locked(KeyMode),
    Unlocked(KeyMode),
}

/// A decrypted record. `data` is zeroized on drop.
#[derive(Debug)]
pub struct Record {
    pub kind: String,
    pub id: String,
    pub expires_at: Option<DateTime<Utc>>,
    pub data: Zeroizing<Vec<u8>>,
}

pub struct Store {
    conn: Connection,
    path: PathBuf,
    key: Option<DataKey>,
}

fn record_ad(kind: &str, id: &str) -> Vec<u8> {
    let mut ad = Vec::with_capacity(RECORD_AD.len() + kind.len() + id.len() + 2);
    ad.extend_from_slice(RECORD_AD);
    ad.push(0);
    ad.extend_from_slice(kind.as_bytes());
    ad.push(0);
    ad.extend_from_slice(id.as_bytes());
    ad
}

impl Store {
    /// Opens (or creates) the database file. The vault stays locked until a key is supplied.
    pub fn open(path: impl AsRef<Path>) -> Result<Self, StoreError> {
        let path = path.as_ref().to_path_buf();
        if let Some(dir) = path.parent() {
            std::fs::create_dir_all(dir)?;
        }
        let conn = Connection::open(&path)?;
        Self::init(conn, path)
    }

    /// In-memory database, for tests.
    pub fn open_in_memory() -> Result<Self, StoreError> {
        Self::init(Connection::open_in_memory()?, PathBuf::new())
    }

    fn init(conn: Connection, path: PathBuf) -> Result<Self, StoreError> {
        conn.execute_batch(
            "PRAGMA journal_mode=DELETE;
             PRAGMA secure_delete=ON;
             PRAGMA temp_store=MEMORY;
             CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v BLOB NOT NULL);
             CREATE TABLE IF NOT EXISTS records (
               kind TEXT NOT NULL, id TEXT NOT NULL, nonce BLOB NOT NULL, blob BLOB NOT NULL,
               PRIMARY KEY (kind, id));",
        )?;
        Ok(Self {
            conn,
            path,
            key: None,
        })
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    fn meta(&self, k: &str) -> Result<Option<Vec<u8>>, StoreError> {
        Ok(self
            .conn
            .query_row("SELECT v FROM meta WHERE k = ?1", [k], |r| r.get(0))
            .optional()?)
    }

    fn set_meta(&self, k: &str, v: &[u8]) -> Result<(), StoreError> {
        self.conn.execute(
            "INSERT INTO meta (k, v) VALUES (?1, ?2) ON CONFLICT(k) DO UPDATE SET v = excluded.v",
            params![k, v],
        )?;
        Ok(())
    }

    pub fn key_mode(&self) -> Result<Option<KeyMode>, StoreError> {
        Ok(match self.meta("key_mode")?.as_deref() {
            Some(b"keychain") => Some(KeyMode::Keychain),
            Some(b"passphrase") => Some(KeyMode::Passphrase),
            _ => None,
        })
    }

    pub fn state(&self) -> Result<VaultState, StoreError> {
        Ok(match (self.key_mode()?, self.key.is_some()) {
            (None, _) => VaultState::Uninitialized,
            (Some(m), false) => VaultState::Locked(m),
            (Some(m), true) => VaultState::Unlocked(m),
        })
    }

    fn check_key(&self, key: &DataKey) -> Result<(), StoreError> {
        let nonce = self
            .meta("check_nonce")?
            .ok_or(StoreError::NotInitialized)?;
        let ct = self.meta("check_blob")?.ok_or(StoreError::NotInitialized)?;
        let pt = crypto::open(key, CHECK_AD, &nonce, &ct)?;
        if pt.as_slice() != CHECK_PLAINTEXT {
            return Err(StoreError::WrongKey);
        }
        Ok(())
    }

    fn initialize(&mut self, mode: KeyMode, key: DataKey) -> Result<(), StoreError> {
        if self.key_mode()?.is_some() {
            return Err(StoreError::AlreadyInitialized);
        }
        let (nonce, ct) = crypto::seal(&key, CHECK_AD, CHECK_PLAINTEXT)?;
        let tx = self.conn.transaction()?;
        for (k, v) in [
            ("check_nonce", nonce.as_slice()),
            ("check_blob", ct.as_slice()),
            ("key_mode", mode.as_str().as_bytes()),
        ] {
            tx.execute(
                "INSERT INTO meta (k, v) VALUES (?1, ?2) ON CONFLICT(k) DO UPDATE SET v = excluded.v",
                params![k, v],
            )?;
        }
        tx.commit()?;
        self.key = Some(key);
        Ok(())
    }

    /// First run with an OS key store: `key` is the random key just saved in the keychain.
    pub fn initialize_with_key(&mut self, key: DataKey) -> Result<(), StoreError> {
        self.initialize(KeyMode::Keychain, key)
    }

    /// First run without a secure key store: derive the key from a passphrase.
    pub fn initialize_with_passphrase(
        &mut self,
        passphrase: &str,
        params: KdfParams,
    ) -> Result<(), StoreError> {
        if self.key_mode()?.is_some() {
            return Err(StoreError::AlreadyInitialized);
        }
        let salt = random_bytes::<SALT_LEN>()?;
        let key = derive_key(passphrase, &salt, params)?;
        self.set_meta("kdf_salt", &salt)?;
        self.set_meta("kdf_params", &params.to_bytes())?;
        self.initialize(KeyMode::Passphrase, key)
    }

    /// Unlocks a keychain vault with the key read from the OS key store.
    pub fn unlock_with_key(&mut self, key: DataKey) -> Result<(), StoreError> {
        match self.key_mode()? {
            None => return Err(StoreError::NotInitialized),
            Some(KeyMode::Passphrase) => return Err(StoreError::WrongMode),
            Some(KeyMode::Keychain) => {}
        }
        self.check_key(&key)?;
        self.key = Some(key);
        Ok(())
    }

    pub fn unlock_with_passphrase(&mut self, passphrase: &str) -> Result<(), StoreError> {
        match self.key_mode()? {
            None => return Err(StoreError::NotInitialized),
            Some(KeyMode::Keychain) => return Err(StoreError::WrongMode),
            Some(KeyMode::Passphrase) => {}
        }
        let salt: [u8; SALT_LEN] = self
            .meta("kdf_salt")?
            .and_then(|s| s.try_into().ok())
            .ok_or(StoreError::NotInitialized)?;
        let params = self
            .meta("kdf_params")?
            .and_then(|b| KdfParams::from_bytes(&b))
            .ok_or(StoreError::NotInitialized)?;
        let key = derive_key(passphrase, &salt, params).map_err(|e| match e {
            // A short passphrase can never be the right one; don't reveal the length rule here.
            StoreError::WeakPassphrase => StoreError::WrongKey,
            e => e,
        })?;
        self.check_key(&key)?;
        self.key = Some(key);
        Ok(())
    }

    /// Drops the key from memory.
    pub fn lock(&mut self) {
        self.key = None;
    }

    pub fn is_unlocked(&self) -> bool {
        self.key.is_some()
    }

    fn key(&self) -> Result<&DataKey, StoreError> {
        self.key.as_ref().ok_or(StoreError::Locked)
    }

    /// Inserts or replaces a record. `expires_at` is encrypted with the data.
    pub fn put(
        &self,
        kind: &str,
        id: &str,
        expires_at: Option<DateTime<Utc>>,
        data: &[u8],
    ) -> Result<(), StoreError> {
        let key = self.key()?;
        let exp = expires_at.map_or(NO_EXPIRY, |t| t.timestamp());
        let mut pt = Zeroizing::new(Vec::with_capacity(8 + data.len()));
        pt.extend_from_slice(&exp.to_be_bytes());
        pt.extend_from_slice(data);
        let (nonce, ct) = crypto::seal(key, &record_ad(kind, id), &pt)?;
        self.conn.execute(
            "INSERT INTO records (kind, id, nonce, blob) VALUES (?1, ?2, ?3, ?4)
             ON CONFLICT(kind, id) DO UPDATE SET nonce = excluded.nonce, blob = excluded.blob",
            params![kind, id, nonce.as_slice(), ct],
        )?;
        Ok(())
    }

    fn decrypt(
        &self,
        kind: String,
        id: String,
        nonce: &[u8],
        ct: &[u8],
    ) -> Result<Record, StoreError> {
        let pt = crypto::open(self.key()?, &record_ad(&kind, &id), nonce, ct)?;
        if pt.len() < 8 {
            return Err(StoreError::WrongKey);
        }
        let exp = i64::from_be_bytes(pt[..8].try_into().expect("8 bytes"));
        let expires_at = if exp == NO_EXPIRY {
            None
        } else {
            DateTime::from_timestamp(exp, 0)
        };
        Ok(Record {
            kind,
            id,
            expires_at,
            data: Zeroizing::new(pt[8..].to_vec()),
        })
    }

    fn rows(&self, kind: Option<&str>) -> Result<Vec<RawRow>, StoreError> {
        let mut stmt = self.conn.prepare(
            "SELECT kind, id, nonce, blob FROM records WHERE ?1 IS NULL OR kind = ?1 ORDER BY kind, id",
        )?;
        let rows = stmt
            .query_map([kind], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)))?
            .collect::<Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    /// The record, unless it is missing or expired at `now`.
    pub fn get(
        &self,
        kind: &str,
        id: &str,
        now: DateTime<Utc>,
    ) -> Result<Option<Record>, StoreError> {
        let row: Option<(Vec<u8>, Vec<u8>)> = self
            .conn
            .query_row(
                "SELECT nonce, blob FROM records WHERE kind = ?1 AND id = ?2",
                [kind, id],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .optional()?;
        let Some((nonce, ct)) = row else {
            return Ok(None);
        };
        let rec = self.decrypt(kind.to_owned(), id.to_owned(), &nonce, &ct)?;
        Ok((!expired(&rec, now)).then_some(rec))
    }

    /// Every unexpired record of `kind`.
    pub fn list(&self, kind: &str, now: DateTime<Utc>) -> Result<Vec<Record>, StoreError> {
        self.key()?;
        let mut out = Vec::new();
        for (k, id, nonce, ct) in self.rows(Some(kind))? {
            let rec = self.decrypt(k, id, &nonce, &ct)?;
            if !expired(&rec, now) {
                out.push(rec);
            }
        }
        Ok(out)
    }

    pub fn delete(&self, kind: &str, id: &str) -> Result<bool, StoreError> {
        Ok(self.conn.execute(
            "DELETE FROM records WHERE kind = ?1 AND id = ?2",
            [kind, id],
        )? > 0)
    }

    /// Deletes every record whose expiry is at or before `now`; returns `(kind, id)` of each.
    /// Records that no longer decrypt are left alone (they cannot be read either).
    pub fn purge_expired(&self, now: DateTime<Utc>) -> Result<Vec<(String, String)>, StoreError> {
        self.key()?;
        let mut gone = Vec::new();
        for (kind, id, nonce, ct) in self.rows(None)? {
            if let Ok(rec) = self.decrypt(kind, id, &nonce, &ct)
                && expired(&rec, now)
            {
                self.delete(&rec.kind, &rec.id)?;
                gone.push((rec.kind, rec.id));
            }
        }
        if !gone.is_empty() {
            self.conn.execute_batch("VACUUM;")?;
        }
        Ok(gone)
    }

    /// Deletes every record and the key setup, then the database file itself.
    pub fn destroy(mut self) -> Result<(), StoreError> {
        self.key = None;
        self.conn
            .execute_batch("DELETE FROM records; DELETE FROM meta; VACUUM;")?;
        let path = std::mem::take(&mut self.path);
        drop(self.conn);
        if !path.as_os_str().is_empty() {
            for p in [path.clone(), path.with_extension("db-journal")] {
                match std::fs::remove_file(&p) {
                    Ok(()) => {}
                    Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
                    Err(e) => return Err(e.into()),
                }
            }
        }
        Ok(())
    }

    /// Raw blobs, for tests that check nothing is stored in plaintext.
    #[cfg(test)]
    pub(crate) fn raw_dump(&self) -> Result<Vec<u8>, StoreError> {
        let mut out = Vec::new();
        for (kind, id, nonce, ct) in self.rows(None)? {
            out.extend_from_slice(kind.as_bytes());
            out.extend_from_slice(id.as_bytes());
            out.extend_from_slice(&nonce);
            out.extend_from_slice(&ct);
        }
        Ok(out)
    }

    #[cfg(test)]
    pub(crate) fn swap_blobs_for_test(
        &self,
        a: (&str, &str),
        b: (&str, &str),
    ) -> Result<(), StoreError> {
        let get = |k: &str, i: &str| -> Result<(Vec<u8>, Vec<u8>), StoreError> {
            Ok(self.conn.query_row(
                "SELECT nonce, blob FROM records WHERE kind = ?1 AND id = ?2",
                [k, i],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )?)
        };
        let (ra, rb) = (get(a.0, a.1)?, get(b.0, b.1)?);
        for ((k, i), (n, c)) in [(a, rb), (b, ra)] {
            self.conn.execute(
                "UPDATE records SET nonce = ?3, blob = ?4 WHERE kind = ?1 AND id = ?2",
                params![k, i, n, c],
            )?;
        }
        Ok(())
    }
}

fn expired(rec: &Record, now: DateTime<Utc>) -> bool {
    rec.expires_at.is_some_and(|t| now >= t)
}
