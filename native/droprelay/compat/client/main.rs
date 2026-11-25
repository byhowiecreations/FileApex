//! An older shipped relay client against a running relay: paired devices exchange a datagram, strangers are refused.
//! usage: oldclient <relay url> <shared token>

use std::time::Duration;

use iroh_base::{RelayUrl, SecretKey};
use iroh_dns::dns::DnsResolver;
use iroh_relay::client::{Client, ClientBuilder, ConnectError};
use iroh_relay::protos::handshake;
use iroh_relay::protos::relay::{ClientToRelayMsg, Datagrams, RelayToClientMsg};
use iroh_relay::tls::{CaTlsConfig, default_provider};
use n0_future::{SinkExt, StreamExt};

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

#[tokio::main]
async fn main() {
    let mut args = std::env::args().skip(1);
    let url: RelayUrl = args.next().expect("relay url").parse().expect("relay url");
    let token = args.next().expect("token");

    let (a_key, b_key) = (SecretKey::generate(), SecretKey::generate());
    let (a_id, b_id) = (a_key.public(), b_key.public());
    let mut a = connect(&url, a_key, Some(&token)).await.expect("paired device A must connect");
    let mut b = connect(&url, b_key, Some(&token)).await.expect("paired device B must connect");

    let message = Datagrams::from("a piece of the file");
    let mut received = None;
    for _ in 0..10 {
        a.send(ClientToRelayMsg::Datagrams { dst_endpoint_id: b_id, datagrams: message.clone() })
            .await
            .expect("send");
        if let Ok(Some(Ok(item))) = tokio::time::timeout(Duration::from_millis(500), b.next()).await {
            received = Some(item);
            break;
        }
    }
    match received.expect("device B received nothing") {
        RelayToClientMsg::Datagrams { remote_endpoint_id, datagrams } => {
            assert_eq!(remote_endpoint_id, a_id);
            assert_eq!(datagrams, message);
        }
        other => panic!("unexpected message {other:?}"),
    }

    assert!(denied(&connect(&url, SecretKey::generate(), None).await), "a stranger must be refused");
    assert!(denied(&connect(&url, SecretKey::generate(), Some("guess")).await), "a wrong token must be refused");
    println!("ok");
}
