//! The access rule inside a real relay server, with real relay clients (plain HTTP on localhost, no certificates).

use std::net::Ipv4Addr;
use std::sync::Arc;
use std::time::Duration;

use droprelay::access::{DropRegistry, DropRelayAccess};
use iroh_base::{EndpointId, RelayUrl, SecretKey};
use iroh_dns::dns::DnsResolver;
use iroh_relay::client::{Client, ClientBuilder, ConnectError};
use iroh_relay::protos::handshake;
use iroh_relay::protos::relay::{ClientToRelayMsg, Datagrams, RelayToClientMsg};
use iroh_relay::server::{RelayConfig, Server, ServerConfig};
use iroh_relay::tls::{CaTlsConfig, default_provider};
use n0_future::{SinkExt, StreamExt};

const TOKEN: &str = "paired-device-token";

#[derive(Debug, Clone)]
struct ListedIds(Vec<EndpointId>);

impl DropRegistry for ListedIds {
    async fn is_registered(&self, id: EndpointId) -> bool {
        self.0.contains(&id)
    }
}

async fn relay_listing(listed: Vec<EndpointId>) -> (Server, RelayUrl) {
    let mut relay = RelayConfig::new((Ipv4Addr::LOCALHOST, 0));
    relay.access = Arc::new(DropRelayAccess::new(vec![TOKEN.to_string()], ListedIds(listed)).unwrap());
    let mut config = ServerConfig::default();
    config.relay = Some(relay);
    let server = Server::spawn(config).await.unwrap();
    let url: RelayUrl = format!("http://{}", server.http_addr().unwrap()).parse().unwrap();
    (server, url)
}

async fn connect(url: &RelayUrl, key: SecretKey, token: Option<&str>) -> Result<Client, ConnectError> {
    let tls = CaTlsConfig::default().client_config(default_provider()).unwrap();
    let mut builder = ClientBuilder::new(url.clone(), key, DnsResolver::new()).tls_client_config(tls);
    if let Some(token) = token {
        builder = builder.auth_token(token);
    }
    builder.connect().await
}

fn denied(result: &Result<Client, ConnectError>) -> bool {
    matches!(result, Err(ConnectError::Handshake { source: handshake::Error::ServerDeniedAuth { .. }, .. }))
}

#[tokio::test]
async fn a_stranger_is_refused_by_the_real_relay() {
    let (_server, url) = relay_listing(vec![]).await;
    let result = connect(&url, SecretKey::generate(), None).await;
    assert!(denied(&result), "a client with no token and no listing must be refused");
}

#[tokio::test]
async fn a_wrong_token_is_refused_by_the_real_relay() {
    let (_server, url) = relay_listing(vec![]).await;
    let result = connect(&url, SecretKey::generate(), Some("guess")).await;
    assert!(denied(&result));
}

#[tokio::test]
async fn a_paired_device_with_the_token_connects() {
    let (_server, url) = relay_listing(vec![]).await;
    assert!(connect(&url, SecretKey::generate(), Some(TOKEN)).await.is_ok());
}

#[tokio::test]
async fn a_listed_guest_connects_with_no_token() {
    let guest = SecretKey::generate();
    let (_server, url) = relay_listing(vec![guest.public()]).await;
    assert!(connect(&url, guest, None).await.is_ok());
}

#[tokio::test]
async fn a_listed_sender_and_a_listed_guest_can_exchange_data_and_nobody_else_gets_in() {
    let sender_key = SecretKey::generate();
    let guest_key = SecretKey::generate();
    let (sender_id, guest_id) = (sender_key.public(), guest_key.public());
    let (_server, url) = relay_listing(vec![sender_id, guest_id]).await;

    let mut sender = connect(&url, sender_key, None).await.unwrap();
    let mut guest = connect(&url, guest_key, None).await.unwrap();
    assert!(denied(&connect(&url, SecretKey::generate(), None).await), "a third party must still be refused");

    let message = Datagrams::from("a piece of the file");
    let mut received = None;
    for _ in 0..10 {
        sender
            .send(ClientToRelayMsg::Datagrams { dst_endpoint_id: guest_id, datagrams: message.clone() })
            .await
            .unwrap();
        if let Ok(Some(Ok(item))) = tokio::time::timeout(Duration::from_millis(500), guest.next()).await {
            received = Some(item);
            break;
        }
    }
    match received.expect("the guest received nothing") {
        RelayToClientMsg::Datagrams { remote_endpoint_id, datagrams } => {
            assert_eq!(remote_endpoint_id, sender_id);
            assert_eq!(datagrams, message);
        }
        other => panic!("unexpected message {other:?}"),
    }
}
