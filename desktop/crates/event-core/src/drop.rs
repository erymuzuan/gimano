//! Short link through the end-to-end encrypted relay (§5.6).
//!
//! ```text
//! https://manotu.app/j/#<dropId>.<b64u(secret)>      manotu://event?drop=<dropId>#<b64u(secret)>
//! key  = HKDF-SHA256(ikm = secret (16 bytes), salt = empty, info = "manotu-drop-v1", L = 32)
//! blob = nonce (24) ‖ XChaCha20-Poly1305(key, nonce, AD "manotu-event-v1", deflated signed envelope)
//! ```
//!
//! The server stores the blob under `dropId`; the secret only ever travels after `#`. The blob is
//! the `nonce ‖ ciphertext` of a link (§5.1) under the derived key, always a full event, and is
//! opened with every link rule (signature, §7).

use hkdf::Hkdf;
use sha2::Sha256;
use zeroize::{Zeroize, Zeroizing};

use crate::encoding::{b64u, b64u_array};
use crate::error::Error;
use crate::keys::{NONCE_LEN, OrganizerKey, PackKey, random_nonce};
use crate::pack::{OpenedPack, SealedPack, SealedParts, open, seal_parts};

/// The server's dropId alphabet (base58: no `0`, `O`, `I`, `l`).
pub const DROP_ID_ALPHABET: &str = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
pub const DROP_ID_LEN: usize = 10;
/// Random bytes made on the organizer's device; 22 characters of b64u.
pub const DROP_SECRET_LEN: usize = 16;
/// HKDF `info`.
pub const DROP_HKDF_INFO: &[u8] = b"manotu-drop-v1";
/// Largest blob the server accepts (256 KiB).
pub const MAX_DROP_BYTES: usize = 256 * 1024;
/// Path of short links on the invitation site.
pub const DROP_PATH: &str = "/j/";
const TAG_LEN: usize = 16;

/// The 16-byte drop secret. Zeroized on drop.
#[derive(Clone, PartialEq, Eq)]
pub struct DropSecret([u8; DROP_SECRET_LEN]);

impl DropSecret {
    pub fn generate() -> Result<Self, Error> {
        let mut b = [0u8; DROP_SECRET_LEN];
        getrandom::getrandom(&mut b).map_err(|_| Error::Random)?;
        Ok(Self(b))
    }

    pub fn from_bytes(bytes: [u8; DROP_SECRET_LEN]) -> Self {
        Self(bytes)
    }

    pub fn as_bytes(&self) -> &[u8; DROP_SECRET_LEN] {
        &self.0
    }

    /// Strict b64u of exactly 16 bytes (22 characters), else [`Error::MalformedDropLink`].
    pub fn from_b64u(s: &str) -> Result<Self, Error> {
        b64u_array(s).map(Self).ok_or(Error::MalformedDropLink)
    }

    pub fn to_b64u(&self) -> String {
        b64u(&self.0)
    }

    /// `HKDF-SHA256(ikm = secret, salt = empty, info = "manotu-drop-v1", L = 32)`.
    pub fn key(&self) -> PackKey {
        let mut out = Zeroizing::new([0u8; 32]);
        Hkdf::<Sha256>::new(None, &self.0)
            .expand(DROP_HKDF_INFO, out.as_mut())
            .expect("32 bytes is a valid HKDF-SHA256 length");
        PackKey::from_bytes(*out)
    }
}

impl Drop for DropSecret {
    fn drop(&mut self) {
        self.0.zeroize();
    }
}

impl std::fmt::Debug for DropSecret {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("DropSecret").finish_non_exhaustive()
    }
}

/// A parsed short link: which blob to fetch and the secret that opens it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DropLink {
    pub drop_id: String,
    pub secret: DropSecret,
}

impl DropLink {
    /// `<dropId>.<b64u(secret)>`, the part after `#`.
    pub fn fragment(&self) -> String {
        format!("{}.{}", self.drop_id, self.secret.to_b64u())
    }

    /// `<site>/j/#<dropId>.<secret>`; `site` is e.g. [`crate::DEFAULT_SITE`].
    pub fn to_link(&self, site: &str) -> String {
        format!(
            "{}{DROP_PATH}#{}",
            site.trim_end_matches('/'),
            self.fragment()
        )
    }

    /// `manotu://event?drop=<dropId>#<secret>`, the web page's "Buka di Manotu" (§5.6).
    pub fn to_app_link(&self) -> String {
        format!(
            "manotu://event?drop={}#{}",
            self.drop_id,
            self.secret.to_b64u()
        )
    }
}

