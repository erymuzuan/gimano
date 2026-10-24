//! Publishing revisions (event-core `next_revision` → `seal` → `encode_link` / `encode_file`)
//! and turning imported packs into read-only documents. Nothing here touches the network.

use chrono::{DateTime, Utc};
use event_core::{
    Error as PackError, Event, GeometrySource, OpenedPack, OrganizerKey, PackKey, b64u,
    b64u_decode, encode_file, encode_link, link_fragment, revision_hash_b64u, seal,
};
use route_core::decode_polyline;
use serde::{Deserialize, Serialize};
use zeroize::Zeroizing;

use crate::assemble::{Change, Issue, build_event, change_summary, same_content};
use crate::doc::{
    Doc, DocKind, EventMeta, ImportedInfo, Leg, LegState, PublishedRev, RouteDraft, RoutePoint,
    StopDraft,
};
use crate::editing::next_generation;

/// Per-event secrets, stored only inside the encrypted local store (kind `secret`).
#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EventSecrets {
    seed: String,
    pack_key: String,
}

impl EventSecrets {
    pub fn generate() -> Result<Self, PackError> {
        let org = OrganizerKey::generate()?;
        let key = PackKey::generate()?;
        Ok(Self {
            seed: b64u(&Zeroizing::new(org.to_seed())[..]),
            pack_key: b64u(key.as_bytes()),
        })
    }

    pub fn organizer(&self) -> Option<OrganizerKey> {
        let seed: Zeroizing<[u8; 32]> = Zeroizing::new(b64u_decode(&self.seed)?.try_into().ok()?);
        Some(OrganizerKey::from_seed(&seed))
    }

    pub fn pack_key(&self) -> Option<PackKey> {
        Some(PackKey::from_bytes(
            b64u_decode(&self.pack_key)?.try_into().ok()?,
        ))
    }
}

impl Drop for EventSecrets {
    fn drop(&mut self) {
        use zeroize::Zeroize;
        self.seed.zeroize();
        self.pack_key.zeroize();
    }
}

/// The next revision to publish, or the last one again when nothing changed.
pub struct Prepared {
    pub event: Event,
    pub bytes: Vec<u8>,
    pub previous: Option<Event>,
    /// Same content as the last published revision: its bytes are re-sealed unchanged.
    pub reused: bool,
    pub issues: Vec<Issue>,
}

pub fn prepare(doc: &Doc, stops_only: bool, now: DateTime<Utc>) -> Result<Prepared, Vec<Issue>> {
    let previous = doc.published.last().and_then(|p| {
        let bytes = b64u_decode(&p.event_bytes)?;
        Event::from_bytes(&bytes).ok().map(|e| (e, bytes))
    });
    let (rev, parent, at) = match &previous {
        None => (1, None, now),
        Some((e, _)) => (e.rev, e.parent.clone(), e.published_at),
    };
    let (event, issues) = build_event(doc, rev, parent, at);
    let Some(mut event) = event else {
        return Err(issues);
    };
    if stops_only {
        event = event.without_route_geometry();
    }
    let (event, reused) = match &previous {
        None => (event, false),
        Some((prev, _)) if same_content(&event, prev) => (prev.clone(), true),
        Some((_, prev_bytes)) => (event.next_revision(prev_bytes, now), false),
    };
    if event.expires_at <= now {
        return Err(vec![Issue::error(
            "expiresAt",
            "expired",
            "access would already have ended",
        )]);
    }
    let bytes = match &previous {
        Some((_, b)) if reused => b.clone(),
        _ => event
            .to_bytes()
            .map_err(|e| vec![Issue::error("event", e.code(), e.to_string())])?,
    };
    Ok(Prepared {
        event,
        bytes,
        previous: previous.map(|(e, _)| e),
        reused,
        issues,
    })
}

pub fn changes(p: &Prepared) -> Vec<Change> {
    match &p.previous {
        Some(prev) if !p.reused => change_summary(prev, &p.event),
        _ => Vec::new(),
    }
}

/// Records a publication in the draft (no-op when the bytes were reused).
pub fn record(doc: &mut Doc, p: &Prepared) {
    if p.reused {
        return;
    }
    doc.published.push(PublishedRev {
        rev: p.event.rev,
        published_at: p.event.published_at,
        event_bytes: b64u(&p.bytes),
        hash: revision_hash_b64u(&p.bytes),
        expires_at: p.event.expires_at,
    });
}

/// Seals the exact bytes (`seal` validates, signs, encrypts and re-opens to verify).
pub fn seal_bytes(
    p: &Prepared,
    secrets: &EventSecrets,
) -> Result<(event_core::SealedPack, PackKey), PackError> {
    let org = secrets.organizer().ok_or(PackError::Random)?;
    let key = secrets.pack_key().ok_or(PackError::Random)?;
    let event = Event::from_bytes(&p.bytes)?;
    let sealed = seal(&event, &org, &key)?;
    Ok((sealed, key))
}

/// Fragment length (characters after `#`), even when it is over the limit.
pub fn fragment_len(sealed: &event_core::SealedPack, key: &PackKey) -> usize {
    match link_fragment(key, sealed) {
        Ok(f) => f.len(),
        Err(PackError::FragmentTooLong { len, .. }) => len,
        Err(_) => 0,
    }
}

