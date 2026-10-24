use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;

/// base64url without padding.
pub fn b64u(bytes: &[u8]) -> String {
    URL_SAFE_NO_PAD.encode(bytes)
}

/// Strict base64url decode: no padding, canonical trailing bits.
pub fn b64u_decode(s: &str) -> Option<Vec<u8>> {
    URL_SAFE_NO_PAD.decode(s).ok()
}

pub(crate) fn b64u_array<const N: usize>(s: &str) -> Option<[u8; N]> {
    b64u_decode(s)?.try_into().ok()
}