/// Exactly 10 characters of [`DROP_ID_ALPHABET`].
pub fn is_valid_drop_id(id: &str) -> bool {
    id.len() == DROP_ID_LEN && id.bytes().all(|b| DROP_ID_ALPHABET.as_bytes().contains(&b))
}

pub fn drop_link(drop_id: &str, secret: DropSecret) -> Result<DropLink, Error> {
    if !is_valid_drop_id(drop_id) {
        return Err(Error::MalformedDropLink);
    }
    Ok(DropLink {
        drop_id: drop_id.to_owned(),
        secret,
    })
}

/// Accepts `https://<any site>/j/#<dropId>.<secret>`, `manotu://event?drop=<dropId>#<secret>`
/// (also `gimano://`, and other query parameters such as `stop`), or a bare `<dropId>.<secret>`
/// (with or without the `#`). An `/a/` link (`#1.<key>.<data>`) is never a drop link.
pub fn parse_drop_link(link: &str) -> Result<DropLink, Error> {
    let link = link.trim();
    let (before, fragment) = link.split_once('#').unwrap_or(("", link));
    let scheme_query = ["manotu://event", "gimano://event"]
        .iter()
        .find_map(|p| before.strip_prefix(p));
    let (drop_id, secret) = if let Some(rest) = scheme_query {
        let query = rest.strip_prefix('?').ok_or(Error::MalformedDropLink)?;
        let id = query
            .split('&')
            .find_map(|kv| kv.strip_prefix("drop="))
            .ok_or(Error::MalformedDropLink)?;
        (id, fragment)
    } else {
        if !before.is_empty() && !before.ends_with(DROP_PATH) && !before.ends_with("/j") {
            return Err(Error::MalformedDropLink);
        }
        fragment.split_once('.').ok_or(Error::MalformedDropLink)?
    };
    drop_link(drop_id, DropSecret::from_b64u(secret)?)
}

/// Whether `link` parses as a short link (so a reader fetches it instead of opening it as `/a/`).
pub fn is_drop_link(link: &str) -> bool {
    parse_drop_link(link).is_ok()
}

/// `nonce ‖ ciphertext`, refusing a blob over [`MAX_DROP_BYTES`].
pub fn encode_drop_blob(sealed: &SealedPack) -> Result<Vec<u8>, Error> {
    let len = NONCE_LEN + sealed.ciphertext.len();
    if len > MAX_DROP_BYTES {
        return Err(Error::DropTooLarge {
            max: MAX_DROP_BYTES,
        });
    }
    let mut out = Vec::with_capacity(len);
    out.extend_from_slice(&sealed.nonce);
    out.extend_from_slice(&sealed.ciphertext);
    Ok(out)
}

/// Splits a blob into nonce and ciphertext. Over 256 KiB is [`Error::DropTooLarge`]; shorter than
/// nonce + tag (40 bytes) is [`Error::MalformedDrop`].
pub fn parse_drop_blob(blob: &[u8]) -> Result<SealedPack, Error> {
    if blob.len() > MAX_DROP_BYTES {
        return Err(Error::DropTooLarge {
            max: MAX_DROP_BYTES,
        });
    }
    if blob.len() < NONCE_LEN + TAG_LEN {
        return Err(Error::MalformedDrop);
    }
    Ok(SealedPack {
        nonce: blob[..NONCE_LEN].try_into().expect("length checked"),
        ciphertext: blob[NONCE_LEN..].to_vec(),
    })
}

/// Deterministic writer for test vectors: signs, compresses and encrypts these (full) event bytes
/// under the derived key with an explicit nonce. Never reuse a nonce; [`seal_drop`] picks one.
pub fn seal_drop_parts(
    event_bytes: &[u8],
    organizer: &OrganizerKey,
    secret: &DropSecret,
    nonce: [u8; NONCE_LEN],
) -> Result<(SealedParts, Vec<u8>), Error> {
    if crate::model::Event::from_bytes(event_bytes)?
        .variant
        .is_some()
    {
        return Err(Error::NotFullEvent);
    }
    let parts = seal_parts(event_bytes, organizer, &secret.key(), nonce)?;
    let blob = encode_drop_blob(&parts.sealed)?;
    Ok((parts, blob))
}

/// The writer path (upload and every republish): a fresh nonce, then the blob, opened again to
/// prove it verifies before it is uploaded. The secret stays the same for every revision.
pub fn seal_drop(
    event_bytes: &[u8],
    organizer: &OrganizerKey,
    secret: &DropSecret,
) -> Result<Vec<u8>, Error> {
    let (_, blob) = seal_drop_parts(event_bytes, organizer, secret, random_nonce()?)?;
    if open_drop(&blob, secret)?.event_bytes != event_bytes {
        return Err(Error::MalformedEnvelope);
    }
    Ok(blob)
}

