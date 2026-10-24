//! Manotu event packs (docs/events.md is the contract).
//!
//! ```text
//! event JSON bytes ─sign(Ed25519, per-event key)─► envelope JSON ─raw DEFLATE─►
//! XChaCha20-Poly1305(AD "manotu-event-v1") ─► invitation link (§5.1, random key) or
//! .manotu-event file (§5.2, key = Argon2id(8-character code, salt))
//! ```
//!
//! Privacy: a pack carries only guest-visible data. Organizer-only working notes deliberately have
//! no field anywhere in this crate's model, so they cannot be serialized into a pack; the desktop
//! app keeps them in its own local type next to the event, never inside [`Event`]. Unknown JSON
//! fields are ignored on read and never re-emitted, because readers keep the signed bytes as-is.

mod codes;
mod encoding;
mod error;
mod file;
mod import;
mod keys;
mod link;
mod model;
mod pack;
mod revision;
mod validate;

pub use codes::{
    SAFETY_ALPHABET, SECRET_CODE_V2_BYTES, SECRET_CODE_V2_CHARS, decode_secret_code,
    encode_secret_code, encode_secret_code_v2, generate_secret_code_v2, parse_secret_code_v2,
    safety_code,
};
pub use encoding::{b64u, b64u_decode};
pub use error::{Error, ValidationError};
pub use file::{
    FILE_EXTENSION, FILE_FORMAT_V1, FILE_KDF_MEMLIMIT_KIB, FILE_KDF_OPSLIMIT, FILE_MAGIC,
    FILE_MIME, FILE_SALT_LEN, FILE_SUITE_CODE, FILE_SUITE_RAW_KEY, FILE_V2_OVERHEAD, FileSuite,
    ParsedFile, derive_file_key, encode_file, file_key, open_file, parse_file, random_salt,
    seal_file, seal_file_parts,
};
pub use import::{
    ImportCandidate, ImportDecision, ParentCheck, StoredEvent, StoredRevision, decide_import,
    is_expired,
};
pub use keys::{NONCE_LEN, OrganizerKey, PackKey, new_event_id, new_item_id, random_nonce};
pub use link::{
    DEFAULT_SITE, LEGACY_SITE, LINK_VERSION, MAX_FRAGMENT_CHARS, encode_link, link_fragment,
    open_link, parse_link,
};
pub use model::{
    Event, Lang, Organizer, Session, Stop, StopKind, Template, TimeWindow, VARIANT_STOPS_ONLY,
    Variant, default_expiry, stops_only_variant,
};
pub use pack::{
    AEAD_AD, Envelope, MAX_INFLATED_BYTES, OpenedPack, SIGNATURE_PREFIX, SealedPack, SealedParts,
    decrypt, deflate_raw, encrypt, inflate_raw, open, seal, seal_bytes, seal_parts,
};
pub use revision::{bytes_hash, revision_hash, revision_hash_b64u};
pub use route_core::{
    EVENT_SIMPLIFY_TOLERANCE_M, GeometrySource, LatLng, Route, RouteLeg, TravelMode,
};
pub use validate::limits;
