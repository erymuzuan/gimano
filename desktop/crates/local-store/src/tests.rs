use chrono::{DateTime, Duration, Utc};

use crate::*;

const P: KdfParams = KdfParams::INSECURE_FOR_TESTS;

fn now() -> DateTime<Utc> {
    "2026-11-01T00:00:00Z".parse().unwrap()
}

fn keyed() -> Store {
    let mut s = Store::open_in_memory().unwrap();
    s.initialize_with_key(DataKey::from_bytes([7; 32])).unwrap();
    s
}

#[test]
fn round_trip_with_key() {
    let s = keyed();
    assert_eq!(s.state().unwrap(), VaultState::Unlocked(KeyMode::Keychain));
    s.put("event", "a", None, b"{\"title\":\"Kenduri\"}")
        .unwrap();
    let exp = now() + Duration::days(3);
    s.put("route", "b", Some(exp), b"route-bytes").unwrap();

    let a = s.get("event", "a", now()).unwrap().unwrap();
    assert_eq!(a.data.as_slice(), b"{\"title\":\"Kenduri\"}");
    assert_eq!(a.expires_at, None);
    let b = s.get("route", "b", now()).unwrap().unwrap();
    assert_eq!(b.expires_at, Some(exp));
    assert!(s.get("event", "b", now()).unwrap().is_none());

    // Replace keeps one row.
    s.put("event", "a", None, b"v2").unwrap();
    let all = s.list("event", now()).unwrap();
    assert_eq!(all.len(), 1);
    assert_eq!(all[0].data.as_slice(), b"v2");

    assert!(s.delete("event", "a").unwrap());
    assert!(s.get("event", "a", now()).unwrap().is_none());
}

#[test]
fn nothing_is_stored_in_plaintext() {
    let s = keyed();
    let exp = now() + Duration::days(1);
    s.put(
        "event",
        "x",
        Some(exp),
        b"Parkir tetamu 2.987100 101.793000",
    )
    .unwrap();
    let raw = s.raw_dump().unwrap();
    let hay = String::from_utf8_lossy(&raw);
    assert!(!hay.contains("Parkir"));
    assert!(!hay.contains("2.987100"));
    assert!(!raw.windows(8).any(|w| w == exp.timestamp().to_be_bytes()));
}

#[test]
fn wrong_key_is_rejected() {
    let dir = std::env::temp_dir().join(format!("manotu-ls-{}", std::process::id()));
    let path = dir.join("wrong-key.db");
    let _ = std::fs::remove_file(&path);
    {
        let mut s = Store::open(&path).unwrap();
        s.initialize_with_key(DataKey::from_bytes([1; 32])).unwrap();
        s.put("event", "a", None, b"secret").unwrap();
    }
    let mut s = Store::open(&path).unwrap();
    assert_eq!(s.state().unwrap(), VaultState::Locked(KeyMode::Keychain));
    assert!(matches!(
        s.get("event", "a", now()),
        Err(StoreError::Locked)
    ));
    assert!(matches!(
        s.unlock_with_key(DataKey::from_bytes([2; 32])),
        Err(StoreError::WrongKey)
    ));
    assert!(!s.is_unlocked());
    assert!(matches!(
        s.unlock_with_passphrase("long enough passphrase"),
        Err(StoreError::WrongMode)
    ));
    s.unlock_with_key(DataKey::from_bytes([1; 32])).unwrap();
    assert_eq!(
        s.get("event", "a", now()).unwrap().unwrap().data.as_slice(),
        b"secret"
    );
    s.lock();
    assert!(matches!(s.list("event", now()), Err(StoreError::Locked)));
    s.unlock_with_key(DataKey::from_bytes([1; 32])).unwrap();
    s.destroy().unwrap();
    assert!(!path.exists());
}

