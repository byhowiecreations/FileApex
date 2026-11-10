//! Browser side of a FileApex guest drop. One call dials the sender through its relay, asks for the file and hands every
//! chunk to JavaScript. There is no resume and no storage here: the page decides where the bytes go.

use iroh::endpoint::presets;
use iroh::{Endpoint, EndpointAddr, EndpointId, RelayMode, RelayUrl};
use js_sys::{Function, Promise, Uint8Array};
use wasm_bindgen::prelude::*;
use std::cell::Cell;
use std::rc::Rc;
use wasm_bindgen_futures::{spawn_local, JsFuture};

mod guestkey;
mod pump;

use crate::pump::{pump, Sink, Source};

const ALPN: &[u8] = b"fileapex/drop/1";
const MAX_FRAME: usize = 4096;
/// How long the sender has to answer (connect and first reply) before the attempt is given up.
const ANSWER_LIMIT_MS: i32 = 30_000;

#[wasm_bindgen(start)]
fn start() {
    console_error_panic_hook::set_once();
}

async fn sleep_ms(ms: i32) {
    let promise = Promise::new(&mut |resolve, _| {
        if let Ok(set_timeout) = js_sys::Reflect::get(&js_sys::global(), &JsValue::from_str("setTimeout")) {
            let set_timeout: Function = set_timeout.unchecked_into();
            let _ = set_timeout.call2(&JsValue::NULL, &resolve, &JsValue::from_f64(ms as f64));
        }
    });
    let _ = JsFuture::from(promise).await;
}

fn fail(msg: impl std::fmt::Display) -> JsError {
    JsError::new(&msg.to_string())
}

/// Calls a JS callback and waits for it when it returns a promise, so a slow disk slows the transfer instead of filling memory.
async fn call(f: &Function, args: &[JsValue]) -> Result<(), String> {
    let arr = js_sys::Array::new();
    for a in args {
        arr.push(a);
    }
    let out = f.apply(&JsValue::NULL, &arr).map_err(|e| format!("{e:?}"))?;
    if out.is_instance_of::<Promise>() {
        JsFuture::from(Promise::from(out)).await.map_err(|e| format!("{e:?}"))?;
    }
    Ok(())
}

/// The connection's receive side as the download loop's source.
struct RecvSource<'a>(&'a mut iroh::endpoint::RecvStream);

impl Source for RecvSource<'_> {
    async fn read(&mut self, buf: &mut [u8]) -> Result<usize, String> {
        match self.0.read(buf).await {
            Ok(Some(n)) => Ok(n),
            Ok(None) => Ok(0),
            Err(e) => Err(format!("connection lost: read {e}")),
        }
    }
}

/// The page's callback as the download loop's sink; the bytes are copied into a JS array for each call.
struct JsSink<'a>(&'a Function);

impl Sink for JsSink<'_> {
    async fn write(&mut self, data: &[u8]) -> Result<(), String> {
        call(self.0, &[Uint8Array::from(data).into()]).await
    }
}

/// Dials the drop endpoint and downloads its file.
///
/// `guest_keys` is 0 for a link made before guest keys existed (the endpoint gets a random key); otherwise the page connects with
/// one of the keys derived from the secret, chosen at random, which the sender has listed with the drop relay.
///
/// `on_head(size, name)` runs once when the sender accepts. `on_data(bytes)` runs for every chunk, in order.
/// Errors carry the sender's reason in plain words: `denied`, `expired`, `used`, `busy`, `gone`, or a connection problem.
#[wasm_bindgen]
pub async fn pull(
    endpoint_id: String,
    relay_url: String,
    drop_id: String,
    secret: String,
    guest_keys: u32,
    on_head: Function,
    on_data: Function,
) -> Result<(), JsError> {
    let id: EndpointId = endpoint_id.parse().map_err(|_| fail("bad link"))?;
    let relay: RelayUrl = relay_url.parse().map_err(|_| fail("bad link"))?;
    // Only the relay named in the link: no address lookup service and no speed checks against other relays, so the
    // visitor's address reaches the relay of this link and nothing else.
    let mut builder = Endpoint::builder(presets::Minimal).relay_mode(RelayMode::custom([relay.clone()]));
    if guest_keys > 0 {
        let mut pick = [0u8; 1];
        getrandom::fill(&mut pick).map_err(|e| fail(format!("start: {e}")))?;
        let index = pick[0] % guest_keys.min(u32::from(u8::MAX)) as u8;
        builder = builder.secret_key(iroh::SecretKey::from_bytes(&guestkey::guest_seed(&secret, index)));
    }
    let endpoint = builder.bind().await.map_err(|e| fail(format!("start: {e}")))?;
    let addr = EndpointAddr::new(id).with_relay_url(relay);

    // A sender that is gone never answers, and a connect to it can run for minutes. Closing the endpoint ends it at once
    // and makes sure an abandoned attempt can never start a download later.
    let answered = Rc::new(Cell::new(false));
    {
        let (watched, answered) = (endpoint.clone(), answered.clone());
        spawn_local(async move {
            sleep_ms(ANSWER_LIMIT_MS).await;
            if !answered.get() {
                watched.close().await;
            }
        });
    }
    let connection = endpoint.connect(addr, ALPN).await.map_err(|_| fail("sender offline"))?;
    let (mut send, mut recv) = connection.open_bi().await.map_err(|_| fail("sender offline"))?;

    let request = format!("v1\n{drop_id}\n{secret}");
    send.write_all(&(request.len() as u32).to_be_bytes()).await.map_err(|_| fail("connection lost"))?;
    send.write_all(request.as_bytes()).await.map_err(|_| fail("connection lost"))?;

    let mut len = [0u8; 4];
    recv.read_exact(&mut len).await.map_err(|_| fail("connection lost"))?;
    let len = u32::from_be_bytes(len) as usize;
    if len > MAX_FRAME {
        return Err(fail("bad reply"));
    }
    let mut head = vec![0u8; len];
    recv.read_exact(&mut head).await.map_err(|_| fail("connection lost"))?;
    answered.set(true);
    let head = String::from_utf8(head).map_err(|_| fail("bad reply"))?;
    let mut parts = head.splitn(3, '\n');
    match (parts.next(), parts.next(), parts.next()) {
        (Some("ok"), Some(size), Some(name)) => {
            let size: u64 = size.parse().map_err(|_| fail("bad reply"))?;
            call(&on_head, &[JsValue::from_f64(size as f64), JsValue::from_str(name)]).await.map_err(fail)?;
            pump(&mut RecvSource(&mut recv), &mut JsSink(&on_data), size).await.map_err(fail)?;
            send.write_all(b"k").await.map_err(|e| fail(format!("connection lost: receipt {e}")))?;
            send.finish().map_err(|e| fail(format!("connection lost: finish {e}")))?;
            let _ = send.stopped().await;
            connection.close(0u8.into(), b"done");
            endpoint.close().await;
            Ok(())
        }
        (Some("no"), Some(code), None) => Err(fail(code)),
        _ => Err(fail("bad reply")),
    }
}
