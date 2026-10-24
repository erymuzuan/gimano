#[derive(Debug, thiserror::Error)]
pub enum StoreError {
    /// The vault is locked (no data key in memory).
    #[error("the local vault is locked")]
    Locked,
    /// Wrong key or passphrase, or a tampered record. Indistinguishable by design.
    #[error("the key or passphrase is wrong, or the data was modified")]
    WrongKey,
    #[error("the local vault is already set up")]
    AlreadyInitialized,
    #[error("the local vault is not set up yet")]
    NotInitialized,
    #[error("the vault uses a different key mode")]
    WrongMode,
    #[error("passphrase is too short (at least 10 characters)")]
    WeakPassphrase,
    #[error("not a Manotu recovery file")]
    MalformedRecovery,
    #[error("key derivation failed")]
    Kdf,
    #[error("random number generator failed")]
    Random,
    #[error("secure key store unavailable: {0}")]
    KeychainUnavailable(String),
    #[error("database error: {0}")]
    Db(#[from] rusqlite::Error),
    #[error("file error: {0}")]
    Io(#[from] std::io::Error),
}
