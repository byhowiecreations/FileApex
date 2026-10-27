# fcmrelay

Holds the Firebase service-account key so FileApex clients carry none (audit S-5).

`POST /v1/wake`, header `Authorization: Bearer <Firebase Auth ID token>`, body
`{"token": "<target FCM token>", "data": {"type": "presence_wake", ...}}` (string values only).

The relay checks the ID token (RS256 against Google's published certificates, issuer, audience, expiry),
rate limits per Firebase user and globally, accepts only the wake types listed in `server.go`
(`FcmWakeProtocol.kt` on the client), caps the payload at 4000 bytes, and sends the message through
FCM HTTP v1 with the same shape the clients send today (`data` plus Android priority HIGH).

Answers: 200 sent, 400 refused body, 401 bad token, 410 target token no longer registered, 429 slow down
(`Retry-After`), 502 FCM failed. `GET /healthz` answers `ok`.

| Setting | Default |
| :--- | :--- |
| `FCM_SERVICE_ACCOUNT_FILE` | required, path to the firebase-adminsdk JSON |
| `LISTEN_ADDR` | `127.0.0.1:8787` (the Docker image sets `0.0.0.0:8787`) |
| `RATE_PER_USER_PER_MIN` / `RATE_PER_USER_BURST` | 60 / 20 |
| `RATE_GLOBAL_PER_MIN` / `RATE_GLOBAL_BURST` | 3000 / 200 |

Run it behind a TLS reverse proxy (Caddy); see `docs/planning/relays-plan.md`. Tests: `go test ./...`
(standard library only).

## Device endpoints (per-device Firebase identity)

Plan: `docs/planning/per-device-firebase-identity-plan.md`. All but the first need `Authorization: Bearer <Firebase ID token>`.

| Endpoint | Caller | Does |
| :--- | :--- | :--- |
| `GET /v1/device/key` | anyone | the relay's X25519 key (base64), new on each start |
| `POST /v1/device/enrol` | any session of the account | `{deviceId, ts, nonce, tag}`: checks the device's cloud record and its key proof, adds it to the allow-list, returns a custom token for uid `owner~deviceId` with claims `owner` and `deviceId` |
| `POST /v1/device/sync` | enrolled device | `{admit:[{deviceId,publicKeyHash,version}], revoke:[{deviceId,version}]}`: a newer version wins; denies or re-admits devices and switches their Firebase users; writes nothing when nothing changes |

The allow-list lives in `users/{owner}/security/state` (JSON string, written only here). The service account needs
the Firestore and Identity Toolkit admin roles. Answers: 403 refused (bad proof, removed, not admitted, caller not
enrolled), 404 no cloud record, 409 state changed (retry), 502 Google call failed.