pub fn link(
    site: &str,
    sealed: &event_core::SealedPack,
    key: &PackKey,
) -> Result<String, PackError> {
    encode_link(site, key, sealed)
}

pub fn file(sealed: &event_core::SealedPack) -> Result<Vec<u8>, PackError> {
    encode_file(sealed)
}

// ---------------------------------------------------------------------------------------------
// Imported packs → read-only documents.

fn route_from_event(r: &event_core::Route) -> RouteDraft {
    let pts =
        decode_polyline(&r.polyline, event_core::limits::MAX_POLYLINE_POINTS).unwrap_or_default();
    let mut d = RouteDraft::new(r.mode);
    d.id = r.id.clone();
    d.title = r.title.clone().unwrap_or_default();
    d.day = r.day.clone().unwrap_or_default();
    if pts.len() >= 2 {
        for p in [pts[0], pts[pts.len() - 1]] {
            d.points.push(RoutePoint {
                id: format!("i{}", next_generation()),
                lat: p.lat,
                lng: p.lng,
                stop_id: None,
            });
        }
        let mut leg = Leg::pending(next_generation());
        leg.state = match r.source {
            GeometrySource::Routed => LegState::Routed,
            GeometrySource::Sketch => LegState::Sketch,
            GeometrySource::Gpx => LegState::Gpx,
        };
        leg.geometry = pts;
        leg.distance_m = r.distance_m;
        leg.duration_s = r.duration_s;
        d.legs.push(leg);
    }
    d
}

fn fmt(t: &DateTime<chrono::FixedOffset>) -> String {
    t.to_rfc3339_opts(chrono::SecondsFormat::Secs, false)
}

pub fn doc_from_pack(opened: &OpenedPack, now: DateTime<Utc>) -> Doc {
    let e = &opened.event;
    Doc {
        id: e.id.clone(),
        kind: DocKind::Event,
        updated_at: now,
        meta: EventMeta {
            template: e.template,
            title: e.title.clone(),
            description: e.description.clone().unwrap_or_default(),
            lang: e.lang,
            tz: e.tz.clone(),
            start: fmt(&e.start),
            end: fmt(&e.end),
            expires_at: e
                .expires_at
                .to_rfc3339_opts(chrono::SecondsFormat::Secs, true),
            alias: e
                .organizer
                .as_ref()
                .and_then(|o| o.alias.clone())
                .unwrap_or_default(),
        },
        stops: e
            .stops
            .iter()
            .map(|s| StopDraft {
                id: s.id.clone(),
                kind: s.kind,
                label: s.label.clone(),
                lat: s.lat,
                lng: s.lng,
                entrance: s.entrance,
                arrive_from: s.arrive.as_ref().map(|a| fmt(&a.from)).unwrap_or_default(),
                arrive_to: s.arrive.as_ref().map(|a| fmt(&a.to)).unwrap_or_default(),
                dwell_min: s.dwell_min,
                notes: s.notes.clone().unwrap_or_default(),
                audience: s.audience.clone().unwrap_or_default(),
                organizer_note: String::new(),
            })
            .collect(),
        sessions: e.sessions.clone(),
        routes: e.routes.iter().map(route_from_event).collect(),
        published: Vec::new(),
        imported: Some(ImportedInfo {
            safety_code: opened.safety_code(),
            organizer_pub: b64u(&opened.organizer_pub),
            rev: e.rev,
            expires_at: e.expires_at,
        }),
    }
}

/// What the importer keeps per event id (pinned key + revisions), kind `imported`.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportRecord {
    pub organizer_pub: String,
    pub revisions: Vec<ImportedRev>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportedRev {
    pub rev: u32,
    pub hash: String,
    pub event_bytes: String,
}

impl ImportRecord {
    pub fn stored(&self) -> Option<event_core::StoredEvent> {
        let pub_key: [u8; 32] = b64u_decode(&self.organizer_pub)?.try_into().ok()?;
        Some(event_core::StoredEvent {
            organizer_pub: pub_key,
            revisions: self
                .revisions
                .iter()
                .filter_map(|r| {
                    Some(event_core::StoredRevision {
                        rev: r.rev,
                        hash: b64u_decode(&r.hash)?.try_into().ok()?,
                    })
                })
                .collect(),
        })
    }

    /// The newest revision (the first stored one when two share a rev after a fork).
    pub fn latest(&self) -> Option<OpenedPackLite> {
        let r = self.revisions.iter().max_by_key(|r| r.rev)?;
        let bytes = b64u_decode(&r.event_bytes)?;
        let event = Event::from_bytes(&bytes).ok()?;
        let organizer_pub = b64u_decode(&self.organizer_pub)?.try_into().ok()?;
        Some(OpenedPackLite {
            pack: OpenedPack {
                revision_hash: event_core::revision_hash(&bytes),
                event,
                event_bytes: bytes,
                organizer_pub,
            },
        })
    }
}

pub struct OpenedPackLite {
    pub pack: OpenedPack,
}
