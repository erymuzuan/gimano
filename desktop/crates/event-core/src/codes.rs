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

/// First 8 bytes of SHA-256(organizer public key), upper-case hex in 4 groups of 4.
pub fn safety_code(organizer_pub: &[u8; 32]) -> String {
    let h = Sha256::digest(organizer_pub);
    h[..8]
        .chunks(2)
        .map(|c| format!("{:02X}{:02X}", c[0], c[1]))
        .collect::<Vec<_>>()
        .join(" ")
}

#[cfg(test)]
mod tests {
    use super::*;

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
        // SHA-256 of 32 zero bytes starts 66687aadf862bd77.
        assert_eq!(c, "6668 7AAD F862 BD77");
    }
}
