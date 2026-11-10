use std::path::PathBuf;

use droprelay::config::Config;
use iroh_relay::server::Server;

fn config_path() -> Option<PathBuf> {
    let mut args = std::env::args().skip(1);
    while let Some(arg) = args.next() {
        if arg == "-c" || arg == "--config-path" {
            return args.next().map(PathBuf::from);
        }
    }
    None
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()))
        .init();
    if rustls::crypto::ring::default_provider().install_default().is_err() {
        eprintln!("could not install the tls provider");
        std::process::exit(1);
    }
    let Some(path) = config_path() else {
        eprintln!("usage: droprelay --config-path <file>");
        std::process::exit(2);
    };
    let text = match std::fs::read_to_string(&path) {
        Ok(text) => text,
        Err(e) => {
            eprintln!("cannot read {}: {e}", path.display());
            std::process::exit(1);
        }
    };
    let config = match Config::parse(&text) {
        Ok(config) => config,
        Err(e) => {
            eprintln!("config refused: {e:?}");
            std::process::exit(1);
        }
    };
    tracing::info!(tokens = config.shared_tokens.len(), tls = config.tls.is_some(), "starting the drop relay");
    let server_config = match droprelay::server::build(config).await {
        Ok(server_config) => server_config,
        Err(e) => {
            eprintln!("cannot build the relay: {e}");
            std::process::exit(1);
        }
    };
    let mut server = match Server::spawn(server_config).await {
        Ok(server) => server,
        Err(e) => {
            eprintln!("cannot start the relay: {e}");
            std::process::exit(1);
        }
    };
    tokio::select! {
        biased;
        _ = tokio::signal::ctrl_c() => (),
        _ = server.join() => (),
    }
    if let Err(e) = server.shutdown().await {
        eprintln!("shutdown: {e}");
    }
}
