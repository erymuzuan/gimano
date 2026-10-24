//! Bounded GPX 1.1 import (also reads 1.0) and privacy-preserving export.
//!
//! Import safety: the input size is checked before parsing, DTDs are refused (so no entity
//! expansion or external entities), the XML node count and point count are capped, and every
//! coordinate is range-checked. Names are treated as plain text, trimmed and truncated.

use std::fmt::Write as _;

use crate::geo::LatLng;

pub const MAX_NAME_CHARS: usize = 200;
const MAX_TIME_CHARS: usize = 40;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct GpxLimits {
    pub max_bytes: usize,
    /// Track points + route points + waypoints.
    pub max_points: usize,
    pub max_xml_nodes: u32,
}

impl Default for GpxLimits {
    fn default() -> Self {
        Self {
            max_bytes: 20 * 1024 * 1024,
            max_points: 50_000,
            max_xml_nodes: 2_000_000,
        }
    }
}

#[derive(Debug, Clone, PartialEq, thiserror::Error)]
pub enum GpxError {
    #[error("GPX file is larger than {limit} bytes")]
    TooLarge { limit: usize },
    #[error("GPX has more than {limit} points")]
    TooManyPoints { limit: usize },
    #[error("GPX is not valid UTF-8")]
    NotUtf8,
    #[error("GPX is not well-formed or uses a forbidden construct: {0}")]
    Xml(String),
    #[error("root element is not <gpx>")]
    NotGpx,
    #[error("invalid coordinate in <{element}> #{index}")]
    InvalidCoordinate { element: &'static str, index: usize },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GpxPathKind {
    /// `<trk>`: recorded geometry, not necessarily a road route.
    Track,
    /// `<rte>`: ordered route points.
    Route,
}

#[derive(Debug, Clone, PartialEq)]
pub struct GpxPoint {
    pub pos: LatLng,
    pub ele: Option<f64>,
    pub time: Option<String>,
}

impl GpxPoint {
    pub fn new(pos: LatLng) -> Self {
        Self {
            pos,
            ele: None,
            time: None,
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct GpxPath {
    pub kind: GpxPathKind,
    pub name: Option<String>,
    /// A route has exactly one segment; a track has one per `<trkseg>`.
    pub segments: Vec<Vec<GpxPoint>>,
}

impl GpxPath {
    pub fn points(&self) -> impl Iterator<Item = &GpxPoint> {
        self.segments.iter().flatten()
    }

    pub fn positions(&self) -> Vec<LatLng> {
        self.points().map(|p| p.pos).collect()
    }

    pub fn elevation_samples(&self) -> Vec<Option<f64>> {
        self.points().map(|p| p.ele).collect()
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct GpxWaypoint {
    pub point: GpxPoint,
    pub name: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Default)]
pub struct GpxDocument {
    pub paths: Vec<GpxPath>,
    pub waypoints: Vec<GpxWaypoint>,
    pub point_count: usize,
}

impl GpxDocument {
    pub fn has_tracks(&self) -> bool {
        self.paths.iter().any(|p| p.kind == GpxPathKind::Track)
    }

    pub fn has_routes(&self) -> bool {
        self.paths.iter().any(|p| p.kind == GpxPathKind::Route)
    }

    fn all_points(&self) -> impl Iterator<Item = &GpxPoint> {
        self.paths
            .iter()
            .flat_map(|p| p.points())
            .chain(self.waypoints.iter().map(|w| &w.point))
    }

    /// Recorded times exist; they describe the past, never a future schedule.
    pub fn has_timestamps(&self) -> bool {
        self.all_points().any(|p| p.time.is_some())
    }

    pub fn has_elevation(&self) -> bool {
        self.all_points().any(|p| p.ele.is_some())
    }
}

pub fn parse_gpx(input: &[u8], limits: &GpxLimits) -> Result<GpxDocument, GpxError> {
    if input.len() > limits.max_bytes {
        return Err(GpxError::TooLarge {
            limit: limits.max_bytes,
        });
    }
    let text = std::str::from_utf8(input).map_err(|_| GpxError::NotUtf8)?;
    let text = text.strip_prefix('\u{feff}').unwrap_or(text);
    let opts = roxmltree::ParsingOptions {
        allow_dtd: false,
        nodes_limit: limits.max_xml_nodes,
        entity_resolver: None,
    };
    let doc = roxmltree::Document::parse_with_options(text, opts)
        .map_err(|e| GpxError::Xml(e.to_string()))?;
    let root = doc.root_element();
    if root.tag_name().name() != "gpx" {
        return Err(GpxError::NotGpx);
    }

    let mut out = GpxDocument::default();
    let mut count = 0usize;
    let mut take = |node: roxmltree::Node, element: &'static str| -> Result<GpxPoint, GpxError> {
        count += 1;
        if count > limits.max_points {
            return Err(GpxError::TooManyPoints {
                limit: limits.max_points,
            });
        }
        read_point(node, element, count - 1)
    };

    for child in root.children().filter(|n| n.is_element()) {
        match child.tag_name().name() {
            "wpt" => {
                let point = take(child, "wpt")?;
                out.waypoints.push(GpxWaypoint {
                    point,
                    name: child_text(child, "name").map(clean_name),
                });
            }
            "rte" => {
                let mut seg = Vec::new();
                for p in elements(child, "rtept") {
                    seg.push(take(p, "rtept")?);
                }
                out.paths.push(GpxPath {
                    kind: GpxPathKind::Route,
                    name: child_text(child, "name").map(clean_name),
                    segments: vec![seg],
                });
            }
            "trk" => {
                let mut segments = Vec::new();
                for s in elements(child, "trkseg") {
                    let mut seg = Vec::new();
                    for p in elements(s, "trkpt") {
                        seg.push(take(p, "trkpt")?);
                    }
                    segments.push(seg);
                }
                out.paths.push(GpxPath {
                    kind: GpxPathKind::Track,
                    name: child_text(child, "name").map(clean_name),
                    segments,
                });
            }
            _ => {}
        }
    }
    out.point_count = count;
    Ok(out)
}

fn elements<'a, 'i>(
    node: roxmltree::Node<'a, 'i>,
    name: &'static str,
) -> impl Iterator<Item = roxmltree::Node<'a, 'i>> {
    node.children()
        .filter(move |n| n.is_element() && n.tag_name().name() == name)
}

fn child_text<'a>(node: roxmltree::Node<'a, '_>, name: &'static str) -> Option<&'a str> {
    elements(node, name).next().and_then(|n| n.text())
}

fn read_point(
    node: roxmltree::Node,
    element: &'static str,
    index: usize,
) -> Result<GpxPoint, GpxError> {
    let bad = || GpxError::InvalidCoordinate { element, index };
    let lat = node.attribute("lat").and_then(parse_f64).ok_or_else(bad)?;
    let lng = node.attribute("lon").and_then(parse_f64).ok_or_else(bad)?;
    let pos = LatLng::new(lat, lng);
    if !pos.is_valid() {
        return Err(bad());
    }
    let ele = child_text(node, "ele").and_then(parse_f64);
    let time = child_text(node, "time")
        .map(str::trim)
        .filter(|t| !t.is_empty())
        .map(|t| t.chars().take(MAX_TIME_CHARS).collect());
    Ok(GpxPoint { pos, ele, time })
}

fn parse_f64(s: &str) -> Option<f64> {
    s.trim().parse::<f64>().ok().filter(|v| v.is_finite())
}

fn clean_name(s: &str) -> String {
    s.chars()
        .map(|c| if c.is_control() { ' ' } else { c })
        .collect::<String>()
        .trim()
        .chars()
        .take(MAX_NAME_CHARS)
        .collect()
}

/// What to write. Timestamps and document metadata are stripped unless asked for.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct GpxExportOptions {
    /// Write `<metadata>` (document name and time).
    pub include_metadata: bool,
    pub include_timestamps: bool,
    pub include_elevation: bool,
}

impl Default for GpxExportOptions {
    fn default() -> Self {
        Self {
            include_metadata: false,
            include_timestamps: false,
            include_elevation: true,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Default)]
pub struct GpxExport {
    pub name: Option<String>,
    /// RFC 3339; written only with `include_metadata`.
    pub time: Option<String>,
    pub paths: Vec<GpxPath>,
    pub waypoints: Vec<GpxWaypoint>,
}

/// GPX is plaintext without expiry: the UI must confirm with the user before writing it.
pub fn export_gpx(doc: &GpxExport, opts: &GpxExportOptions) -> String {
    let mut x = String::new();
    x.push_str("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    x.push_str(
        "<gpx version=\"1.1\" creator=\"Manotu\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n",
    );
    if opts.include_metadata && (doc.name.is_some() || doc.time.is_some()) {
        x.push_str("  <metadata>\n");
        if let Some(n) = &doc.name {
            let _ = writeln!(x, "    <name>{}</name>", escape(n));
        }
        if let Some(t) = &doc.time {
            let _ = writeln!(x, "    <time>{}</time>", escape(t));
        }
        x.push_str("  </metadata>\n");
    }
    for w in &doc.waypoints {
        write_point(&mut x, "wpt", &w.point, w.name.as_deref(), opts, "  ");
    }
    for p in &doc.paths {
        match p.kind {
            GpxPathKind::Route => {
                x.push_str("  <rte>\n");
                write_name(&mut x, p.name.as_deref(), "    ");
                for pt in p.points() {
                    write_point(&mut x, "rtept", pt, None, opts, "    ");
                }
                x.push_str("  </rte>\n");
            }
            GpxPathKind::Track => {
                x.push_str("  <trk>\n");
                write_name(&mut x, p.name.as_deref(), "    ");
                for seg in &p.segments {
                    x.push_str("    <trkseg>\n");
                    for pt in seg {
                        write_point(&mut x, "trkpt", pt, None, opts, "      ");
                    }
                    x.push_str("    </trkseg>\n");
                }
                x.push_str("  </trk>\n");
            }
        }
    }
    x.push_str("</gpx>\n");
    x
}

fn write_name(x: &mut String, name: Option<&str>, indent: &str) {
    if let Some(n) = name {
        let _ = writeln!(x, "{indent}<name>{}</name>", escape(n));
    }
}

fn write_point(
    x: &mut String,
    tag: &str,
    p: &GpxPoint,
    name: Option<&str>,
    opts: &GpxExportOptions,
    indent: &str,
) {
    let ele = p.ele.filter(|e| opts.include_elevation && e.is_finite());
    let time = p.time.as_deref().filter(|_| opts.include_timestamps);
    let head = format!(
        "{indent}<{tag} lat=\"{}\" lon=\"{}\"",
        fmt6(p.pos.lat),
        fmt6(p.pos.lng)
    );
    if ele.is_none() && time.is_none() && name.is_none() {
        let _ = writeln!(x, "{head}/>");
        return;
    }
    let _ = writeln!(x, "{head}>");
    if let Some(e) = ele {
        let _ = writeln!(x, "{indent}  <ele>{}</ele>", fmt_ele(e));
    }
    if let Some(t) = time {
        let _ = writeln!(x, "{indent}  <time>{}</time>", escape(t));
    }
    write_name(x, name, &format!("{indent}  "));
    let _ = writeln!(x, "{indent}</{tag}>");
}

fn fmt6(v: f64) -> String {
    let s = format!("{v:.6}");
    let s = s.trim_end_matches('0').trim_end_matches('.');
    if s == "-0" { "0".into() } else { s.into() }
}

fn fmt_ele(v: f64) -> String {
    let s = format!("{v:.1}");
    s.strip_suffix(".0").map(str::to_owned).unwrap_or(s)
}

fn escape(s: &str) -> String {
    let mut o = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '&' => o.push_str("&amp;"),
            '<' => o.push_str("&lt;"),
            '>' => o.push_str("&gt;"),
            '"' => o.push_str("&quot;"),
            '\'' => o.push_str("&apos;"),
            '\t' | '\n' | '\r' => o.push(c),
            c if c.is_control() => o.push(' '),
            c => o.push(c),
        }
    }
    o
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = r#"<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
  <metadata><name>Secret ride</name><time>2026-01-01T00:00:00Z</time></metadata>
  <wpt lat="2.9871" lon="101.793"><ele>40</ele><name>Start &amp; end</name></wpt>
  <rte><name>Plan</name>
    <rtept lat="2.98" lon="101.79"/><rtept lat="2.99" lon="101.80"/>
  </rte>
  <trk><name>Morning</name>
    <trkseg>
      <trkpt lat="2.9" lon="101.7"><ele>10</ele><time>2026-01-01T00:00:00Z</time></trkpt>
      <trkpt lat="2.91" lon="101.71"><ele>30.5</ele><time>2026-01-01T00:01:00Z</time></trkpt>
    </trkseg>
    <trkseg><trkpt lat="2.92" lon="101.72"/></trkseg>
  </trk>
</gpx>"#;

