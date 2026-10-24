use std::fmt;

/// A field that breaks a docs/events.md §4 rule; `path` points at it (e.g. `stops[3].label`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ValidationError {
    pub path: String,
    pub reason: &'static str,
}

impl ValidationError {
    pub(crate) fn new(path: impl Into<String>, reason: &'static str) -> Self {
        Self {
            path: path.into(),
            reason,
        }
    }
}

impl fmt::Display for ValidationError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}: {}", self.path, self.reason)
    }
}

impl std::error::Error for ValidationError {}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum Error {
    #[error("invalid event: {0}")]
    Invalid(#[from] ValidationError),
    #[error("event JSON is malformed: {0}")]
    MalformedEvent(String),
    #[error("link fragment has {len} characters, more than {max}")]
    FragmentTooLong { len: usize, max: usize },
    #[error("link is malformed")]
    MalformedLink,
    #[error("unsupported link version")]
    UnsupportedLinkVersion,
    #[error("file is larger than {max} bytes")]
    FileTooLarge { max: usize },
    #[error("not a .manotu-event file")]
    MalformedFile,
    #[error("unsupported file format {0}")]
    UnsupportedFileFormat(u8),
    #[error("unsupported key suite {0}")]
    UnsupportedSuite(u8),
    #[error("secret code is invalid: {0}")]
    InvalidSecretCode(&'static str),
    /// Wrong key or tampered ciphertext; indistinguishable by design.
    #[error("pack cannot be decrypted")]
    Decrypt,
    #[error("pack cannot be decompressed")]
    Inflate,
    #[error("decompressed pack exceeds {max} bytes")]
    InflateTooLarge { max: usize },
    #[error("signed envelope is malformed")]
    MalformedEnvelope,
    #[error("unsupported envelope version {0}")]
    UnsupportedEnvelopeVersion(u64),
    #[error("organizer signature is invalid")]
    BadSignature,
    #[error("random number generator failed")]
    Random,
    /// `variant.kind` is not `stops-only` (§5.5).
    #[error("unsupported event variant {0:?}")]
    UnsupportedVariant(String),
    /// A stops-only variant still carries `routes[route].polyline` (§5.5).
    #[error("stops-only variant has a polyline on routes[{route}]")]
    VariantHasPolyline { route: usize },
    /// A full event lacks `routes[route].polyline` (§5.5).
    #[error("routes[{route}] has no polyline")]
    MissingPolyline { route: usize },
    /// A stops-only variant can only be made from a full event.
    #[error("not a full event")]
    NotFullEvent,
}

impl Error {
    /// Stable code shared with the test vectors (`expectedError`).
    pub fn code(&self) -> &'static str {
        match self {
            Error::Invalid(_) => "invalid_event",
            Error::MalformedEvent(_) => "malformed_event",
            Error::FragmentTooLong { .. } => "fragment_too_long",
            Error::MalformedLink => "malformed_link",
            Error::UnsupportedLinkVersion => "unsupported_link_version",
            Error::FileTooLarge { .. } => "file_too_large",
            Error::MalformedFile => "malformed_file",
            Error::UnsupportedFileFormat(_) => "unsupported_file_format",
            Error::UnsupportedSuite(_) => "unsupported_suite",
            Error::InvalidSecretCode(_) => "invalid_secret_code",
            Error::Decrypt => "decrypt",
            Error::Inflate => "inflate",
            Error::InflateTooLarge { .. } => "inflate_too_large",
            Error::MalformedEnvelope => "malformed_envelope",
            Error::UnsupportedEnvelopeVersion(_) => "unsupported_envelope_version",
            Error::BadSignature => "signature",
            Error::Random => "random",
            Error::UnsupportedVariant(_) => "unsupported_variant",
            Error::VariantHasPolyline { .. } => "variant_has_polyline",
            Error::MissingPolyline { .. } => "missing_polyline",
            Error::NotFullEvent => "not_full_event",
        }
    }
}
