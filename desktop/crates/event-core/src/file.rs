//! `.manotu-event` file (§5.2).
//!
//! ```text
//! suite 3 (default):    "MNTE" ‖ 0x01 format ‖ 0x03 suite ‖ key(32) ‖ nonce(24) ‖ ciphertext
//! suite 2 (sensitive):  "MNTE" ‖ 0x01 format ‖ 0x02 suite ‖ salt(16) ‖ nonce(24) ‖ ciphertext
//! suite 1 (read only):  "MNTE" ‖ 0x01 format ‖ 0x01 suite ‖ nonce(24) ‖ ciphertext
//! ```
//!
//! Suite 3 carries its own key: key, nonce and ciphertext are exactly the link's bytes (§5.1), so
//! the file and the link of one publication are interchangeable and need no code.
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
/// Suite 3: the 32-byte pack key is in the file (same bytes as the link). No code.
pub const FILE_SUITE_OPEN: u8 = 0x03;
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
/// Bytes of a suite-3 file around the ciphertext (header, key, nonce).
pub const FILE_V3_OVERHEAD: usize = HEADER_LEN + 32 + NONCE_LEN;

/// How a file's key is obtained.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FileSuite {
    /// Suite 1: the 52-character code is the key.
    RawKey,
    /// Suite 2: the key is derived from the 8-character code with this salt.
    Code { salt: [u8; FILE_SALT_LEN] },
    /// Suite 3: the key travels in the file; no code.
    Open { key: PackKey },
}

impl FileSuite {
    /// Whether opening needs a code from the host (suites 1 and 2).
    pub fn needs_code(&self) -> bool {
        !matches!(self, FileSuite::Open { .. })
    }
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

/// Frames a pack sealed under `key` as a suite-3 (open) file: the link's key, nonce and
/// ciphertext behind a 6-byte header. Only the 20 MiB file limit applies, not the link's 16 000
/// characters.
pub fn encode_open_file(key: &PackKey, sealed: &SealedPack) -> Result<Vec<u8>, Error> {
    let len = FILE_V3_OVERHEAD + sealed.ciphertext.len();
    if len > MAX_FILE_BYTES {
        return Err(Error::FileTooLarge {
            max: MAX_FILE_BYTES,
        });
    }
    let mut out = Vec::with_capacity(len);
    out.extend_from_slice(FILE_MAGIC);
    out.push(FILE_FORMAT_V1);
    out.push(FILE_SUITE_OPEN);
    out.extend_from_slice(key.as_bytes());
    out.extend_from_slice(&sealed.nonce);
    out.extend_from_slice(&sealed.ciphertext);
    Ok(out)
}

/// The writer path for the default share (§5.2.1): a fresh pack key and nonce, then the suite-3
/// file. The returned key and sealed pack are the same publication's link
/// ([`crate::encode_link`]), so app and web viewer get interchangeable copies. Pass full event
/// bytes; suite 3 is never a stops-only variant. The file is opened again before it is returned.
pub fn seal_open_file(
    event_bytes: &[u8],
    organizer: &OrganizerKey,
) -> Result<(PackKey, SealedPack, Vec<u8>), Error> {
    if crate::model::Event::from_bytes(event_bytes)?
        .variant
        .is_some()
    {
        return Err(Error::NotFullEvent);
    }
    let key = PackKey::generate()?;
    let parts = seal_parts(event_bytes, organizer, &key, random_nonce()?)?;
    let file = encode_open_file(&key, &parts.sealed)?;
    let check = open_file_without_code(&file)?;
    if check.event_bytes != event_bytes {
        return Err(Error::MalformedEnvelope);
    }
    Ok((key, parts.sealed, file))
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
        FILE_SUITE_OPEN => {
            let rest = &bytes[HEADER_LEN..];
            if rest.len() < 32 {
                return Err(Error::MalformedFile);
            }
            let key = PackKey::from_bytes(rest[..32].try_into().expect("length checked"));
            (FileSuite::Open { key }, &rest[32..])
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

/// The key for a parsed file from the code the host sent through another channel. A suite-3
/// file carries its key; the code is ignored.
pub fn file_key(suite: &FileSuite, secret_code: &str) -> Result<PackKey, Error> {
    match suite {
        FileSuite::Open { key } => Ok(key.clone()),
        FileSuite::RawKey => PackKey::from_secret_code(secret_code),
        FileSuite::Code { salt } => derive_file_key(secret_code, salt),
    }
}

/// Whether opening this file needs a code: `false` for suite 3, `true` for suites 1 and 2.
/// Fails like [`parse_file`] on a malformed or unsupported file, so apps can report that before
/// prompting for a code.
pub fn file_needs_code(bytes: &[u8]) -> Result<bool, Error> {
    Ok(parse_file(bytes)?.suite.needs_code())
}

/// Opens a suite-3 file, which carries its key. Suites 1 and 2 fail with
/// [`Error::InvalidSecretCode`]`("a code is required")`.
pub fn open_file_without_code(bytes: &[u8]) -> Result<OpenedPack, Error> {
    let parsed = parse_file(bytes)?;
    match &parsed.suite {
        FileSuite::Open { key } => open(key, &parsed.sealed),
        _ => Err(Error::InvalidSecretCode("a code is required")),
    }
}

/// Opens a file of any suite with its secret code (8 characters for suite 2, 52 for suite 1).
/// A suite-3 file ignores `secret_code` (any value, also empty, opens it), so callers may pass
/// whatever they have; check [`file_needs_code`] first to avoid prompting.
/// Suite 2 runs Argon2id (64 MiB, a fraction of a second): call it off the UI thread.
pub fn open_file(bytes: &[u8], secret_code: &str) -> Result<OpenedPack, Error> {
    let parsed = parse_file(bytes)?;
    let key = file_key(&parsed.suite, secret_code)?;
    open(&key, &parsed.sealed)
}
