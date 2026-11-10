//! The settings file turned into a running relay: the same rule and the speed limit must reach the server.

use droprelay::config::Config;
use iroh_base::{RelayUrl, SecretKey};
use iroh_dns::dns::DnsResolver;
use iroh_relay::client::{ClientBuilder, ConnectError};
use iroh_relay::protos::handshake;
use iroh_relay::server::Server;
use iroh_relay::tls::{CaTlsConfig, default_provider};

const PLAIN_CONFIG: &str = r#"
http_bind_addr = "127.0.0.1:0"

[access]
shared_token = ["paired-device-token"]

[drop]
registry_url = "https://registry.invalid/check"

[limits.client.rx]
bytes_per_second = 12000000
max_burst_bytes = 24000000
"#;

async fn connect(url: &RelayUrl, token: Option<&str>) -> Result<(), ConnectError> {
    let tls = CaTlsConfig::default().client_config(default_provider()).unwrap();
    let mut builder = ClientBuilder::new(url.clone(), SecretKey::generate(), DnsResolver::new()).tls_client_config(tls);
    if let Some(token) = token {
        builder = builder.auth_token(token);
    }
    builder.connect().await.map(|_| ())
}

#[tokio::test]
async fn a_plain_http_config_starts_a_relay_that_enforces_the_rule() {
    let server_config = droprelay::server::build(Config::parse(PLAIN_CONFIG).unwrap()).await.unwrap();
    let relay = server_config.relay.as_ref().expect("relay section");
    let limit = relay.limits.client_rx.expect("speed limit reached the server");
    assert_eq!(limit.bytes_per_second.get(), 12_000_000);
    assert_eq!(limit.max_burst_bytes.map(|v| v.get()), Some(24_000_000));

    let server = Server::spawn(server_config).await.unwrap();
    let url: RelayUrl = format!("http://{}", server.http_addr().unwrap()).parse().unwrap();
    assert!(connect(&url, Some("paired-device-token")).await.is_ok(), "the token must open the relay");
    let stranger = connect(&url, None).await;
    assert!(
        matches!(stranger, Err(ConnectError::Handshake { source: handshake::Error::ServerDeniedAuth { .. }, .. })),
        "a stranger must be refused, the registry being unreachable"
    );
}

#[tokio::test]
async fn an_unreadable_manual_certificate_stops_the_start() {
    let text = format!(
        "{PLAIN_CONFIG}\n[tls]\ncert_mode = \"Manual\"\ncert_dir = \"/nonexistent-dir-for-test\"\n"
    );
    let config = Config::parse(&text).unwrap();
    assert!(droprelay::server::build(config).await.is_err());
}
