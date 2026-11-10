//! The relay over real https with a certificate from files (the Manual mode), like the VM will run it.

use droprelay::config::Config;
use iroh_base::{RelayUrl, SecretKey};
use iroh_dns::dns::DnsResolver;
use iroh_relay::client::{ClientBuilder, ConnectError};
use iroh_relay::protos::handshake;
use iroh_relay::server::Server;
use iroh_relay::tls::{CaTlsConfig, default_provider};
use rustls::pki_types::CertificateDer;

#[tokio::test]
async fn the_relay_enforces_the_rule_over_https_with_a_certificate_from_files() {
    let cert = rcgen::generate_simple_self_signed(vec!["localhost".to_string()]).unwrap();
    let dir = tempfile::tempdir().unwrap();
    std::fs::write(dir.path().join("default.crt"), cert.cert.pem()).unwrap();
    std::fs::write(dir.path().join("default.key"), cert.key_pair.serialize_pem()).unwrap();

    let text = format!(
        r#"
http_bind_addr = "127.0.0.1:0"

[tls]
https_bind_addr = "127.0.0.1:0"
cert_mode = "Manual"
cert_dir = "{}"

[access]
shared_token = ["paired-device-token"]

[drop]
registry_url = "https://registry.invalid/check"
"#,
        dir.path().display()
    );
    let server = Server::spawn(droprelay::server::build(Config::parse(&text).unwrap()).await.unwrap()).await.unwrap();
    let url: RelayUrl = format!("https://localhost:{}", server.https_addr().unwrap().port()).parse().unwrap();

    let trusted = vec![CertificateDer::from(cert.cert.der().to_vec())];
    let connect = |token: Option<&'static str>| {
        let url = url.clone();
        let trusted = trusted.clone();
        async move {
            let tls = CaTlsConfig::default().with_extra_roots(trusted).client_config(default_provider()).unwrap();
            let mut builder = ClientBuilder::new(url, SecretKey::generate(), DnsResolver::new()).tls_client_config(tls);
            if let Some(token) = token {
                builder = builder.auth_token(token);
            }
            builder.connect().await.map(|_| ())
        }
    };
    assert!(connect(Some("paired-device-token")).await.is_ok(), "the token must open the relay over https");
    assert!(
        matches!(connect(None).await, Err(ConnectError::Handshake { source: handshake::Error::ServerDeniedAuth { .. }, .. })),
        "a stranger must be refused over https"
    );
}
