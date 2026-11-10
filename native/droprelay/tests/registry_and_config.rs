use droprelay::config::{Config, ConfigError};
use droprelay::registry::answer_allows;

#[test]
fn only_status_200_with_the_body_true_lets_a_guest_in() {
    assert!(answer_allows(200, "true"));
    assert!(answer_allows(200, "true\n"));
    assert!(!answer_allows(200, "false"));
    assert!(!answer_allows(200, ""));
    assert!(!answer_allows(200, "truee"));
    assert!(!answer_allows(404, "true"));
    assert!(!answer_allows(500, "true"));
    assert!(!answer_allows(204, ""));
}

const LIVE_CONFIG_SHAPE: &str = r#"
http_bind_addr = "[::]:80"
enable_quic_addr_discovery = true

[tls]
https_bind_addr = "[::]:443"
quic_bind_addr = "[::]:7842"
hostname = "relay.example.com"
cert_mode = "LetsEncrypt"
cert_dir = "/var/lib/iroh-relay/certs"
contact = "someone@example.com"

[access]
shared_token = ["token-one", "token-two"]

[drop]
registry_url = "https://example.com/api/drop/check"
registry_bearer_token = "service-secret"
"#;

#[test]
fn the_existing_relay_config_plus_a_drop_table_is_accepted() {
    let config = Config::parse(LIVE_CONFIG_SHAPE).unwrap();
    assert_eq!(config.shared_tokens, vec!["token-one".to_string(), "token-two".to_string()]);
    assert_eq!(config.registry_url, "https://example.com/api/drop/check");
    assert_eq!(config.registry_bearer_token.as_deref(), Some("service-secret"));
    assert_eq!(config.http_bind_addr.port(), 80);
}

#[test]
fn a_config_without_tokens_is_refused_so_the_relay_never_opens_up() {
    let text = LIVE_CONFIG_SHAPE.replace(r#"shared_token = ["token-one", "token-two"]"#, "shared_token = []");
    assert_eq!(Config::parse(&text).unwrap_err(), ConfigError::NoTokens);
    let text = LIVE_CONFIG_SHAPE.replace("[access]\nshared_token = [\"token-one\", \"token-two\"]\n", "");
    assert_eq!(Config::parse(&text).unwrap_err(), ConfigError::NoTokens);
}

#[test]
fn a_config_without_a_registry_address_is_refused() {
    let text = LIVE_CONFIG_SHAPE.replace(r#"registry_url = "https://example.com/api/drop/check""#, "");
    assert_eq!(Config::parse(&text).unwrap_err(), ConfigError::NoRegistry);
}

#[test]
fn the_registry_address_must_be_https() {
    let text = LIVE_CONFIG_SHAPE.replace("https://example.com/api/drop/check", "http://example.com/api/drop/check");
    assert_eq!(Config::parse(&text).unwrap_err(), ConfigError::NoRegistry);
}

#[test]
fn unreadable_text_is_reported_not_ignored() {
    assert!(matches!(Config::parse("this is = = not toml"), Err(ConfigError::Unreadable(_))));
}

use droprelay::config::{CertMode, RateSettings};

#[test]
fn the_tls_section_and_quic_switch_are_read() {
    let config = Config::parse(LIVE_CONFIG_SHAPE).unwrap();
    assert!(config.enable_quic_addr_discovery);
    let tls = config.tls.expect("tls section");
    assert_eq!(tls.https_bind_addr.port(), 443);
    assert_eq!(tls.quic_bind_addr.port(), 7842);
    assert_eq!(tls.hostnames, vec!["relay.example.com".to_string()]);
    assert_eq!(tls.cert_mode, CertMode::LetsEncrypt);
    assert_eq!(tls.cert_dir, "/var/lib/iroh-relay/certs");
    assert_eq!(tls.contact.as_deref(), Some("someone@example.com"));
    assert!(tls.prod_tls);
}

#[test]
fn a_config_with_no_tls_section_runs_plain_http() {
    let text = LIVE_CONFIG_SHAPE.replace("enable_quic_addr_discovery = true", "");
    let text = text[..text.find("[tls]").unwrap()].to_string() + &text[text.find("[access]").unwrap()..];
    assert!(Config::parse(&text).unwrap().tls.is_none());
}

#[test]
fn cert_modes_other_than_lets_encrypt_and_manual_are_refused() {
    let text = LIVE_CONFIG_SHAPE.replace("LetsEncrypt", "Reloading");
    assert!(matches!(Config::parse(&text), Err(ConfigError::BadTls(_))));
}

#[test]
fn lets_encrypt_needs_a_hostname_and_a_contact() {
    let no_contact = LIVE_CONFIG_SHAPE.replace(r#"contact = "someone@example.com""#, "");
    assert!(matches!(Config::parse(&no_contact), Err(ConfigError::BadTls(_))));
    let no_host = LIVE_CONFIG_SHAPE.replace(r#"hostname = "relay.example.com""#, "");
    assert!(matches!(Config::parse(&no_host), Err(ConfigError::BadTls(_))));
}

#[test]
fn quic_discovery_without_tls_is_refused() {
    let text = LIVE_CONFIG_SHAPE;
    let text = text[..text.find("[tls]").unwrap()].to_string() + &text[text.find("[access]").unwrap()..];
    assert!(matches!(Config::parse(&text), Err(ConfigError::BadTls(_))));
}

#[test]
fn the_speed_limit_is_read_and_must_have_a_rate() {
    let with_limit = format!("{LIVE_CONFIG_SHAPE}\n[limits.client.rx]\nbytes_per_second = 12000000\nmax_burst_bytes = 24000000\n");
    let config = Config::parse(&with_limit).unwrap();
    assert_eq!(config.client_rx, Some(RateSettings { bytes_per_second: 12_000_000, max_burst_bytes: Some(24_000_000) }));
    let burst_only = format!("{LIVE_CONFIG_SHAPE}\n[limits.client.rx]\nmax_burst_bytes = 24000000\n");
    assert!(matches!(Config::parse(&burst_only), Err(ConfigError::BadLimit(_))));
    let zero = format!("{LIVE_CONFIG_SHAPE}\n[limits.client.rx]\nbytes_per_second = 0\n");
    assert!(matches!(Config::parse(&zero), Err(ConfigError::BadLimit(_))));
    assert_eq!(Config::parse(LIVE_CONFIG_SHAPE).unwrap().client_rx, None);
}
