# FileApex Privacy Policy

**Effective Date:** Nov 6, 2026
**Developer:** ByHowieCreations
**Application:** FileApex (Android, macOS, Windows, Linux)
**Contact:** byhowiecreations@gmail.com

## Summary

FileApex is a local-first, peer-to-peer file manager. By default, all file transfers, clipboard sharing, and messaging happen directly between your paired devices over your local network, nothing touches external servers. Optional, opt-in features (Google Account, Firebase Cloud Messaging, Google Drive) add cross-network convenience and use Google's infrastructure as a relay when enabled.

FileApex has no ads, no third-party trackers, no analytics SDKs, and does not sell or monetize user data.

## What We Collect and Why

**Files (Local mode, default):** Transferred directly between paired devices over LAN via mutual TLS (mTLS) with pinned device certificates. Saved to `Download/FileApex/` (Android) or `~/Downloads/FileApex/` (macOS, Windows, Linux). Never uploaded to external servers in this mode.

**Files (Cloud Relay, opt-in):** If you enable Google Drive Relay, files/attachments are stored in a `FileApex Relay` folder in your own Google Drive, and receiving devices download them directly from that folder. Access is scoped to that folder only, FileApex cannot see or modify other files in your Drive.

**Device pairing metadata:** Device name, hardware model/OS, local IP and port, battery/storage/RAM (shown in Device Details), and cryptographic pairing tokens, public keys, and optional PIN hashes. Stored locally in an on-device SQLite database, protected by OS-level sandboxing and file-based encryption. Deleted immediately when you unpair a device.

**Device list in your Firebase account.** If you link a Google Account, FileApex keeps a list of your devices in your own Firebase project so they can find each other: device name, model, network address and status such as battery. Names and addresses are encrypted between your devices in current versions.

**Bulletin Board (messages/notes):** Stored locally by default. If Google Account is linked, message text, attachment names/sizes, and Drive file references are sent via Firebase Cloud Messaging so your other devices get pushed notifications. Deleting a message removes it locally, or you can send a retraction broadcast to remove it from all paired devices and, if it had an attachment, from Google Drive.

**Broadcast Notifications (opt-in, Android):** If you turn this on, FileApex reads notifications from the apps you choose and sends them end-to-end encrypted to one paired device you select. Nothing is shared until you enable it, grant notification access in Android settings, and switch on individual apps. Notifications containing verification codes are not sent unless you allow them. Ongoing and silent notifications are never sent. Notification content is held in memory on the receiving device only, is never written to disk or logs, and is gone when the app closes.

**Clipboard sharing (opt-in):** Local sharing is point-to-point and end-to-end encrypted. If you enable Cellular Clipboard Sharing, the encrypted ciphertext (not plaintext) is relayed through Firebase Cloud Messaging so it reaches your device off Wi-Fi. Google cannot read the content. Clipboard content is not continuously monitored or logged unless you actively enable the designated background clipboard mode; clipboard history is never stored externally.

**Clipboard Accessibility Service (Android, non-Play builds only, opt-in):** Android does not let apps read the clipboard while they are in the background. The optional Accessibility Service works around this by noticing copy actions (taps on Copy or Cut, text selection and focus changes) in other apps so FileApex can read what you copied. To do that it can see the text you select or edit in the app you are copying from, and that app's name. This text is kept in memory only, is used only to work out what you copied, and is shared with your paired devices (end-to-end encrypted) only when Clipboard Sharing is on and a copy is detected. It is not stored, uploaded, logged, or used for anything else, and the service does not perform actions on your behalf. It runs only after you turn it on in Android Accessibility settings and in FileApex, and you can turn it off at any time. **This feature is not included in Google Play builds.**

**Shizuku (Android, non-Play builds only, opt-in):** If you already use the separate Shizuku app and authorize FileApex inside it, FileApex can read the clipboard while it is in the background through Shizuku. It is off by default, used only to read the clipboard for Clipboard Sharing, and handled exactly like other clipboard content. FileApex does not install or start Shizuku. **This feature is not included in Google Play builds.**

**Critical Notifications (all builds):** After telling you about it once, FileApex can read a small public file from the FileApex GitHub page that lists notices about major bugs or updates that break features. The file is signed, and FileApex shows only the notices that apply to your version, platform and the features you use. The request carries no device, account, version or other identifier; GitHub sees your IP address, as with any download. The setting is on by default, is explained the first time you use it, and can be turned off at any time in Settings, after which FileApex never makes this request. Notices appear only inside your Bulletin Board on your own device and are never sent to other devices.

**App updates (Android and desktop, non-Play builds):** When update checks are on, FileApex asks the GitHub Releases API for the latest public version, downloads the update file from GitHub, and on Android hands it to the system installer, which asks you to confirm. FileApex checks that the file is a valid Android package, and Android only installs an update that carries the same package name and signing key as the installed app. No device or personal identifiers are sent. An update shared by one of your paired devices through the Bulletin Board is offered the same way. Google Play builds update only through Google Play and do not include this.

**Notifications you choose to share.** If you turn on Broadcast Notifications, FileApex on your phone reads the notifications of the apps you selected and sends the sender or chat name, the title, the recent message text and whether the notification can be replied to, to the one paired device you chose. Everything travels end to end encrypted between your two devices and never through a FileApex server. The receiving device keeps this only in memory while the app runs; it is not written to disk, not logged and not backed up, and it is gone when the app exits. Nothing is read from apps you did not select, and verification codes are skipped unless you turn them on.