/// Decrypt → bounded inflate → envelope → signature → event, exactly like a link (§7 steps 1–2).
pub fn open_drop(blob: &[u8], secret: &DropSecret) -> Result<OpenedPack, Error> {
    open(&secret.key(), &parse_drop_blob(blob)?)
}

#[cfg(test)]
mod tests {
    use super::*;

    const ID: &str = "7Kx2Qp9mWd";

    fn secret() -> DropSecret {
        DropSecret::from_bytes(*b"0123456789abcdef")
    }

    #[test]
    fn hkdf_rfc5869_shape() {
        // RFC 5869 test case 3 (empty salt and info) checks the HKDF wiring; ours adds the info.
        let ikm = [0x0bu8; 22];
        let mut okm = [0u8; 42];
        Hkdf::<Sha256>::new(None, &ikm)
            .expand(&[], &mut okm)
            .unwrap();
        assert_eq!(
            b64u(&okm),
            b64u(&[
                0x8d, 0xa4, 0xe7, 0x75, 0xa5, 0x63, 0xc1, 0x8f, 0x71, 0x5f, 0x80, 0x2a, 0x06, 0x3c,
                0x5a, 0x31, 0xb8, 0xa1, 0x1f, 0x5c, 0x5e, 0xe1, 0x87, 0x9e, 0xc3, 0x45, 0x4e, 0x5f,
                0x3c, 0x73, 0x8d, 0x2d, 0x9d, 0x20, 0x13, 0x95, 0xfa, 0xa4, 0xb6, 0x1a, 0x96, 0xc8
            ])
        );
        assert_ne!(secret().key().as_bytes(), &[0u8; 32]);
    }

    #[test]
    fn link_forms_round_trip() {
        let l = drop_link(ID, secret()).unwrap();
        let web = l.to_link("https://manotu.app/");
        assert_eq!(
            web,
            format!("https://manotu.app/j/#{ID}.{}", secret().to_b64u())
        );
        assert_eq!(parse_drop_link(&web).unwrap(), l);
        assert_eq!(parse_drop_link(&l.to_app_link()).unwrap(), l);
        assert_eq!(parse_drop_link(&l.fragment()).unwrap(), l);
        assert_eq!(parse_drop_link(&format!("#{}", l.fragment())).unwrap(), l);
        let other = format!(
            "gimano://event?stop=AAAAAAAAAAA&drop={ID}#{}",
            secret().to_b64u()
        );
        assert_eq!(parse_drop_link(&other).unwrap(), l);
        assert_eq!(
            parse_drop_link(&format!(
                "https://www.bukitbunga.com/gimano/j/#{}",
                l.fragment()
            ))
            .unwrap(),
            l
        );
    }

    #[test]
    fn rejects_malformed_links() {
        let s = secret().to_b64u();
        for bad in [
            format!("7Kx2Qp9mW0.{s}"),  // 0 not in base58
            format!("7Kx2Qp9mWl.{s}"),  // l
            format!("7Kx2Qp9mW.{s}"),   // 9 chars
            format!("7Kx2Qp9mWdd.{s}"), // 11 chars
            format!("{ID}.{}", b64u(&[1u8; 15])),
            format!("{ID}.{}", b64u(&[1u8; 17])),
            format!("{ID}.{s}="),
            format!("{ID}."),
            ID.to_string(),
            format!("https://manotu.app/a/#{ID}.{s}"),
            format!("manotu://event#{s}"),
            "1.AAAA.BBBB".into(),
        ] {
            assert_eq!(
                parse_drop_link(&bad),
                Err(Error::MalformedDropLink),
                "{bad}"
            );
        }
        assert!(!is_drop_link("https://manotu.app/a/#1.abc.def"));
    }

    #[test]
    fn blob_bounds() {
        assert_eq!(parse_drop_blob(&[0u8; 39]), Err(Error::MalformedDrop));
        assert!(parse_drop_blob(&[0u8; 40]).is_ok());
        assert_eq!(
            parse_drop_blob(&vec![0u8; MAX_DROP_BYTES + 1]),
            Err(Error::DropTooLarge {
                max: MAX_DROP_BYTES
            })
        );
        assert_eq!(open_drop(&[0u8; 40], &secret()), Err(Error::Decrypt));
    }
}