    #[test]
    fn imports_tracks_routes_and_waypoints() {
        let d = parse_gpx(SAMPLE.as_bytes(), &GpxLimits::default()).unwrap();
        assert_eq!(d.point_count, 6);
        assert!(d.has_tracks() && d.has_routes());
        assert!(d.has_timestamps() && d.has_elevation());
        assert_eq!(d.waypoints[0].name.as_deref(), Some("Start & end"));
        let trk = d
            .paths
            .iter()
            .find(|p| p.kind == GpxPathKind::Track)
            .unwrap();
        assert_eq!(trk.segments.len(), 2);
        assert_eq!(trk.positions().len(), 3);
        assert_eq!(trk.elevation_samples(), vec![Some(10.0), Some(30.5), None]);
        let rte = d
            .paths
            .iter()
            .find(|p| p.kind == GpxPathKind::Route)
            .unwrap();
        assert_eq!(rte.name.as_deref(), Some("Plan"));
        assert!(!rte.points().any(|p| p.time.is_some()));
    }

    #[test]
    fn route_only_reports_no_timestamps_or_elevation() {
        let g = r#"<gpx version="1.0"><rte><rtept lat="1" lon="2"/><rtept lat="1.1" lon="2.1"/></rte></gpx>"#;
        let d = parse_gpx(g.as_bytes(), &GpxLimits::default()).unwrap();
        assert!(d.has_routes() && !d.has_tracks());
        assert!(!d.has_timestamps() && !d.has_elevation());
    }

