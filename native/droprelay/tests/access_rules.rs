use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};

use droprelay::access::{DropRegistry, DropRelayAccess, TokenListError, tokens_match};
use iroh_base::{EndpointId, SecretKey};
use iroh_relay::http::ProtocolVersion;
use iroh_relay::server::{Access, AccessControl, ClientRequest};

#[derive(Debug, Clone)]
struct FakeRegistry {
    known: Vec<EndpointId>,
    asked: Arc<AtomicUsize>,
    broken: bool,
}

impl FakeRegistry {
    fn knowing(known: Vec<EndpointId>) -> Self {
        Self { known, asked: Arc::new(AtomicUsize::new(0)), broken: false }
    }
}

impl DropRegistry for FakeRegistry {
    async fn is_registered(&self, id: EndpointId) -> bool {
        self.asked.fetch_add(1, Ordering::SeqCst);
        !self.broken && self.known.contains(&id)
    }
}

fn some_id() -> EndpointId {
    SecretKey::generate().public()
}

fn request(id: EndpointId, uri: &str, bearer: Option<&str>) -> ClientRequest {
    let mut builder = http::Request::builder().uri(uri);
    if let Some(token) = bearer {
        builder = builder.header("Authorization", format!("Bearer {token}"));
    }
    let (parts, _) = builder.body(()).unwrap().into_parts();
    ClientRequest::new(id, ProtocolVersion::V2, parts)
}

fn tokens() -> Vec<String> {
    vec!["paired-device-token".to_string()]
}

#[tokio::test]
async fn a_paired_device_with_the_token_is_allowed() {
    let access = DropRelayAccess::new(tokens(), FakeRegistry::knowing(vec![])).unwrap();
    let allowed = access.on_connect(&request(some_id(), "/relay", Some("paired-device-token"))).await;
    assert_eq!(allowed, Access::Allow);
}

#[tokio::test]
async fn the_token_also_works_as_a_query_parameter() {
    let access = DropRelayAccess::new(tokens(), FakeRegistry::knowing(vec![])).unwrap();
    let allowed = access.on_connect(&request(some_id(), "/relay?token=paired-device-token", None)).await;
    assert_eq!(allowed, Access::Allow);
}

#[tokio::test]
async fn a_wrong_token_from_an_unknown_id_is_refused() {
    let access = DropRelayAccess::new(tokens(), FakeRegistry::knowing(vec![])).unwrap();
    let refused = access.on_connect(&request(some_id(), "/relay", Some("guess"))).await;
    assert!(matches!(refused, Access::Deny { .. }));
}

#[tokio::test]
async fn a_stranger_with_no_token_and_no_registration_is_refused() {
    let access = DropRelayAccess::new(tokens(), FakeRegistry::knowing(vec![])).unwrap();
    let refused = access.on_connect(&request(some_id(), "/relay", None)).await;
    assert!(matches!(refused, Access::Deny { .. }));
}

#[tokio::test]
async fn a_registered_drop_id_is_allowed_without_the_token() {
    let guest = some_id();
    let access = DropRelayAccess::new(tokens(), FakeRegistry::knowing(vec![guest])).unwrap();
    let allowed = access.on_connect(&request(guest, "/relay", None)).await;
    assert_eq!(allowed, Access::Allow);
}

#[tokio::test]
async fn a_registered_drop_id_with_a_wrong_token_is_still_allowed() {
    let guest = some_id();
    let access = DropRelayAccess::new(tokens(), FakeRegistry::knowing(vec![guest])).unwrap();
    let allowed = access.on_connect(&request(guest, "/relay", Some("wrong"))).await;
    assert_eq!(allowed, Access::Allow);
}

#[tokio::test]
async fn paired_devices_never_cost_a_registry_lookup() {
    let registry = FakeRegistry::knowing(vec![]);
    let asked = registry.asked.clone();
    let access = DropRelayAccess::new(tokens(), registry).unwrap();
    access.on_connect(&request(some_id(), "/relay", Some("paired-device-token"))).await;
    assert_eq!(asked.load(Ordering::SeqCst), 0);
}

#[tokio::test]
async fn when_the_registry_cannot_answer_a_stranger_is_refused_but_the_token_still_works() {
    let guest = some_id();
    let registry = FakeRegistry { known: vec![guest], asked: Arc::new(AtomicUsize::new(0)), broken: true };
    let access = DropRelayAccess::new(tokens(), registry).unwrap();
    assert!(matches!(access.on_connect(&request(guest, "/relay", None)).await, Access::Deny { .. }));
    assert_eq!(access.on_connect(&request(some_id(), "/relay", Some("paired-device-token"))).await, Access::Allow);
}

#[test]
fn an_empty_token_list_or_an_empty_token_is_refused_at_start() {
    assert_eq!(DropRelayAccess::new(vec![], FakeRegistry::knowing(vec![])).unwrap_err(), TokenListError::Empty);
    assert_eq!(
        DropRelayAccess::new(vec!["ok".into(), "".into()], FakeRegistry::knowing(vec![])).unwrap_err(),
        TokenListError::EmptyToken
    );
}

#[test]
fn token_comparison_needs_the_exact_same_text() {
    assert!(tokens_match("abc123", "abc123"));
    assert!(!tokens_match("abc123", "abc124"));
    assert!(!tokens_match("abc12", "abc123"));
    assert!(!tokens_match("abc1234", "abc123"));
    assert!(!tokens_match("", "abc123"));
    assert!(!tokens_match("", ""));
}
