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
