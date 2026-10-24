//! Invitation link `https://<site>/a/#1.<b64u(key)>.<b64u(nonce ‖ ciphertext)>` (§5.1).

use crate::encoding::{b64u, b64u_array, b64u_decode};
use crate::error::Error;
use crate::keys::{NONCE_LEN, PackKey};
use crate::pack::{OpenedPack, SealedPack, open};

pub const DEFAULT_SITE: &str = "https://manotu.app";
/// Where links pointed before manotu.app; still opened (§5.1). The test vectors use it.
pub const LEGACY_SITE: &str = "https://www.bukitbunga.com/gimano";
pub const LINK_VERSION: &str = "1";
/// Characters after `#`.
pub const MAX_FRAGMENT_CHARS: usize = crate::validate::limits::MAX_FRAGMENT_CHARS;

/// The fragment without `#`. Fails with [`Error::FragmentTooLong`] above 16 000 characters;
/// the caller then offers the file, or a stops-only link after asking.
pub fn link_fragment(key: &PackKey, sealed: &SealedPack) -> Result<String, Error> {
    let mut data = Vec::with_capacity(NONCE_LEN + sealed.ciphertext.len());
    data.extend_from_slice(&sealed.nonce);
    data.extend_from_slice(&sealed.ciphertext);
    let fragment = format!("{LINK_VERSION}.{}.{}", b64u(key.as_bytes()), b64u(&data));
    if fragment.len() > MAX_FRAGMENT_CHARS {
        return Err(Error::FragmentTooLong {
            len: fragment.len(),
            max: MAX_FRAGMENT_CHARS,
        });
    }
    Ok(fragment)
}

/// `site` is the configurable base, e.g. [`DEFAULT_SITE`].
pub fn encode_link(site: &str, key: &PackKey, sealed: &SealedPack) -> Result<String, Error> {
    let fragment = link_fragment(key, sealed)?;
    Ok(format!("{}/a/#{fragment}", site.trim_end_matches('/')))
}

/// Accepts a full link (any site) or a bare fragment. The length is checked before decoding.
pub fn parse_link(link: &str) -> Result<(PackKey, SealedPack), Error> {
    let fragment = link.split_once('#').map_or(link, |(_, f)| f).trim();
    let len = fragment.chars().count();
    if len > MAX_FRAGMENT_CHARS {
        return Err(Error::FragmentTooLong {
            len,
            max: MAX_FRAGMENT_CHARS,
        });
    }
    let mut parts = fragment.split('.');
    let (Some(version), Some(key), Some(data), None) =
        (parts.next(), parts.next(), parts.next(), parts.next())
    else {
        return Err(Error::MalformedLink);
    };
    if version != LINK_VERSION {
        return Err(Error::UnsupportedLinkVersion);
    }
    let key = PackKey::from_bytes(b64u_array(key).ok_or(Error::MalformedLink)?);
    let data = b64u_decode(data).ok_or(Error::MalformedLink)?;
    if data.len() < NONCE_LEN + 16 {
        return Err(Error::MalformedLink);
    }
    let nonce: [u8; NONCE_LEN] = data[..NONCE_LEN].try_into().expect("length checked");
    Ok((
        key,
        SealedPack {
            nonce,
            ciphertext: data[NONCE_LEN..].to_vec(),
        },
    ))
}

pub fn open_link(link: &str) -> Result<OpenedPack, Error> {
    let (key, sealed) = parse_link(link)?;
    open(&key, &sealed)
}
