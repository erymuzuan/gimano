#![cfg(feature = "http")]

use route_core::{
    HttpRoutingClient, LatLng, MatchRequest, RouteRequest, RoutingClient, RoutingError, TravelMode,
};
use serde_json::Value;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

/// One-shot HTTP server: returns the captured request line and body.
async fn serve_once(
    status: &'static str,
    body: &'static str,
) -> (String, tokio::task::JoinHandle<(String, Value)>) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}/api/v1/", listener.local_addr().unwrap());
    let handle = tokio::spawn(async move {
        let (mut sock, _) = listener.accept().await.unwrap();
        let mut buf = Vec::new();
        let mut chunk = [0u8; 4096];
        let (head_end, len) = loop {
            let n = sock.read(&mut chunk).await.unwrap();
            buf.extend_from_slice(&chunk[..n]);
            if let Some(pos) = buf.windows(4).position(|w| w == b"\r\n\r\n") {
                let head = String::from_utf8_lossy(&buf[..pos]).to_lowercase();
                let len = head
                    .lines()
                    .find_map(|l| l.strip_prefix("content-length:"))
                    .map(|v| v.trim().parse::<usize>().unwrap())
                    .unwrap_or(0);
                break (pos + 4, len);
            }
        };
        while buf.len() < head_end + len {
            let n = sock.read(&mut chunk).await.unwrap();
            buf.extend_from_slice(&chunk[..n]);
        }
        let request_line = String::from_utf8_lossy(&buf[..head_end])
            .lines()
            .next()
            .unwrap()
            .to_owned();
        let json: Value = serde_json::from_slice(&buf[head_end..head_end + len]).unwrap();
        let resp = format!(
            "HTTP/1.1 {status}\r\ncontent-type: application/json\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{body}",
            body.len()
        );
        sock.write_all(resp.as_bytes()).await.unwrap();
        (request_line, json)
    });
    (base, handle)
}

#[tokio::test]
async fn route_posts_stateless_coordinates_only() {
    let (base, server) = serve_once(
        "200 OK",
        r#"{"routes":[{"id":"x-abc","polyline":"_izlhA~rlgdF_{geC~ywl@","distanceM":250000,"durationS":36000,"legs":[{"distanceM":250000,"durationS":36000}]}]}"#,
    )
    .await;
    let client = HttpRoutingClient::new(base).unwrap();
    let routes = client
        .route(&RouteRequest {
            origin: LatLng::new(2.9871, 101.793),
            destination: LatLng::new(3.0, 101.8),
            waypoints: vec![LatLng::new(2.99, 101.795)],
            vehicle: TravelMode::Bicycle,
        })
        .await
        .unwrap();
    let (line, body) = server.await.unwrap();
    assert_eq!(line, "POST /api/v1/routes HTTP/1.1");
    assert_eq!(body["store"], Value::Bool(false));
    assert_eq!(body["vehicle"], "bicycle");
    assert!(!body.to_string().contains("name"));
    assert_eq!(routes[0].id, "x-abc");
    assert_eq!(routes[0].points.len(), 2);
    assert_eq!(routes[0].legs.len(), 1);
}

#[tokio::test]
async fn match_posts_points_and_maps_422() {
    let (base, server) = serve_once("422 Unprocessable Entity", r#"{"title":"no match"}"#).await;
    let client = HttpRoutingClient::new(base).unwrap();
    let err = client
        .match_line(&MatchRequest {
            points: vec![LatLng::new(3.1, 101.7), LatLng::new(3.11, 101.71)],
            vehicle: TravelMode::Walk,
        })
        .await
        .unwrap_err();
    assert_eq!(err, RoutingError::NoRoute);
    let (line, body) = server.await.unwrap();
    assert_eq!(line, "POST /api/v1/routes/match HTTP/1.1");
    assert_eq!(body["points"].as_array().unwrap().len(), 2);
    assert_eq!(body.as_object().unwrap().len(), 2);
}

#[tokio::test]
async fn invalid_request_never_hits_network() {
    let client = HttpRoutingClient::new("http://127.0.0.1:9").unwrap();
    let err = client
        .match_line(&MatchRequest {
            points: vec![LatLng::new(3.1, 101.7)],
            vehicle: TravelMode::Car,
        })
        .await
        .unwrap_err();
    assert!(matches!(err, RoutingError::InvalidRequest(_)));
}
