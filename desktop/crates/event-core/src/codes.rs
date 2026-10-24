use sha2::{Digest, Sha256};

use crate::error::Error;

const CROCKFORD: &[u8; 32] = b"0123456789ABCDEFGHJKMNPQRSTVWXYZ";
const KEY_CHARS: usize = 52;

/// The pack key as a Crockford base32 secret code: 52 characters (MSB first, 4 zero padding
/// bits), upper case, in groups of 4 joined by `-`.
pub fn encode_secret_code(key: &[u8; 32]) -> String {
    let mut chars = Vec::with_capacity(KEY_CHARS);
    let (mut acc, mut bits) = (0u32, 0u32);
    for &b in key {
        acc = (acc << 8) | u32::from(b);
        bits += 8;
        while bits >= 5 {
            bits -= 5;
            chars.push(CROCKFORD[((acc >> bits) & 31) as usize]);
        }
        acc &= (1 << bits) - 1;
    }
    if bits > 0 {
        chars.push(CROCKFORD[((acc << (5 - bits)) & 31) as usize]);
    }
    chars
        .chunks(4)
        .map(|c| std::str::from_utf8(c).expect("ascii"))
        .collect::<Vec<_>>()
        .join("-")
}

/// Case-insensitive; `I`/`L` read as 1 and `O` as 0; hyphens and whitespace are ignored.
/// The padding bits must be zero so every key has exactly one code.
pub fn decode_secret_code(code: &str) -> Result<[u8; 32], Error> {
    let mut values = Vec::with_capacity(KEY_CHARS);
    for c in code.chars() {
        if c == '-' || c.is_whitespace() {
            continue;
        }
        let v = match c.to_ascii_uppercase() {
            'O' => 0,
            'I' | 'L' => 1,
            u => CROCKFORD
                .iter()
                .position(|&a| char::from(a) == u)
                .ok_or(Error::InvalidSecretCode("invalid character"))? as u32,
        };
        values.push(v);
        if values.len() > KEY_CHARS {
            return Err(Error::InvalidSecretCode("too long"));
        }
    }
    if values.len() != KEY_CHARS {
        return Err(Error::InvalidSecretCode("wrong length"));
    }
    let mut out = [0u8; 32];
    let (mut acc, mut bits, mut i) = (0u32, 0u32, 0usize);
    for v in values {
        acc = (acc << 5) | v;
        bits += 5;
        if bits >= 8 {
            bits -= 8;
            out[i] = (acc >> bits) as u8;
            i += 1;
            acc &= (1 << bits) - 1;
        }
    }
    if acc != 0 {
        return Err(Error::InvalidSecretCode("non-canonical padding"));
    }
    Ok(out)
}

/// Safety code alphabet: digits and upper-case letters without the look-alikes 0/O and 1/I.
pub const SAFETY_ALPHABET: &[u8; 32] = b"23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

/// The first 30 bits of SHA-256(organizer public key), most significant first, as 6 characters of
/// [`SAFETY_ALPHABET`] (5 bits each), e.g. `ETN9PD`.
pub fn safety_code(organizer_pub: &[u8; 32]) -> String {
    let h = Sha256::digest(organizer_pub);
    let v = u32::from_be_bytes([h[0], h[1], h[2], h[3]]);
    (0..6)
        .map(|i| SAFETY_ALPHABET[((v >> (27 - 5 * i)) & 31) as usize] as char)
        .collect()
}

/// Characters in a suite-2 file secret code (§5.2): 8 × 5 bits = 40 bits.
pub const SECRET_CODE_V2_CHARS: usize = 8;
/// Random bytes behind a suite-2 code.
pub const SECRET_CODE_V2_BYTES: usize = 5;

/// A suite-2 file secret code from 40 bits (MSB first) as 8 characters of [`SAFETY_ALPHABET`],
/// shown as `XXXX-XXXX`. Uniform bits give uniform characters.
pub fn encode_secret_code_v2(bits: &[u8; SECRET_CODE_V2_BYTES]) -> String {
    let v = bits.iter().fold(0u64, |acc, &b| (acc << 8) | u64::from(b));
    let mut s = String::with_capacity(SECRET_CODE_V2_CHARS + 1);
    for i in 0..SECRET_CODE_V2_CHARS {
        if i == 4 {
            s.push('-');
        }
        s.push(SAFETY_ALPHABET[((v >> (35 - 5 * i)) & 31) as usize] as char);
    }
    s
}

/// A fresh suite-2 code from the CSPRNG, e.g. `K7M4-QX9P`.
pub fn generate_secret_code_v2() -> Result<String, Error> {
    let mut bits = zeroize::Zeroizing::new([0u8; SECRET_CODE_V2_BYTES]);
    getrandom::getrandom(bits.as_mut()).map_err(|_| Error::Random)?;
    Ok(encode_secret_code_v2(&bits))
}

