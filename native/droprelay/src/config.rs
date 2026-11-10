use std::net::SocketAddr;

use serde::Deserialize;

const DEFAULT_HTTP_BIND: &str = "[::]:80";
const DEFAULT_HTTPS_BIND: &str = "[::]:443";
const DEFAULT_QUIC_BIND: &str = "[::]:7842";

/// The relay's settings file: the same keys as the stock iroh-relay config, plus a `[drop]` table.
#[derive(Debug)]
pub struct Config {
    pub http_bind_addr: SocketAddr,
    pub enable_quic_addr_discovery: bool,
    pub tls: Option<TlsSettings>,
    pub client_rx: Option<RateSettings>,
    pub shared_tokens: Vec<String>,
    pub registry_url: String,
    pub registry_bearer_token: Option<String>,
}

#[derive(Debug, PartialEq, Eq)]
pub enum CertMode {
    LetsEncrypt,
    Manual,
}

#[derive(Debug)]
pub struct TlsSettings {
    pub https_bind_addr: SocketAddr,
    pub quic_bind_addr: SocketAddr,
    pub hostnames: Vec<String>,
    pub cert_mode: CertMode,
    pub cert_dir: String,
    pub manual_cert_path: String,
    pub manual_key_path: String,
    pub contact: Option<String>,
    pub prod_tls: bool,
}

#[derive(Debug, PartialEq, Eq)]
pub struct RateSettings {
    pub bytes_per_second: u32,
    pub max_burst_bytes: Option<u32>,
}

#[derive(Debug, PartialEq, Eq)]
pub enum ConfigError {
    Unreadable(String),
    NoTokens,
    NoRegistry,
    BadTls(String),
    BadLimit(String),
}

#[derive(Deserialize, Default)]
struct RawConfig {
    http_bind_addr: Option<String>,
    #[serde(default)]
    enable_quic_addr_discovery: bool,
    tls: Option<RawTls>,
    limits: Option<RawLimits>,
    access: Option<RawAccess>,
    #[serde(rename = "drop")]
    drop_registry: Option<RawDrop>,
}

#[derive(Deserialize)]
#[serde(untagged)]
enum OneOrMany {
    One(String),
    Many(Vec<String>),
}

#[derive(Deserialize)]
struct RawTls {
    https_bind_addr: Option<String>,
    quic_bind_addr: Option<String>,
    hostname: Option<OneOrMany>,
    cert_mode: Option<String>,
    cert_dir: Option<String>,
    manual_cert_path: Option<String>,
    manual_key_path: Option<String>,
    contact: Option<String>,
    prod_tls: Option<bool>,
}

#[derive(Deserialize, Default)]
struct RawLimits {
    client: Option<RawClientLimits>,
}

#[derive(Deserialize, Default)]
struct RawClientLimits {
    rx: Option<RawRate>,
}

#[derive(Deserialize, Default)]
struct RawRate {
    bytes_per_second: Option<u32>,
    max_burst_bytes: Option<u32>,
}

#[derive(Deserialize, Default)]
struct RawAccess {
    #[serde(default)]
    shared_token: Vec<String>,
}

#[derive(Deserialize, Default)]
struct RawDrop {
    registry_url: Option<String>,
    registry_bearer_token: Option<String>,
}

impl Config {
    pub fn parse(text: &str) -> Result<Config, ConfigError> {
        let raw: RawConfig = toml::from_str(text).map_err(|e| ConfigError::Unreadable(e.message().to_string()))?;
        let shared_tokens = raw.access.unwrap_or_default().shared_token;
        if shared_tokens.is_empty() || shared_tokens.iter().any(|token| token.is_empty()) {
            return Err(ConfigError::NoTokens);
        }
        let drop_table = raw.drop_registry.unwrap_or_default();
        let registry_url = match drop_table.registry_url {
            Some(url) if url.starts_with("https://") => url,
            _ => return Err(ConfigError::NoRegistry),
        };
        let http_bind_addr = raw
            .http_bind_addr
            .as_deref()
            .unwrap_or(DEFAULT_HTTP_BIND)
            .parse()
            .map_err(|_| ConfigError::Unreadable("http_bind_addr is not an address".into()))?;
        let tls = raw.tls.map(parse_tls).transpose()?;
        if raw.enable_quic_addr_discovery && tls.is_none() {
            return Err(ConfigError::BadTls("enable_quic_addr_discovery needs a [tls] section".into()));
        }
        let client_rx = parse_rate(raw.limits)?;
        Ok(Config {
            http_bind_addr,
            enable_quic_addr_discovery: raw.enable_quic_addr_discovery,
            tls,
            client_rx,
            shared_tokens,
            registry_url,
            registry_bearer_token: drop_table.registry_bearer_token,
        })
    }
}

fn address(text: Option<String>, default: &str, name: &str) -> Result<SocketAddr, ConfigError> {
    text.as_deref()
        .unwrap_or(default)
        .parse()
        .map_err(|_| ConfigError::BadTls(format!("{name} is not an address")))
}

fn parse_tls(raw: RawTls) -> Result<TlsSettings, ConfigError> {
    let cert_mode = match raw.cert_mode.as_deref() {
        Some("LetsEncrypt") => CertMode::LetsEncrypt,
        Some("Manual") => CertMode::Manual,
        other => return Err(ConfigError::BadTls(format!("cert_mode {other:?} is not supported (LetsEncrypt or Manual)"))),
    };
    let hostnames = match raw.hostname {
        Some(OneOrMany::One(name)) => vec![name],
        Some(OneOrMany::Many(names)) => names,
        None => Vec::new(),
    };
    let cert_dir = raw.cert_dir.unwrap_or_else(|| ".".to_string());
    if cert_mode == CertMode::LetsEncrypt {
        if hostnames.is_empty() {
            return Err(ConfigError::BadTls("LetsEncrypt needs a hostname".into()));
        }
        if raw.contact.as_deref().is_none_or(str::is_empty) {
            return Err(ConfigError::BadTls("LetsEncrypt needs a contact".into()));
        }
    }
    Ok(TlsSettings {
        https_bind_addr: address(raw.https_bind_addr, DEFAULT_HTTPS_BIND, "https_bind_addr")?,
        quic_bind_addr: address(raw.quic_bind_addr, DEFAULT_QUIC_BIND, "quic_bind_addr")?,
        manual_cert_path: raw.manual_cert_path.unwrap_or_else(|| format!("{cert_dir}/default.crt")),
        manual_key_path: raw.manual_key_path.unwrap_or_else(|| format!("{cert_dir}/default.key")),
        hostnames,
        cert_mode,
        cert_dir,
        contact: raw.contact,
        prod_tls: raw.prod_tls.unwrap_or(true),
    })
}

fn parse_rate(limits: Option<RawLimits>) -> Result<Option<RateSettings>, ConfigError> {
    let Some(rx) = limits.and_then(|l| l.client).and_then(|c| c.rx) else {
        return Ok(None);
    };
    match (rx.bytes_per_second, rx.max_burst_bytes) {
        (None, None) => Ok(None),
        (None, Some(_)) => Err(ConfigError::BadLimit("max_burst_bytes needs bytes_per_second".into())),
        (Some(0), _) | (_, Some(0)) => Err(ConfigError::BadLimit("limits must be above zero".into())),
        (Some(bytes_per_second), max_burst_bytes) => Ok(Some(RateSettings { bytes_per_second, max_burst_bytes })),
    }
}
