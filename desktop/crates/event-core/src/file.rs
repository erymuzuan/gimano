//! `.manotu-event` file (§5.2).
//!
//! ```text
//! suite 2 (written):    "MNTE" ‖ 0x01 format ‖ 0x02 suite ‖ salt(16) ‖ nonce(24) ‖ ciphertext
//! suite 1 (read only):  "MNTE" ‖ 0x01 format ‖ 0x01 suite ‖ nonce(24) ‖ ciphertext
//! ```
//!
//! Suite 2's key is Argon2id13 (libsodium `crypto_pwhash`, opslimit 3, 64 MiB) over the 8-character
//! secret code and the file's salt; suite 1's 52-character code is the raw key.

use argon2::{Algorithm, Argon2, Params, Version};
use zeroize::Zeroizing;

use crate::codes::parse_secret_code_v2;
use crate::error::Error;
use crate::keys::{NONCE_LEN, OrganizerKey, PackKey, random_nonce};
use crate::pack::{OpenedPack, SealedPack, SealedParts, open, seal_parts};
use crate::validate::limits::MAX_FILE_BYTES;

pub const FILE_MAGIC: &[u8; 4] = b"MNTE";
pub const FILE_FORMAT_V1: u8 = 0x01;
/// Suite 1: raw key, shared as a 52-character Crockford code. Read only.
pub const FILE_SUITE_RAW_KEY: u8 = 0x01;
/// Suite 2: key derived with Argon2id from an 8-character code and the file's salt.
pub const FILE_SUITE_CODE: u8 = 0x02;
pub const FILE_SALT_LEN: usize = 16;
pub const FILE_EXTENSION: &str = "manotu-event";
pub const FILE_MIME: &str = "application/vnd.manotu.event";
const HEADER_LEN: usize = 6;
const TAG_LEN: usize = 16;

/// libsodium `crypto_pwhash` with `opslimit = 3`, `memlimit = 64 MiB`, `ALG_ARGON2ID13`:
/// Argon2id v1.3, t = 3, m = 65 536 KiB, one lane, 32-byte output.
pub const FILE_KDF_OPSLIMIT: u32 = 3;
pub const FILE_KDF_MEMLIMIT_KIB: u32 = 64 * 1024;

/// Bytes of a suite-2 file around the ciphertext (header, salt, nonce).
pub const FILE_V2_OVERHEAD: usize = HEADER_LEN + FILE_SALT_LEN + NONCE_LEN;

/// How a file's key is obtained.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FileSuite {
    /// Suite 1: the 52-character code is the key.
    RawKey,
    /// Suite 2: the key is derived from the 8-character code with this salt.
    Code { salt: [u8; FILE_SALT_LEN] },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParsedFile {
    pub suite: FileSuite,
    pub sealed: SealedPack,
}

/// The suite-2 file key: Argon2id13 over the code's 8 upper-case characters (no separator) with
/// the file salt. The code is checked first, so a typo fails as `invalid_secret_code` without the
/// (deliberately slow) derivation.
pub fn derive_file_key(code: &str, salt: &[u8; FILE_SALT_LEN]) -> Result<PackKey, Error> {
    let password = parse_secret_code_v2(code)?;
    let params = Params::new(FILE_KDF_MEMLIMIT_KIB, FILE_KDF_OPSLIMIT, 1, Some(32))
        .map_err(|_| Error::KeyDerivation)?;
    let mut out = Zeroizing::new([0u8; 32]);
    Argon2::new(Algorithm::Argon2id, Version::V0x13, params)
        .hash_password_into(password.as_bytes(), salt, out.as_mut())
        .map_err(|_| Error::KeyDerivation)?;
    Ok(PackKey::from_bytes(*out))
}

pub fn random_salt() -> Result<[u8; FILE_SALT_LEN], Error> {
    let mut b = [0u8; FILE_SALT_LEN];
    getrandom::getrandom(&mut b).map_err(|_| Error::Random)?;
    Ok(b)
}

