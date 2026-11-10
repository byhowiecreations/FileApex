//! FileApex relay: the stock iroh-relay server with one access rule.
//!
//! A connection is allowed when its client presents one of the configured shared tokens (paired devices, as before) or
//! when its endpoint id is a registered guest drop id (asked of the registry). Anything else is refused.

pub mod access;
pub mod config;
pub mod registry;
pub mod server;
