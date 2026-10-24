//! `.manotu-event` file: `"MNTE" ‖ 0x01 format ‖ 0x01 suite ‖ nonce(24) ‖ ciphertext` (§5.2).

use crate::error::Error;
use crate::keys::{NONCE_LEN, PackKey};
use crate::pack::{OpenedPack, SealedPack, open};
use crate::validate::limits::MAX_FILE_BYTES;

pub const FILE_MAGIC: &[u8; 4] = b"MNTE";
pub const FILE_FORMAT_V1: u8 = 0x01;
/// Raw key, shared as a secret code.
pub const FILE_SUITE_RAW_KEY: u8 = 0x01;
pub const FILE_EXTENSION: &str = "manotu-event";
pub const FILE_MIME: &str = "application/vnd.manotu.event";
const HEADER_LEN: usize = 6;

pub fn encode_file(sealed: &SealedPack) -> Result<Vec<u8>, Error> {
    let len = HEADER_LEN + NONCE_LEN + sealed.ciphertext.len();
    if len > MAX_FILE_BYTES {
        return Err(Error::FileTooLarge {
            max: MAX_FILE_BYTES,
        });
    }
    let mut out = Vec::with_capacity(len);
    out.extend_from_slice(FILE_MAGIC);
    out.push(FILE_FORMAT_V1);
    out.push(FILE_SUITE_RAW_KEY);
    out.extend_from_slice(&sealed.nonce);
    out.extend_from_slice(&sealed.ciphertext);
    Ok(out)
}

pub fn parse_file(bytes: &[u8]) -> Result<SealedPack, Error> {
    if bytes.len() > MAX_FILE_BYTES {
        return Err(Error::FileTooLarge {
            max: MAX_FILE_BYTES,
        });
    }
    if bytes.len() < HEADER_LEN + NONCE_LEN + 16 || &bytes[..4] != FILE_MAGIC {
        return Err(Error::MalformedFile);
    }
    if bytes[4] != FILE_FORMAT_V1 {
        return Err(Error::UnsupportedFileFormat(bytes[4]));
    }
    if bytes[5] != FILE_SUITE_RAW_KEY {
        return Err(Error::UnsupportedSuite(bytes[5]));
    }
    let nonce: [u8; NONCE_LEN] = bytes[HEADER_LEN..HEADER_LEN + NONCE_LEN]
        .try_into()
        .expect("length checked");
    Ok(SealedPack {
        nonce,
        ciphertext: bytes[HEADER_LEN + NONCE_LEN..].to_vec(),
    })
}

/// Opens a file with the secret code the host sent through another channel.
pub fn open_file(bytes: &[u8], secret_code: &str) -> Result<OpenedPack, Error> {
    let sealed = parse_file(bytes)?;
    let key = PackKey::from_secret_code(secret_code)?;
    open(&key, &sealed)
}
