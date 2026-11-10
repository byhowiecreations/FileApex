use std::fmt::Debug;
use std::future::Future;

use iroh_base::EndpointId;
use iroh_relay::server::{Access, AccessControl, ClientRequest};

/// Answers whether an endpoint id belongs to a live guest drop.
pub trait DropRegistry: Debug + Send + Sync + 'static {
    fn is_registered(&self, id: EndpointId) -> impl Future<Output = bool> + Send;
}

/// Why a token list was refused at start.
#[derive(Debug, PartialEq, Eq)]
pub enum TokenListError {
    Empty,
    EmptyToken,
}

#[derive(Debug)]
pub struct DropRelayAccess<R: DropRegistry> {
    tokens: Vec<String>,
    registry: R,
}

impl<R: DropRegistry> DropRelayAccess<R> {
    pub fn new(tokens: Vec<String>, registry: R) -> Result<Self, TokenListError> {
        if tokens.is_empty() {
            return Err(TokenListError::Empty);
        }
        if tokens.iter().any(|token| token.is_empty()) {
            return Err(TokenListError::EmptyToken);
        }
        Ok(Self { tokens, registry })
    }

    fn token_allowed(&self, request: &ClientRequest) -> bool {
        let Some(presented) = request.auth_token() else {
            return false;
        };
        let mut found = false;
        for expected in &self.tokens {
            found |= tokens_match(&presented, expected);
        }
        found
    }
}

/// Compares two tokens without stopping at the first difference.
pub fn tokens_match(presented: &str, expected: &str) -> bool {
    if presented.is_empty() || expected.is_empty() {
        return false;
    }
    let (a, b) = (presented.as_bytes(), expected.as_bytes());
    let mut difference = (a.len() ^ b.len()) as u64;
    for i in 0..a.len().max(b.len()) {
        let left = a.get(i).copied().unwrap_or(0);
        let right = b.get(i).copied().unwrap_or(0);
        difference |= u64::from(left ^ right);
    }
    difference == 0
}

impl<R: DropRegistry> AccessControl for DropRelayAccess<R> {
    async fn on_connect(&self, request: &ClientRequest) -> Access {
        if self.token_allowed(request) {
            return Access::Allow;
        }
        if self.registry.is_registered(request.endpoint_id()).await {
            return Access::Allow;
        }
        Access::Deny { reason: None }
    }
}
