use std::time::Duration;

use serde_json::Value;

use super::{
    MatchRequest, MatchedPath, RouteRequest, RoutedPath, RoutingClient, RoutingError,
    match_request_body, parse_match_response, parse_routes_response, route_request_body,
    status_error,
};

const MAX_RESPONSE_BYTES: usize = 32 * 1024 * 1024;

/// HTTP client for the Manotu routing API, e.g. base `http://100.101.150.9:5080/api/v1`.
#[derive(Debug, Clone)]
pub struct HttpRoutingClient {
    client: reqwest::Client,
    base_url: String,
}

impl HttpRoutingClient {
    pub fn new(base_url: impl Into<String>) -> Result<Self, RoutingError> {
        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(30))
            .build()
            .map_err(|e| RoutingError::Transport(e.to_string()))?;
        Ok(Self::with_client(client, base_url))
    }

    pub fn with_client(client: reqwest::Client, base_url: impl Into<String>) -> Self {
        let base_url = base_url.into().trim_end_matches('/').to_owned();
        Self { client, base_url }
    }

    pub fn base_url(&self) -> &str {
        &self.base_url
    }

    async fn post(&self, path: &str, body: &Value) -> Result<Vec<u8>, RoutingError> {
        let resp = self
            .client
            .post(format!("{}{path}", self.base_url))
            .header("content-type", "application/json")
            .body(body.to_string())
            .send()
            .await
            .map_err(|e| RoutingError::Transport(e.without_url().to_string()))?;
        if let Some(err) = status_error(resp.status().as_u16()) {
            return Err(err);
        }
        let bytes = resp
            .bytes()
            .await
            .map_err(|e| RoutingError::Transport(e.without_url().to_string()))?;
        if bytes.len() > MAX_RESPONSE_BYTES {
            return Err(RoutingError::InvalidResponse("response too large".into()));
        }
        Ok(bytes.to_vec())
    }
}

impl RoutingClient for HttpRoutingClient {
    async fn route(&self, req: &RouteRequest) -> Result<Vec<RoutedPath>, RoutingError> {
        let body = route_request_body(req)?;
        parse_routes_response(&self.post("/routes", &body).await?)
    }

    async fn match_line(&self, req: &MatchRequest) -> Result<MatchedPath, RoutingError> {
        let body = match_request_body(req)?;
        parse_match_response(&self.post("/routes/match", &body).await?)
    }
}
