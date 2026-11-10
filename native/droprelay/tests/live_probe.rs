//! Checks a RUNNING relay from outside. Skipped unless DROPRELAY_URL is set, so the normal test run never touches the network.
//!
//!   DROPRELAY_URL=https://relay.example.com DROPRELAY_TOKEN=<shared token> [DROPRELAY_REGISTER_URL=https://site/api/drop/register]
//!
//! The token is read from the environment and never printed.

use iroh_base::{RelayUrl, SecretKey};
use iroh_dns::dns::DnsResolver;
use iroh_relay::client::{ClientBuilder, ConnectError};
use iroh_relay::protos::handshake;
use iroh_relay::tls::{CaTlsConfig, default_provider};

fn client(url: &RelayUrl, key: SecretKey, token: Option<&str>) -> ClientBuilder {
    let tls = CaTlsConfig::default().client_config(default_provider()).unwrap();
    let mut builder = ClientBuilder::new(url.clone(), key, DnsResolver::new()).tls_client_config(tls);
    if let Some(token) = token {
        builder = builder.auth_token(token);
    }
    builder
}

fn denied<T>(result: &Result<T, ConnectError>) -> bool {
    matches!(result, Err(ConnectError::Handshake { source: handshake::Error::ServerDeniedAuth { .. }, .. }))
}

#[tokio::test]
async fn the_live_relay_lets_the_token_in_and_refuses_strangers() {
    let Ok(url) = std::env::var("DROPRELAY_URL") else {
        eprintln!("DROPRELAY_URL not set: skipped");
        return;
    };
    let url: RelayUrl = url.parse().expect("DROPRELAY_URL is not a relay url");
    let token = std::env::var("DROPRELAY_TOKEN").expect("DROPRELAY_TOKEN");

    let paired = client(&url, SecretKey::generate(), Some(&token)).connect().await;
    assert!(paired.is_ok(), "the shared token must open the live relay: {:?}", paired.err());

    let stranger = client(&url, SecretKey::generate(), None).connect().await;
    assert!(denied(&stranger), "a client with no token and no registration must be refused");

    let wrong = client(&url, SecretKey::generate(), Some("not-the-token")).connect().await;
    assert!(denied(&wrong), "a wrong token must be refused");
}

#[tokio::test]
async fn a_guest_registered_with_the_worker_gets_in_without_a_token() {
    let (Ok(url), Ok(register)) = (std::env::var("DROPRELAY_URL"), std::env::var("DROPRELAY_REGISTER_URL")) else {
        eprintln!("DROPRELAY_URL or DROPRELAY_REGISTER_URL not set: skipped");
        return;
    };
    let url: RelayUrl = url.parse().expect("DROPRELAY_URL is not a relay url");
    let guest = SecretKey::generate();
    let id = guest.public().to_string();
    let expires = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_millis() + 600_000;
    let response = reqwest::Client::new()
        .post(&register)
        .json(&serde_json::json!({ "ids": [id], "expiresAtMs": expires as u64 }))
        .send()
        .await
        .expect("register request");
    assert!(response.status().is_success(), "the Worker refused the registration: {}", response.status());

    let result = client(&url, guest, None).connect().await;
    assert!(result.is_ok(), "a registered id must connect with no token: {:?}", result.err());
}
