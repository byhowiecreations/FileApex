use std::num::NonZeroU32;
use std::sync::Arc;

use iroh_relay::server::{AcmeConfig, CertConfig, ClientRateLimit, QuicConfig, RelayConfig, ServerConfig, TlsConfig};
use rustls::pki_types::pem::PemObject;
use rustls::pki_types::{CertificateDer, PrivateKeyDer};

use crate::access::DropRelayAccess;
use crate::config::{CertMode, Config, TlsSettings};
use crate::registry::WorkerRegistry;

#[derive(Debug)]
pub struct BuildError(pub String);

impl std::fmt::Display for BuildError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

fn failed(what: &str, why: impl std::fmt::Display) -> BuildError {
    BuildError(format!("{what}: {why}"))
}

/// Turns the settings file into the relay server's configuration.
pub async fn build(config: Config) -> Result<ServerConfig, BuildError> {
    let registry = WorkerRegistry::new(config.registry_url.clone(), config.registry_bearer_token.clone())
        .map_err(|e| failed("registry client", e))?;
    let access = DropRelayAccess::new(config.shared_tokens.clone(), registry)
        .map_err(|e| failed("access rule", format!("{e:?}")))?;

    let mut relay = RelayConfig::new(config.http_bind_addr);
    relay.access = Arc::new(access);
    if let Some(rx) = &config.client_rx {
        let bytes_per_second = NonZeroU32::new(rx.bytes_per_second).ok_or_else(|| failed("limit", "rate is zero"))?;
        let mut limit = ClientRateLimit::new(bytes_per_second);
        limit.max_burst_bytes = rx.max_burst_bytes.and_then(NonZeroU32::new);
        relay.limits.client_rx = Some(limit);
    }

    let mut quic = None;
    if let Some(tls) = &config.tls {
        relay.tls = Some(TlsConfig::new(tls.https_bind_addr, cert_config(tls).await?));
        if config.enable_quic_addr_discovery {
            quic = Some(QuicConfig::new(tls.quic_bind_addr));
        }
    }

    let mut server = ServerConfig::default();
    server.relay = Some(relay);
    server.quic = quic;
    Ok(server)
}

fn crypto_builder() -> Result<rustls::ConfigBuilder<rustls::ServerConfig, rustls::WantsVerifier>, BuildError> {
    rustls::ServerConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
        .with_safe_default_protocol_versions()
        .map_err(|e| failed("tls protocols", e))
}

async fn cert_config(tls: &TlsSettings) -> Result<CertConfig, BuildError> {
    let builder = crypto_builder()?.with_no_client_auth();
    match tls.cert_mode {
        CertMode::LetsEncrypt => {
            let contact = tls.contact.clone().unwrap_or_default();
            let acme_config = AcmeConfig::letsencrypt(tls.prod_tls)
                .domains(tls.hostnames.clone())
                .contact(vec![format!("mailto:{contact}")])
                .cache_path(std::path::PathBuf::from(&tls.cert_dir));
            Ok(CertConfig::LetsEncrypt { acme_config, server_config_builder: builder })
        }
        CertMode::Manual => {
            let (cert_path, key_path) = (tls.manual_cert_path.clone(), tls.manual_key_path.clone());
            let (certs, key) = tokio::task::spawn_blocking(move || {
                let certs = CertificateDer::pem_file_iter(&cert_path)
                    .map_err(|e| failed("certificate file", e))?
                    .collect::<Result<Vec<_>, _>>()
                    .map_err(|e| failed("certificate file", e))?;
                let key = PrivateKeyDer::from_pem_file(&key_path).map_err(|e| failed("key file", e))?;
                Ok::<_, BuildError>((certs, key))
            })
            .await
            .map_err(|e| failed("certificate loading", e))??;
            let server_config = builder.with_single_cert(certs, key).map_err(|e| failed("certificate", e))?;
            Ok(CertConfig::Manual { server_config })
        }
    }
}
