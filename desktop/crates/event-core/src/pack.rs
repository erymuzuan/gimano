//! Signed envelope, raw DEFLATE and XChaCha20-Poly1305 (docs/events.md §2–§3).

use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use ed25519_dalek::{Signature, VerifyingKey};
use miniz_oxide::inflate::TINFLStatus;
use serde::{Deserialize, Serialize};

use crate::codes::safety_code;
use crate::encoding::{b64u, b64u_array, b64u_decode};
use crate::error::Error;
use crate::keys::{NONCE_LEN, OrganizerKey, PackKey, random_nonce};
use crate::model::Event;
use crate::revision::revision_hash;

/// Associated data for XChaCha20-Poly1305.
pub const AEAD_AD: &[u8] = b"manotu-event-v1";
/// The organizer signs `SIGNATURE_PREFIX ‖ event bytes`.
pub const SIGNATURE_PREFIX: &[u8] = b"manotu-event-v1\n";
/// Inflate output bound (20 MiB).
pub const MAX_INFLATED_BYTES: usize = 20 * 1024 * 1024;
const DEFLATE_LEVEL: u8 = 9;
const TAG_LEN: usize = 16;

/// `{"v":1,"event":b64u,"pub":b64u(32),"sig":b64u(64)}`
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Envelope {
    pub v: u64,
    pub event: String,
    #[serde(rename = "pub")]
    pub organizer_pub: String,
    pub sig: String,
}

/// What travels in a link or file, next to the key.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SealedPack {
    pub nonce: [u8; NONCE_LEN],
    /// Ciphertext followed by the 16-byte Poly1305 tag.
    pub ciphertext: Vec<u8>,
}

/// Every intermediate layer; used for test vectors and diagnostics.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SealedParts {
    pub event_bytes: Vec<u8>,
    pub signature: [u8; 64],
    pub envelope_bytes: Vec<u8>,
    pub deflated: Vec<u8>,
    pub sealed: SealedPack,
}

/// A decrypted pack whose signature verified and whose event passed validation.
#[derive(Debug, Clone, PartialEq)]
pub struct OpenedPack {
    pub event: Event,
    /// The exact signed bytes; store these, never a re-serialization.
    pub event_bytes: Vec<u8>,
    pub organizer_pub: [u8; 32],
    pub revision_hash: [u8; 32],
}

impl OpenedPack {
    pub fn safety_code(&self) -> String {
        safety_code(&self.organizer_pub)
    }
}

pub fn deflate_raw(data: &[u8]) -> Vec<u8> {
    miniz_oxide::deflate::compress_to_vec(data, DEFLATE_LEVEL)
}

/// Raw DEFLATE decode, refusing output larger than `max`.
pub fn inflate_raw(data: &[u8], max: usize) -> Result<Vec<u8>, Error> {
    miniz_oxide::inflate::decompress_to_vec_with_limit(data, max).map_err(|e| match e.status {
        TINFLStatus::HasMoreOutput => Error::InflateTooLarge { max },
        _ => Error::Inflate,
    })
}

pub fn encrypt(key: &PackKey, nonce: &[u8; NONCE_LEN], plaintext: &[u8]) -> Result<Vec<u8>, Error> {
    XChaCha20Poly1305::new(key.as_bytes().into())
        .encrypt(
            XNonce::from_slice(nonce),
            Payload {
                msg: plaintext,
                aad: AEAD_AD,
            },
        )
        .map_err(|_| Error::Decrypt)
}

pub fn decrypt(
    key: &PackKey,
    nonce: &[u8; NONCE_LEN],
    ciphertext: &[u8],
) -> Result<Vec<u8>, Error> {
    if ciphertext.len() < TAG_LEN {
        return Err(Error::Decrypt);
    }
    XChaCha20Poly1305::new(key.as_bytes().into())
        .decrypt(
            XNonce::from_slice(nonce),
            Payload {
                msg: ciphertext,
                aad: AEAD_AD,
            },
        )
        .map_err(|_| Error::Decrypt)
}

/// Signs, compresses and encrypts already-serialized event bytes with an explicit nonce.
/// The bytes must form a valid event. Never reuse a nonce with the same key.
pub fn seal_parts(
    event_bytes: &[u8],
    organizer: &OrganizerKey,
    key: &PackKey,
    nonce: [u8; NONCE_LEN],
) -> Result<SealedParts, Error> {
    Event::from_bytes(event_bytes)?;
    let mut msg = Vec::with_capacity(SIGNATURE_PREFIX.len() + event_bytes.len());
    msg.extend_from_slice(SIGNATURE_PREFIX);
    msg.extend_from_slice(event_bytes);
    let signature = organizer.sign(&msg);
    let envelope = Envelope {
        v: 1,
        event: b64u(event_bytes),
        organizer_pub: b64u(&organizer.public_key()),
        sig: b64u(&signature),
    };
    let envelope_bytes = serde_json::to_vec(&envelope).map_err(|_| Error::MalformedEnvelope)?;
    let deflated = deflate_raw(&envelope_bytes);
    let ciphertext = encrypt(key, &nonce, &deflated)?;
    Ok(SealedParts {
        event_bytes: event_bytes.to_vec(),
        signature,
        envelope_bytes,
        deflated,
        sealed: SealedPack { nonce, ciphertext },
    })
}

/// Validates, signs and encrypts an event with a fresh nonce, then opens the result again to
/// prove it verifies before anything is offered to the user.
pub fn seal(event: &Event, organizer: &OrganizerKey, key: &PackKey) -> Result<SealedPack, Error> {
    let bytes = event.to_bytes()?;
    let parts = seal_parts(&bytes, organizer, key, random_nonce()?)?;
    let check = open(key, &parts.sealed)?;
    debug_assert_eq!(check.event_bytes, bytes);
    Ok(parts.sealed)
}

/// Decrypt → bounded inflate → envelope → signature → event (§7 steps 1–2).
pub fn open(key: &PackKey, sealed: &SealedPack) -> Result<OpenedPack, Error> {
    let deflated = decrypt(key, &sealed.nonce, &sealed.ciphertext)?;
    let envelope_bytes = inflate_raw(&deflated, MAX_INFLATED_BYTES)?;
    let env: Envelope =
        serde_json::from_slice(&envelope_bytes).map_err(|_| Error::MalformedEnvelope)?;
    if env.v != 1 {
        return Err(Error::UnsupportedEnvelopeVersion(env.v));
    }
    let organizer_pub: [u8; 32] = b64u_array(&env.organizer_pub).ok_or(Error::MalformedEnvelope)?;
    let sig: [u8; 64] = b64u_array(&env.sig).ok_or(Error::MalformedEnvelope)?;
    let event_bytes = b64u_decode(&env.event).ok_or(Error::MalformedEnvelope)?;

    let vk = VerifyingKey::from_bytes(&organizer_pub).map_err(|_| Error::BadSignature)?;
    let mut msg = Vec::with_capacity(SIGNATURE_PREFIX.len() + event_bytes.len());
    msg.extend_from_slice(SIGNATURE_PREFIX);
    msg.extend_from_slice(&event_bytes);
    vk.verify_strict(&msg, &Signature::from_bytes(&sig))
        .map_err(|_| Error::BadSignature)?;

    let event = Event::from_bytes(&event_bytes)?;
    Ok(OpenedPack {
        revision_hash: revision_hash(&event_bytes),
        event,
        event_bytes,
        organizer_pub,
    })
}