/// Frames a pack sealed under [`derive_file_key`]`(code, salt)` as a suite-2 file.
pub fn encode_file(salt: &[u8; FILE_SALT_LEN], sealed: &SealedPack) -> Result<Vec<u8>, Error> {
    let len = FILE_V2_OVERHEAD + sealed.ciphertext.len();
    if len > MAX_FILE_BYTES {
        return Err(Error::FileTooLarge {
            max: MAX_FILE_BYTES,
        });
    }
    let mut out = Vec::with_capacity(len);
    out.extend_from_slice(FILE_MAGIC);
    out.push(FILE_FORMAT_V1);
    out.push(FILE_SUITE_CODE);
    out.extend_from_slice(salt);
    out.extend_from_slice(&sealed.nonce);
    out.extend_from_slice(&sealed.ciphertext);
    Ok(out)
}

/// Deterministic suite-2 writer for test vectors: derives the key, signs, compresses, encrypts and
/// frames. Never reuse a salt/nonce pair; [`seal_file`] picks fresh ones.
pub fn seal_file_parts(
    event_bytes: &[u8],
    organizer: &OrganizerKey,
    code: &str,
    salt: [u8; FILE_SALT_LEN],
    nonce: [u8; NONCE_LEN],
) -> Result<(SealedParts, Vec<u8>), Error> {
    let key = derive_file_key(code, &salt)?;
    let parts = seal_parts(event_bytes, organizer, &key, nonce)?;
    let file = encode_file(&salt, &parts.sealed)?;
    Ok((parts, file))
}

/// The writer path for files: exactly these event bytes, a fresh salt and nonce, the event's
/// stable secret code. The result is opened again to prove it verifies before it is offered.
pub fn seal_file(
    event_bytes: &[u8],
    organizer: &OrganizerKey,
    code: &str,
) -> Result<Vec<u8>, Error> {
    let salt = random_salt()?;
    let key = derive_file_key(code, &salt)?;
    let parts = seal_parts(event_bytes, organizer, &key, random_nonce()?)?;
    let check = open(&key, &parts.sealed)?;
    if check.event_bytes != event_bytes {
        return Err(Error::MalformedEnvelope);
    }
    encode_file(&salt, &parts.sealed)
}

pub fn parse_file(bytes: &[u8]) -> Result<ParsedFile, Error> {
    if bytes.len() > MAX_FILE_BYTES {
        return Err(Error::FileTooLarge {
            max: MAX_FILE_BYTES,
        });
    }
    if bytes.len() < HEADER_LEN || &bytes[..4] != FILE_MAGIC {
        return Err(Error::MalformedFile);
    }
    if bytes[4] != FILE_FORMAT_V1 {
        return Err(Error::UnsupportedFileFormat(bytes[4]));
    }
    let (suite, rest) = match bytes[5] {
        FILE_SUITE_RAW_KEY => (FileSuite::RawKey, &bytes[HEADER_LEN..]),
        FILE_SUITE_CODE => {
            let rest = &bytes[HEADER_LEN..];
            if rest.len() < FILE_SALT_LEN {
                return Err(Error::MalformedFile);
            }
            let salt = rest[..FILE_SALT_LEN].try_into().expect("length checked");
            (FileSuite::Code { salt }, &rest[FILE_SALT_LEN..])
        }
        other => return Err(Error::UnsupportedSuite(other)),
    };
    if rest.len() < NONCE_LEN + TAG_LEN {
        return Err(Error::MalformedFile);
    }
    let nonce: [u8; NONCE_LEN] = rest[..NONCE_LEN].try_into().expect("length checked");
    Ok(ParsedFile {
        suite,
        sealed: SealedPack {
            nonce,
            ciphertext: rest[NONCE_LEN..].to_vec(),
        },
    })
}

/// The key for a parsed file from the code the host sent through another channel.
pub fn file_key(suite: &FileSuite, secret_code: &str) -> Result<PackKey, Error> {
    match suite {
        FileSuite::RawKey => PackKey::from_secret_code(secret_code),
        FileSuite::Code { salt } => derive_file_key(secret_code, salt),
    }
}

/// Opens a file of either suite with its secret code (8 characters for suite 2, 52 for suite 1).
/// Suite 2 runs Argon2id (64 MiB, a fraction of a second): call it off the UI thread.
pub fn open_file(bytes: &[u8], secret_code: &str) -> Result<OpenedPack, Error> {
    let parsed = parse_file(bytes)?;
    let key = file_key(&parsed.suite, secret_code)?;
    open(&key, &parsed.sealed)
}