    #[test]
    fn rejects_dtd_and_entity_expansion() {
        let lol = r#"<?xml version="1.0"?>
<!DOCTYPE lolz [
 <!ENTITY lol "lol">
 <!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
 <!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">
]>
<gpx><wpt lat="1" lon="1"><name>&lol3;</name></wpt></gpx>"#;
        assert!(matches!(
            parse_gpx(lol.as_bytes(), &GpxLimits::default()),
            Err(GpxError::Xml(_))
        ));
        let xxe = r#"<?xml version="1.0"?>
<!DOCTYPE gpx [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
<gpx><wpt lat="1" lon="1"><name>&xxe;</name></wpt></gpx>"#;
        assert!(matches!(
            parse_gpx(xxe.as_bytes(), &GpxLimits::default()),
            Err(GpxError::Xml(_))
        ));
    }

    #[test]
    fn rejects_oversized_input_before_parsing() {
        let limits = GpxLimits {
            max_bytes: 100,
            ..GpxLimits::default()
        };
        let big = format!("<gpx>{}</gpx>", " ".repeat(200));
        assert_eq!(
            parse_gpx(big.as_bytes(), &limits),
            Err(GpxError::TooLarge { limit: 100 })
        );
    }

    #[test]
    fn rejects_too_many_points() {
        let limits = GpxLimits {
            max_points: 3,
            ..GpxLimits::default()
        };
        let pts = r#"<trkpt lat="1" lon="1"/>"#.repeat(4);
        let g = format!("<gpx><trk><trkseg>{pts}</trkseg></trk></gpx>");
        assert_eq!(
            parse_gpx(g.as_bytes(), &limits),
            Err(GpxError::TooManyPoints { limit: 3 })
        );
    }

    #[test]
    fn rejects_too_many_xml_nodes() {
        let limits = GpxLimits {
            max_xml_nodes: 50,
            ..GpxLimits::default()
        };
        let g = format!("<gpx>{}</gpx>", "<x/>".repeat(100));
        assert!(matches!(
            parse_gpx(g.as_bytes(), &limits),
            Err(GpxError::Xml(_))
        ));
    }

    #[test]
    fn rejects_bad_coordinates() {
        for bad in [
            r#"<gpx><wpt lat="91" lon="1"/></gpx>"#,
            r#"<gpx><wpt lat="1" lon="-180.5"/></gpx>"#,
            r#"<gpx><wpt lat="NaN" lon="1"/></gpx>"#,
            r#"<gpx><wpt lat="inf" lon="1"/></gpx>"#,
            r#"<gpx><wpt lat="abc" lon="1"/></gpx>"#,
            r#"<gpx><wpt lon="1"/></gpx>"#,
        ] {
            assert!(
                matches!(
                    parse_gpx(bad.as_bytes(), &GpxLimits::default()),
                    Err(GpxError::InvalidCoordinate {
                        element: "wpt",
                        index: 0
                    })
                ),
                "{bad}"
            );
        }
    }

    #[test]
    fn rejects_non_gpx_and_garbage() {
        let l = GpxLimits::default();
        assert_eq!(parse_gpx(b"<kml/>", &l), Err(GpxError::NotGpx));
        assert!(matches!(parse_gpx(b"<gpx>", &l), Err(GpxError::Xml(_))));
        assert_eq!(parse_gpx(&[0xff, 0xfe, 0x00], &l), Err(GpxError::NotUtf8));
    }

    #[test]
    fn export_strips_metadata_and_timestamps_by_default() {
        let d = parse_gpx(SAMPLE.as_bytes(), &GpxLimits::default()).unwrap();
        let exp = GpxExport {
            name: Some("Secret ride".into()),
            time: Some("2026-01-01T00:00:00Z".into()),
            paths: d.paths.clone(),
            waypoints: d.waypoints.clone(),
        };
        let out = export_gpx(&exp, &GpxExportOptions::default());
        assert!(!out.contains("<time>") && !out.contains("<metadata>"));
        assert!(!out.contains("Secret ride"));
        assert!(out.contains("<ele>30.5</ele>"));
        assert!(out.contains("Start &amp; end"));

        let back = parse_gpx(out.as_bytes(), &GpxLimits::default()).unwrap();
        assert_eq!(back.point_count, d.point_count);
        assert!(!back.has_timestamps());
        assert_eq!(back.paths[0].positions(), d.paths[0].positions());

        let full = export_gpx(
            &exp,
            &GpxExportOptions {
                include_metadata: true,
                include_timestamps: true,
                include_elevation: false,
            },
        );
        assert!(full.contains("<metadata>") && full.contains("<time>2026-01-01T00:01:00Z</time>"));
        assert!(!full.contains("<ele>"));
    }

    #[test]
    fn export_escapes_markup_in_names() {
        let exp = GpxExport {
            paths: vec![GpxPath {
                kind: GpxPathKind::Route,
                name: Some("<script>\"x\"</script>".into()),
                segments: vec![vec![GpxPoint::new(LatLng::new(-0.0000001, 1.5))]],
            }],
            ..GpxExport::default()
        };
        let out = export_gpx(&exp, &GpxExportOptions::default());
        assert!(out.contains("&lt;script&gt;&quot;x&quot;&lt;/script&gt;"));
        assert!(out.contains(r#"<rtept lat="0" lon="1.5"/>"#));
        assert!(parse_gpx(out.as_bytes(), &GpxLimits::default()).is_ok());
    }
}
