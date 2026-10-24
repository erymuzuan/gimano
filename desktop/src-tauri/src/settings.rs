//! App settings (`settings.json` in the app config dir). They hold no private data: an API base
//! URL, the online-tiles switch, the UI language and the invitation site.

use std::path::Path;

use event_core::Lang;
use serde::{Deserialize, Serialize};

pub const DEFAULT_API: &str = "https://gimano-api.bukitbunga.com/api/v1";
pub const DEV_API: &str = "http://100.101.150.9:5080/api/v1";
/// OpenFreeMap, as in `site/manotu/s/config.js`. The CSP allows only this tile host, so the
/// style URL is fixed at build time.
pub const STYLE_URL: &str = "https://tiles.openfreemap.org/styles/positron";
pub const TILE_HOST: &str = "https://tiles.openfreemap.org";

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Settings {
    pub api_base_url: String,
    pub tiles_online: bool,
    pub lang: Lang,
    pub invite_site: String,
}

impl Default for Settings {
    fn default() -> Self {
        Settings {
            api_base_url: DEFAULT_API.into(),
            tiles_online: true,
            lang: Lang::Ms,
            invite_site: event_core::DEFAULT_SITE.into(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum SettingsError {
    #[error("the API address must start with https:// (or http:// for a development server)")]
    BadApiUrl,
    #[error("the invitation site must start with https://")]
    BadSite,
}

fn url_ok(s: &str, allow_http: bool) -> bool {
    let rest = s
        .strip_prefix("https://")
        .or_else(|| s.strip_prefix("http://").filter(|_| allow_http));
    rest.is_some_and(|r| {
        !r.is_empty() && !r.starts_with('/') && !r.contains(['?', '#', ' ', '@', '\\'])
    })
}

impl Settings {
    pub fn validate(&self) -> Result<(), SettingsError> {
        if !url_ok(&self.api_base_url, true) {
            return Err(SettingsError::BadApiUrl);
        }
        if !url_ok(&self.invite_site, false) {
            return Err(SettingsError::BadSite);
        }
        Ok(())
    }

    pub fn normalized(mut self) -> Self {
        self.api_base_url = self.api_base_url.trim().trim_end_matches('/').to_owned();
        self.invite_site = self.invite_site.trim().trim_end_matches('/').to_owned();
        self
    }

    pub fn load(path: &Path) -> Settings {
        std::fs::read(path)
            .ok()
            .and_then(|b| serde_json::from_slice::<Settings>(&b).ok())
            .map(Settings::normalized)
            .filter(|s| s.validate().is_ok())
            .unwrap_or_default()
    }

    pub fn save(&self, path: &Path) -> std::io::Result<()> {
        if let Some(dir) = path.parent() {
            std::fs::create_dir_all(dir)?;
        }
        std::fs::write(
            path,
            serde_json::to_vec_pretty(self).map_err(std::io::Error::other)?,
        )
    }

    /// `…/api/v1` → `…/readyz` (health check used by the online indicator).
    pub fn readyz_url(&self) -> String {
        let base = self.api_base_url.trim_end_matches('/');
        let root = base.strip_suffix("/api/v1").unwrap_or(base);
        format!("{root}/readyz")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn validates_urls() {
        let mut s = Settings::default();
        assert!(s.validate().is_ok());
        s.api_base_url = DEV_API.into();
        assert!(s.validate().is_ok());
        assert_eq!(s.readyz_url(), "http://100.101.150.9:5080/readyz");
        s.api_base_url = "javascript:alert(1)".into();
        assert_eq!(s.validate(), Err(SettingsError::BadApiUrl));
        s.api_base_url = "https://user@evil.example".into();
        assert!(s.validate().is_err());
        s = Settings::default();
        s.invite_site = "http://www.bukitbunga.com/gimano".into();
        assert_eq!(s.validate(), Err(SettingsError::BadSite));
    }
}