**Clearing and replying.** When you open a notification on the receiving device, FileApex tells your phone which notification you saw so it can dismiss it. If you reply, the text you type is sent encrypted to your phone, which passes it to the app that sent the notification, exactly as if you had typed it in the notification's reply box. The reply text is not stored by FileApex.

**Windows popups.** If you turn on "Notifications from connected phone" on Windows, the sender and message text are shown in a Windows notification on that computer. Windows may keep notification text in its own notification history, which FileApex does not control.

**Permission and app list.** Reading notifications uses Android's Notification Access setting, which you grant yourself and can revoke at any time. To show the list of apps you can pick from, FileApex asks Android which installed apps have a launcher icon, using a narrow package-visibility declaration. It does not request the broader permission to see every installed app. The list stays on your phone and is used only to show you the choices; only the apps you turn on are ever read.

**Camera:** Optional, used only to scan a pairing QR code as a backup to the default 6-digit pairing method. Frames are processed in memory; nothing is saved or uploaded.

## Third-Party Services (opt-in only)

| Service | Purpose | Data Involved |
|---|---|---|
| Firebase Cloud Messaging (Google) | Push signaling, wake notifications | Device push tokens, connection signaling, Bulletin Board text/attachment metadata, encrypted clipboard ciphertext |
| Google Drive API (Google) | Optional file relay & attachment storage | Files stored in your own `FileApex Relay` Drive folder |
| Google Sign-In / OAuth (Google) | Account authentication | OAuth tokens tied to your paired devices |
| GitHub Releases API | Update checks | Public release tag lookup only, no identifiers sent |
| GitHub (raw file) | Critical Notifications | One public file, `notice/notice.json`, fetched without any identifier; GitHub sees your IP address as for any download |
| FileApex message relay (Cloudflare) | Adds the API key and passes push messages to Google. Used only when a Google Account is linked | Device push tokens and the message. Nothing is stored |

If you never sign in with Google or enable cloud relay, none of these are contacted, and FileApex runs entirely offline on your local network, with the exception of the GitHub update checker, which is contacted if you manually trigger a version check, and the Critical Notifications check described below, which you can turn off in Settings.

**Cloud messages.** If you link a Google Account, FileApex sends its push messages through a small relay on Cloudflare. The relay exists only to add the developer's Google API key, so the key is not stored in the app. It passes each message to Google and keeps nothing. If you never link a Google Account, it is never contacted.

**Share as a link.** You can turn one of your files into a link that works once, so a person without FileApex can download it in a browser. The file is never uploaded: it goes from your device to their browser while FileApex stays open, through an Iroh relay that cannot read it because the transfer is encrypted to a key made only for that link. The web page at getfileapex.com/drop/ is a static page; everything it needs (a one-time secret, the file name, the size) sits after the `#` in the link, which browsers never send to the website. The website and the relay can see that a page was opened and, roughly, when and how much data moved. They cannot see the file name or the contents. When a link is made the app also tells the FileApex website (hosted on Cloudflare) which temporary endpoint ids may use the FileApex relay for that link (the device's temporary id and two ids derived from the link's secret) and when they expire. This needs no account or sign-in. The website does not receive the secret, the file name, the size or the contents; like any web request it sees the IP address of whoever contacts it. If the website cannot be reached, the link uses n0's public relay instead. The page connects only to getfileapex.com and to the one relay named in the link; it does not use any address lookup service or test other relays. The software on the page and its licences are listed at getfileapex.com/drop/licenses.txt. The link carries no IP address of yours and no file path. Anyone who holds the link can download the file until it has been downloaded once, you stop sharing, or its time limit (15 minutes to 24 hours, which you choose) runs out. The page has no analytics, cookies or account. FileApex asks you to accept the use of a relay server before the first link is made, and you can withdraw that at any time under Settings, Security & Access Control, Share as link; withdrawing it hides the action and stops any open link.

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
| Notification access (Android, optional) | Read notifications from apps you select so they can be shown on a paired device |
| Install unknown apps (Android, non-Play builds) | Hands a downloaded FileApex update to the Android installer; you confirm each install |
| Accessibility Service (Android, non-Play builds, optional) | Detects copy actions so Clipboard Sharing works in the background |
| Shizuku access (Android, non-Play builds, optional) | Reads the clipboard in the background if you authorize FileApex in Shizuku |

**No location tracking:** FileApex does not request or collect GPS or precise location data.

## Security

- Pairing requires mutual confirmation via 6-digit code or QR scan.
- Paired devices authenticate and encrypt direct connections using mutual TLS (TLS 1.3 / 1.2) with pinned EC certificates on LAN, TLS 1.3 over QUIC on Iroh, and WireGuard on Tailscale (`tsnet`).
- Optional PIN lock can be set to gate pairing and file browsing.
- Clipboard content, mirrored phone notifications, Friends transfers, and one-time browser links (`getfileapex.com/drop`) are end-to-end encrypted on-device (AES-256-GCM).
- Local data is protected by OS-level sandboxing and encryption (Android FBE, macOS Data Protection).

## Data Deletion

Unpairing a device removes its keys and records immediately. Clearing app data or uninstalling FileApex wipes all local databases, settings, and credentials.

## Children's Privacy

FileApex does not knowingly collect personal information from children under 13 (or 16 where applicable). The app operates locally and peer-to-peer without requiring an account.

## Changes to This Policy

This policy may be updated to reflect new features or regulatory requirements. Updates will be posted here.

## Contact

byhowiecreations@gmail.com
