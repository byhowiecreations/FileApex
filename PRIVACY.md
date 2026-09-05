# FileApex Privacy Policy

**Effective Date:** September 5, 2026
**Developer:** ByHowieCreations
**Application:** FileApex (Android, macOS, Windows)
**Contact:** byhowiecreations@gmail.com

## Summary

FileApex is a local-first, peer-to-peer file manager. By default, all file transfers, clipboard sharing, and messaging happen directly between your paired devices over your local network — nothing touches external servers. Optional, opt-in features (Google Account, Firebase Cloud Messaging, Google Drive) add cross-network convenience and use Google's infrastructure as a relay when enabled.

FileApex has no ads, no third-party trackers, no analytics SDKs, and does not sell or monetize user data.

## What We Collect and Why

**Files (Local mode, default):** Transferred directly between paired devices over LAN via local HTTP sockets. Saved to `Download/FileApex/` (Android) or `~/Downloads/FileApex/` (macOS/Windows). Never uploaded to external servers in this mode.

**Files (Cloud Relay, opt-in):** If you enable Google Drive Relay, files/attachments are stored in a `FileApex Relay` folder in your own Google Drive, and receiving devices download them directly from that folder. Access is scoped to that folder only — FileApex cannot see or modify other files in your Drive.

**Device pairing metadata:** Device name, hardware model/OS, local IP and port, battery/storage/RAM (shown in Device Details), and cryptographic pairing tokens, public keys, and optional PIN hashes. Stored locally in an on-device SQLite database, protected by OS-level sandboxing and file-based encryption. Deleted immediately when you unpair a device.

**Clipboard sharing (opt-in):** Local sharing is point-to-point and end-to-end encrypted. If you enable Cellular Clipboard Sharing, the encrypted ciphertext (not plaintext) is relayed through Firebase Cloud Messaging so it reaches your device off Wi-Fi. Google cannot read the content. Clipboard content is not continuously monitored or logged unless you actively enable the designated background clipboard mode; clipboard history is never stored externally.

**Bulletin Board (messages/notes):** Stored locally by default. If Google Account is linked, message text, attachment names/sizes, and Drive file references are sent via Firebase Cloud Messaging so your other devices get pushed notifications. Deleting a message removes it locally, or you can send a retraction broadcast to remove it from all paired devices and, if it had an attachment, from Google Drive.

**Camera:** Optional, used only to scan a pairing QR code as a backup to the default 6-digit pairing method. Frames are processed in memory; nothing is saved or uploaded.

## Third-Party Services (opt-in only)

| Service | Purpose | Data Involved |
|---|---|---|
| Firebase Cloud Messaging (Google) | Push signaling, wake notifications | Device push tokens, connection signaling, Bulletin Board text/attachment metadata, encrypted clipboard ciphertext |
| Google Drive API (Google) | Optional file relay & attachment storage | Files stored in your own `FileApex Relay` Drive folder |
| Google Sign-In / OAuth (Google) | Account authentication | OAuth tokens tied to your paired devices |
| GitHub Releases API | Update checks | Public release tag lookup only — no identifiers sent |

If you never sign in with Google or enable cloud relay, none of these are contacted, and FileApex runs entirely offline on your local network — with the sole exception of the GitHub update checker, which is contacted if you manually trigger a version check.

## Permissions

| Permission | Purpose |
|---|---|
| Storage / All Files Access | Browse, send, and receive files |
| Local Network / Wi-Fi | Discover and connect to paired devices |
| Notifications | Transfer progress, incoming files, Bulletin Board alerts |
| Camera (Android, optional) | Backup QR pairing method |
| Foreground Service & Boot (Android, optional) | Keeps local server reachable in the background |
| Battery & Power Status | Device Details screen, low-battery alerts |
| Nearby Wi-Fi / Phone State (Android, optional) | Network name / carrier type in Device Details |

**No location tracking:** FileApex does not request or collect GPS or precise location data.

## Security

- Pairing requires mutual confirmation via 6-digit code or QR scan.
- Optional PIN lock can be set to gate pairing and file browsing.
- Clipboard content is end-to-end encrypted on-device before any transmission, local or cellular.
- Local data is protected by OS-level sandboxing and encryption (Android FBE, macOS Data Protection).

## Data Deletion

Unpairing a device removes its keys and records immediately. Clearing app data or uninstalling FileApex wipes all local databases, settings, and credentials.

## Children's Privacy

FileApex does not knowingly collect personal information from children under 13 (or 16 where applicable). The app operates locally and peer-to-peer without requiring an account.

## Changes to This Policy

This policy may be updated to reflect new features or regulatory requirements. Updates will be posted here.

## Contact

byhowiecreations@gmail.com