#[test]
fn passphrase_vault() {
    let mut s = Store::open_in_memory().unwrap();
    assert_eq!(s.state().unwrap(), VaultState::Uninitialized);
    assert!(matches!(
        s.initialize_with_passphrase("short", P),
        Err(StoreError::WeakPassphrase)
    ));
    s.initialize_with_passphrase("kenduri di kampung 2026", P)
        .unwrap();
    s.put("secret", "k", None, &[9; 32]).unwrap();
    s.lock();
    assert!(matches!(
        s.unlock_with_passphrase("kenduri di kampung 2027"),
        Err(StoreError::WrongKey)
    ));
    assert!(matches!(
        s.unlock_with_passphrase("x"),
        Err(StoreError::WrongKey)
    ));
    s.unlock_with_passphrase("kenduri di kampung 2026").unwrap();
    assert_eq!(
        s.get("secret", "k", now())
            .unwrap()
            .unwrap()
            .data
            .as_slice(),
        &[9; 32]
    );
    assert!(matches!(
        s.initialize_with_key(DataKey::from_bytes([0; 32])),
        Err(StoreError::AlreadyInitialized)
    ));
}

#[test]
fn swapped_blobs_do_not_decrypt() {
    let s = keyed();
    s.put("event", "a", None, b"A").unwrap();
    s.put("event", "b", None, b"B").unwrap();
    s.swap_blobs_for_test(("event", "a"), ("event", "b"))
        .unwrap();
    assert!(matches!(
        s.get("event", "a", now()),
        Err(StoreError::WrongKey)
    ));
}

#[test]
fn purge_expired_records() {
    let s = keyed();
    s.put("event", "old", Some(now() - Duration::seconds(1)), b"old")
        .unwrap();
    s.put("event", "edge", Some(now()), b"edge").unwrap();
    s.put("event", "new", Some(now() + Duration::hours(1)), b"new")
        .unwrap();
    s.put("secret", "forever", None, b"k").unwrap();

    // Expired records are hidden even before a purge.
    assert!(s.get("event", "old", now()).unwrap().is_none());
    let ids: Vec<_> = s
        .list("event", now())
        .unwrap()
        .into_iter()
        .map(|r| r.id)
        .collect();
    assert_eq!(ids, ["new"]);

    let mut gone = s.purge_expired(now()).unwrap();
    gone.sort();
    assert_eq!(
        gone,
        [
            ("event".to_owned(), "edge".to_owned()),
            ("event".to_owned(), "old".to_owned())
        ]
    );
    assert!(s.purge_expired(now()).unwrap().is_empty());
    assert_eq!(s.list("secret", now()).unwrap().len(), 1);
    // Later on, the remaining event expires too.
    assert_eq!(s.purge_expired(now() + Duration::days(1)).unwrap().len(), 1);
}

#[test]
fn recovery_file_round_trip() {
    let file = seal_recovery(b"{\"keys\":[]}", "pemulihan penganjur", P).unwrap();
    assert_eq!(&file[..4], RECOVERY_MAGIC);
    let back = open_recovery(&file, "pemulihan penganjur").unwrap();
    assert_eq!(back.as_slice(), b"{\"keys\":[]}");
    assert!(matches!(
        open_recovery(&file, "pemulihan salah sekali"),
        Err(StoreError::WrongKey)
    ));
    let mut tampered = file.clone();
    tampered[6] ^= 1; // salt is authenticated as AD
    assert!(open_recovery(&tampered, "pemulihan penganjur").is_err());
    assert!(matches!(
        open_recovery(b"MNTRxx", "pemulihan penganjur"),
        Err(StoreError::MalformedRecovery)
    ));
    // Crafted memory-bomb parameters are refused before deriving.
    let mut bomb = file;
    bomb[5 + 16..5 + 20].copy_from_slice(&u32::MAX.to_be_bytes());
    assert!(matches!(
        open_recovery(&bomb, "pemulihan penganjur"),
        Err(StoreError::MalformedRecovery)
    ));
}

#[test]
fn kdf_is_deterministic_and_salted() {
    let a = derive_key("passphrase-123", &[1; 16], P).unwrap();
    let b = derive_key("passphrase-123", &[1; 16], P).unwrap();
    let c = derive_key("passphrase-123", &[2; 16], P).unwrap();
    assert_eq!(a.as_bytes(), b.as_bytes());
    assert_ne!(a.as_bytes(), c.as_bytes());
}