/// Reads a typed suite-2 code: case-insensitive, spaces and `-` ignored, any other character
/// outside [`SAFETY_ALPHABET`] (including `0`, `O`, `1`, `I`) rejected. Returns the 8 upper-case
/// ASCII characters, the Argon2id password.
pub fn parse_secret_code_v2(code: &str) -> Result<zeroize::Zeroizing<String>, Error> {
    let mut out = zeroize::Zeroizing::new(String::with_capacity(SECRET_CODE_V2_CHARS));
    for c in code.chars() {
        if c == '-' || c.is_whitespace() {
            continue;
        }
        let u = c.to_ascii_uppercase();
        if !u.is_ascii() || !SAFETY_ALPHABET.contains(&(u as u8)) {
            return Err(Error::InvalidSecretCode("invalid character"));
        }
        if out.len() == SECRET_CODE_V2_CHARS {
            return Err(Error::InvalidSecretCode("too long"));
        }
        out.push(u);
    }
    if out.len() != SECRET_CODE_V2_CHARS {
        return Err(Error::InvalidSecretCode("wrong length"));
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn secret_code_v2_format() {
        assert_eq!(encode_secret_code_v2(&[0; 5]), "2222-2222");
        assert_eq!(encode_secret_code_v2(&[0xff; 5]), "ZZZZ-ZZZZ");
        // 00001 00010 00011 00100 00101 00110 00111 01000
        assert_eq!(
            encode_secret_code_v2(&[0x08, 0x86, 0x42, 0x98, 0xe8]),
            "3456-789A"
        );
        for _ in 0..64 {
            let c = generate_secret_code_v2().unwrap();
            assert_eq!(c.len(), 9);
            assert_eq!(&c[4..5], "-");
            assert_eq!(*parse_secret_code_v2(&c).unwrap(), c.replace('-', ""));
        }
    }

    #[test]
    fn secret_code_v2_lenient_reading() {
        assert_eq!(*parse_secret_code_v2("k7m4-qx9p").unwrap(), "K7M4QX9P");
        assert_eq!(*parse_secret_code_v2(" K7M4 QX9P ").unwrap(), "K7M4QX9P");
        assert_eq!(*parse_secret_code_v2("K-7-M-4QX9P").unwrap(), "K7M4QX9P");
    }

    #[test]
    fn secret_code_v2_rejects_bad_input() {
        for bad in [
            "K7M4-QX9O",
            "K7M4-QX90",
            "K7M4-QX9I",
            "K7M4-QX91",
            "K7M4_QX9P",
            "K7M4-QX9É",
        ] {
            assert_eq!(
                parse_secret_code_v2(bad).unwrap_err(),
                Error::InvalidSecretCode("invalid character"),
                "{bad}"
            );
        }
        assert_eq!(
            parse_secret_code_v2("K7M4-QX9").unwrap_err(),
            Error::InvalidSecretCode("wrong length")
        );
        assert_eq!(
            parse_secret_code_v2("K7M4-QX9PP").unwrap_err(),
            Error::InvalidSecretCode("too long")
        );
        // A suite-1 code is not a suite-2 code.
        let old = encode_secret_code(&[7u8; 32]);
        assert!(parse_secret_code_v2(&old).is_err());
    }

    #[test]
    fn secret_code_round_trip() {
        for seed in 0u8..=255 {
            let key: [u8; 32] =
                std::array::from_fn(|i| seed.wrapping_mul(31).wrapping_add(i as u8 * 7));
            let code = encode_secret_code(&key);
            assert_eq!(code.len(), 52 + 12);
            assert_eq!(code.split('-').count(), 13);
            assert!(code.split('-').all(|g| g.len() == 4));
            assert_eq!(decode_secret_code(&code).unwrap(), key);
        }
    }

    #[test]
    fn secret_code_known_values() {
        assert_eq!(
            encode_secret_code(&[0u8; 32]),
            "0000-0000-0000-0000-0000-0000-0000-0000-0000-0000-0000-0000-0000"
        );
        assert_eq!(
            encode_secret_code(&[0xff; 32]),
            "ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZG"
        );
    }

    #[test]
    fn secret_code_lenient_reading() {
        let key: [u8; 32] = std::array::from_fn(|i| i as u8);
        let code = encode_secret_code(&key);
        let typed = code.to_lowercase().replace('-', " ");
        assert_eq!(decode_secret_code(&typed).unwrap(), key);
        let confusable = code.replace('0', "o").replace('1', "l");
        assert_eq!(decode_secret_code(&confusable).unwrap(), key);
        let confusable = code.replace('1', "I");
        assert_eq!(decode_secret_code(&confusable).unwrap(), key);
    }

    #[test]
    fn secret_code_rejects_bad_input() {
        let code = encode_secret_code(&[7u8; 32]);
        assert!(decode_secret_code(&code[..code.len() - 1]).is_err());
        assert!(decode_secret_code(&format!("{code}0")).is_err());
        assert!(
            decode_secret_code(&code.replacen(|c: char| c.is_ascii_alphanumeric(), "U", 1))
                .is_err()
        );
        // Last character carries 1 data bit + 4 padding bits: "1" sets a padding bit.
        let mut bad = encode_secret_code(&[0u8; 32]);
        bad.pop();
        bad.push('1');
        assert_eq!(
            decode_secret_code(&bad),
            Err(Error::InvalidSecretCode("non-canonical padding"))
        );
    }

    #[test]
    fn safety_code_format() {
        let c = safety_code(&[0u8; 32]);
        // SHA-256 of 32 zero bytes starts 66687aad: top 30 bits 01100 11001 10100 00111 10101 01011.
        assert_eq!(c, "ETN9PD");
        assert!(!c.contains(['0', '1', 'O', 'I']));
    }
}
