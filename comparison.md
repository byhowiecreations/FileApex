# FileApex compared with Blip, LocalSend, Pushbullet, KDE Connect, LinkMyMac, DashBeam, Bridgey, AirDroid, Join, and Rabbit Link

Researched **2026-11-06** from each project's own site, repository, store listings, or help pages, with FileApex at **v0.20.5**. These products change quickly, so check the vendor's page before relying on a price or a limit.

**Legend:** ✅ yes · ❌ no · — not documented by the vendor, so not marked either way. A note after an icon gives the limit, such as "browser only".

---

## The quick view

| | **FileApex** | **Blip** | **LocalSend** | **Pushbullet** | **KDE Connect** | **LinkMyMac** | **DashBeam** | **Bridgey** | **AirDroid** | **Join** | **Rabbit Link** |
| --- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **What it is** | File manager and device hub for your own devices | Fast file sender to people and to your own devices | Nearby file and text sender | Cloud hub for pushes, notifications, and SMS | Phone-to-computer plugin suite | Android companion for Mac and iPad | Peer-to-peer file sender, including over the internet | Local Android–Mac companion | Android remote manager, with file transfer | Google-account hub for notifications, SMS, clipboard, and files, built around Tasker | Paid Android companion for the Mac |
| **Price** | Free | Free for personal use; Business $25/user/month yearly or $30 monthly | Free | Free with caps; Pro $4.99/month or $39.99/year | Free | $14.99 once | Free | Free | Free on the local network; Premium $3.99/month or $29.99/year | $4.99 once after a 30-day trial | $49 once per Mac, 5-day trial |
| **Open source** | ✅ MIT | ❌ | ✅ Apache-2.0 | ❌ | ✅ GPL | ❌ | ✅ AGPL-3.0 | ✅ MIT | ❌ | ❌ only the desktop source is public, with no license | ❌ |
| **Android** | ✅ 8+ | ✅ 9+ | ✅ 7+ | ✅ | ✅ 6+ | ✅ | ✅ | ✅ 8+ | ✅ 8+ | ✅ 7+ | ✅ 12+ |
| **iPhone / iPad** | ❌ | ✅ iOS 15+ | ✅ iOS 13+ | ❌ app pulled | ✅ limited | ✅ iPad; iPhone via LinkMyDrop | ❌ "coming soon" | ❌ | ✅ transfer only | ❌ | ❌ |
| **Mac** | ✅ | ✅ 12.1+ | ✅ 11+ | ❌ browser only | ✅ Apple Silicon | ✅ 15.5+ | ✅ | ✅ 13+ | ✅ 10.11+ | ✅ | ✅ 14.6+ |
| **Windows** | ✅ | ✅ 10+ | ✅ 10+ | ✅ | ✅ | ❌ | ✅ | ❌ | ✅ 7+ | ✅ | ❌ |
| **Linux** | ✅ | ✅ | ✅ | ❌ browser only | ✅ | ❌ | ✅ | ❌ after v1.0 | ❌ web client only | ✅ | ❌ |
| **Works without an account** | ✅ Google is opt-in | ❌ email sign-in | ✅ | ❌ Google or Facebook sign-in | ✅ | ✅ | ✅ | ✅ | ❌ only for Nearby | ❌ Google sign-in | ✅ license key only |
| **Reaches devices off the local network** | ✅ opt-in: Tailscale, Iroh, or Google | ✅ | ❌ | ✅ through Pushbullet | ❌ needs your own VPN | ❌ Wi-Fi or USB | ✅ | ❌ | ✅ remote-data quota | ✅ Google push and Drive | ❌ Wi-Fi or Bluetooth |
| **Send files** | ✅ | ✅ | ✅ | ✅ size-capped | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ Drive, or direct on the LAN | ✅ |
| **Phone notifications on the computer** | ✅ | ❌ | ❌ | ✅ | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ |
| **Clipboard sync** | ✅ opt-in | ❌ | ❌ | ✅ Pro | ✅ text | ✅ text, images, URLs | ❌ | ✅ text and HTML | ✅ | ✅ | ✅ text |
| **Browse the other device's files** | ✅ | ❌ | ❌ | ❌ | ✅ SFTP | ✅ | ❌ | ❌ planned | ✅ | ✅ | ✅ photos and videos only |
| **Someone can receive with no app install** | ✅ one-time browser link | ❌ | ✅ browser link, same network | ❌ | ❌ | ❌ | ✅ web app | ❌ | ❌ web client needs an account | ❌ | ❌ |
| **Resume a broken transfer** | ✅ | ✅ | ❌ | ❌ | — | — | ✅ | ❌ | — | — | — |

### Pick by need

- Your own phone and computers, including Windows and Linux, with files, clipboard, notifications, and no account: **FileApex** or **KDE Connect**.
- A file of any size to someone anywhere, with an account: **Blip** (free for personal use).
- A file to someone in the room, including an iPhone or a browser: **LocalSend**.
- A file of any size to someone anywhere, with no account, including a browser: **DashBeam**.
- Notifications, SMS, and pushes on any computer through a cloud account: **Pushbullet**.
- Android notifications, real SMS and MMS in any browser, clipboard, and a URL API you can script from Tasker, IFTTT, Node-RED, or MQTT, for a one-time $4.99: **Join**.
- Android and a Mac, with screen mirroring, the phone's camera, USB, and calls: **LinkMyMac**.
- Android and a Mac, with native-looking notifications, calls, photos, and Bluetooth, for a one-time price: **Rabbit Link**.
- Android and a Mac only, on the local network, with calls, in a small open-source app: **Bridgey**.
- Drive an Android phone from a computer or a browser, including off the home network: **AirDroid**.

---

<details>
<summary><strong>1. Identity and licensing</strong></summary>

<br>

| | FileApex | Blip | LocalSend | Pushbullet | KDE Connect | LinkMyMac | DashBeam | Bridgey | AirDroid | Join | Rabbit Link |
| --- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **Source public** | ✅ | ❌ | ✅ | ❌ | ✅ | ❌ | ✅ | ✅ | ❌ | ✅ desktop app only | ❌ |
| **License** | MIT | proprietary | Apache-2.0 | proprietary | GPL | proprietary | AGPL-3.0 | MIT | proprietary | proprietary; desktop repo has no license file | proprietary |
| **Price** | Free | Free personal; Business $25/user/month ($300/year) or $30 monthly; 15-day trial | Free | Free tier; Pro $4.99/month or $39.99/year | Free | $14.99 once | Free | Free | Free tier; Premium $3.99/month or $29.99/year | $4.99 once after a 30-day trial; Windows Store app sold separately | $49 lifetime per Mac; 5-day trial |
| **Current release** | v0.20.5 (6 Nov 2026) | 1.2.8 (30 Oct 2026) | v1.18.2 (21 Aug 2026) | Android app updated 27 Aug 2025 | 26.08.3 desktop (5 Nov 2026); Android 1.37.0 | 1.62 (Oct 2026) | v0.7.2 (14 Oct 2026) | v0.5.0 (7 Sep 2026); v0.6.0-rc.2 prerelease | no version on the product pages | Android 3.0.15 (3 Dec 2025); Chrome 1.9.4 (25 Sep 2026); Desktop 1.1.3 | 2.2.0 (31 Oct 2026) |
| **First public** | Jul 2026 | Nov 2023 (iOS Jan 2024) | Dec 2022 | 2013 | 2013 | about Dec 2025 | about Oct 2025 | Aug 2026 | long-running commercial product | Nov 2015 beta; Mar 2016 on Play | Mar 2025 Mac beta |
| **Where to get it** | GitHub, Google Play | blip.net, app stores | stores, package managers, GitHub, web.localsend.org | pushbullet.com, Google Play, Firefox add-on | Play, F-Droid, app stores, distro packages | App Store, Google Play | GitHub, Google Play, dashbeam.net, Scoop | GitHub, bridgey.ai | airdroid.com, app stores | Google Play, Chrome Web Store, Microsoft Store, GitHub (desktop) | rabbit-link.app, Gumroad, Google Play |

**Notes**

- **FileApex** has no v0.19.7 tag; that figure in the last revision came from the repo's version file between v0.19.6 and v0.19.8.
- **Pushbullet**'s pricing page renders only with JavaScript, so the Pro price and free caps come from an indexed copy of pushbullet.com/pro, not a live read. Its iPhone app was pulled in 2020, and the Chrome extension listing no longer shows a product.
- **Bridgey** v0.6 has not shipped as a stable release, and there have been no commits since 8 Sep 2026. bridgey.ai still says "v0.5 alpha".
- **Join**: the Play listing says "Contains ads · In-app purchases". The Windows Store app's price couldn't be confirmed. A 3.0.16 beta (12 Oct 2026) appears only on APKMirror.
- **Rabbit Link** calls itself "Rabbit" and is made by a solo developer in India. Don't confuse it with the unrelated iOS app "Rabbit Link" (a safety and compliance product). The Mac app sends analytics by default, and you can only switch them off after buying.

</details>

<details>
<summary><strong>2. Platforms</strong></summary>

<br>

| | FileApex | Blip | LocalSend | Pushbullet | KDE Connect | LinkMyMac | DashBeam | Bridgey | AirDroid | Join | Rabbit Link |
| --- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **Android** | ✅ 8.0+ | ✅ 9+ | ✅ 7.0+ | ✅ | ✅ 6.0+ | ✅ phone and tablet | ✅ | ✅ 8.0+ | ✅ 8.0+ | ✅ 7.0+ | ✅ 12+ Wi-Fi, 15+ Bluetooth |
| **iOS / iPadOS** | ❌ | ✅ | ✅ | ❌ | ✅ see note | ✅ iPad (iPadOS 17+); iPhone via LinkMyDrop | ❌ | ❌ | ✅ iOS 13+ for transfer | ❌ | ❌ |
| **macOS** | ✅ Apple Silicon and Intel | ✅ | ✅ | ❌ browser only | ✅ Apple Silicon; Intel nightly only | ✅ | ✅ universal | ✅ 13+ | ✅ | ✅ desktop app, Chrome, web | ✅ 14.6+ |
| **Windows** | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ incl. ARM64 | ❌ | ✅ | ✅ desktop app, Store app, Chrome, web | ❌ |
| **Linux** | ✅ .deb, .rpm, AppImage | ✅ x86-64 and ARM64 | ✅ | ❌ browser only | ✅ main target | ❌ | ✅ .deb, .rpm, AppImage | ❌ after v1.0 | ❌ web client only | ✅ .deb, AppImage | ❌ |
| **Web** | ✅ receive page for one-time links only | ❌ | ✅ web.localsend.org | ✅ | ❌ | ❌ | ✅ app.dashbeam.net | ❌ | ✅ web.airdroid.com | ✅ Join web app | ❌ |
| **Command line** | ✅ plus a terminal dashboard | ❌ | ✅ official `localsend-cli` | ❌ HTTP API only | ✅ | ❌ | ✅ `sendme` | ❌ | ❌ | ❌ HTTP API only | ❌ |
| **Headless / Docker node** | ✅ | ❌ | ❌ | ❌ | ✅ runs without a display; no Docker image | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **Browser extension** | ✅ Firefox | ❌ | ❌ | ✅ Firefox only | ✅ Chrome and Firefox via Plasma | ❌ | ❌ | ❌ | ❌ | ✅ Chrome | ❌ |

**Notes**

- **KDE Connect on iOS** has no notification sync, no SMS, and no virtual display, and the connection drops when the app is off screen. Remote input, run command, slideshow remote, and clipboard only go from iOS to the desktop.
- **AirDroid**'s file-transfer page names Linux, but its system requirements list no native Linux client. The web client is the Linux path.
- **LinkMyMac**: the Mac and iPad app is the paid one, and the Android app is the free companion. The App Store also lists visionOS.
- **LocalSend Web** works browser to browser. Whether it can exchange files with the installed apps is unclear: the repo says yes, but the page says app support is coming.
- **Join Desktop** talks to the phone directly on the same network. Off that network, a browser has to stay open to receive pushes. The Firefox extension was dropped in 2021 in favor of the web app.

</details>

<details>
<summary><strong>3. What each product is for</strong></summary>

<br>

| | One-line purpose | Who it is for |
| --- | --- | --- |
| **FileApex** | Local-first file manager for paired devices, plus clipboard, a bulletin board, phone notifications, device details, and one-time browser links | Your own phones and computers, plus file-only sending to invited Friends and Groups |
| **Blip** | Send files and folders of any size straight to a person or your own devices | Individuals and teams who will sign in |
| **LocalSend** | Send files and text to a nearby device, with no account and no internet | Anyone on the same network |
| **Pushbullet** | Push links, files, notifications, and SMS through an account in the cloud | People who want an Android phone's notifications on every computer |
| **KDE Connect** | Many small plugins that make a phone and a desktop act together | Linux and Plasma users with Android phones; other desktops work with rough edges |
| **LinkMyMac** | Make an Android phone feel at home on a Mac: messages, files, mirroring, camera, calls | Mac and iPad users with Android phones |
| **DashBeam** | Send files device to device over QUIC, on the LAN or across the internet, with no account | Anyone sending to anyone |
| **Bridgey** | Small native bridge for clipboard, files, notifications, battery, Find Device, and calls over the local network | One Android phone and one Mac |
| **AirDroid Personal** | Manage, mirror, and remote-control Android from a computer or a browser, and move files | People who want a remote console for an Android phone |
| **Join** | Push notifications, SMS and MMS, clipboard, links, files, and commands between Android and your browsers and PCs through your Google account, all scriptable from Tasker | Android users in Google's ecosystem, especially Tasker users |
| **Rabbit Link** | Make an Android phone feel native on a Mac: notifications with each app's icon, replies, calls, photos, files, and clipboard over Wi-Fi or Bluetooth | Mac users with an Android phone who want a polished, paid, local-only companion |

</details>

<details>
<summary><strong>4. File transfer</strong></summary>

<br>

| Feature | FileApex | Blip | LocalSend | Pushbullet | KDE Connect | LinkMyMac | DashBeam | Bridgey | AirDroid | Join | Rabbit Link |
| --- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| Send files | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ Wi-Fi or USB | ✅ | ✅ stream, cancel | ✅ | ✅ | ✅ both ways |
| Send folders | ✅ | ✅ no zip | ✅ | ❌ | — | — | ✅ | ❌ v0.8 | ✅ Premium, local network | — | — |
| Send to several devices at once | ✅ | — | ✅ multiple recipients | ✅ devices on the account | ❌ | ❌ | ✅ | ❌ v0.8 | — | ✅ device groups | ❌ one phone, one Mac |
| Size cap | none for direct transfers | none | none | 25 MB free, 1 GB Pro | — | — | none | none published | local: none. Remote: 30 MB free, 1 GB Premium | none published; your Drive quota applies | — |
| Monthly transfer cap | none | none on the free plan | none | storage 2 GB free, 100 GB Pro | none | none | none | none | remote 200 MB/month free; unlimited Premium | none published | none, local only |
| Resume after a drop | ✅ byte offset, SHA-256 | ✅ | ❌ retry starts over | ❌ | — | — | ✅ kept chunks, BLAKE3 | ❌ retry starts at byte zero | — | — | — |
| Pause | ✅ queue | ✅ | ❌ | ❌ | — | — | ✅ cancel keeps the partial store | ✅ cancel | — | — | — |
| Integrity check | ✅ SHA-256 | — | ✅ SHA-256, on by default | — | — | ✅ since 1.57 | ✅ BLAKE3 per chunk | ✅ | — | — | — |
| Receiver gets bytes while the sender is still sending | ✅ direct LAN, Tailscale, and Iroh | ✅ | ✅ after accept, direct upload | ❌ upload, then download | ❌ | — | ❌ content-addressed chunks | ✅ streaming | local is direct; remote uses their service | ❌ uploads to Drive first | — |
| Browse the other device | ✅ paired devices | ❌ | ❌ | ❌ | ✅ SFTP, Dolphin | ✅ photos, downloads, folders | ❌ preview before download | ❌ v0.8 | ✅ photos, video, music, apps | ✅ Android storage | ✅ photos and videos only |
| Share menu / drag and drop | ✅ Finder, Windows Share and Send To, Android Direct Share, desktop drag and drop | ✅ right-click, share sheet | ✅ | ✅ | ✅ | ✅ Android share, Cast | ✅ | ✅ Android share, Mac drag and drop | ✅ | ✅ Android share, Chrome right-click | ✅ Android share, drag photos out |
| Received files land in | `Download/FileApex`, `~/Downloads/FileApex` | the place you choose | Downloads, configurable | Pushbullet storage, then the device | Downloads, configurable | the device's download area | chosen on receive; per-device folder for trusted devices | `Download/Bridgey`; Mac folder configurable | the AirDroid file manager | "Join Files" in Google Drive, then the device | — |

**Notes**

- **Pushbullet**'s own help page tells people with large files to use a different app over Wi-Fi. Pushed files count against your Pushbullet storage.
- **Bridgey** keeps at most 20 recent transfers in memory. An interrupted send can be retried after reconnecting, but the retry starts from the first byte.
- **AirDroid**'s marketing page says "no size limits", but the pricing page has the numbers. Local transfer is unlimited on both plans. Remote transfer is 30 MB per file and 200 MB a month on free, and 1 GB per file with unlimited data on Premium. Folder transfer is Premium only and works on the local network only.
- **Join** sends files over the internet through your Google Drive, and directly when both devices are on the same LAN. Join fixed sending files over about 2 GB on the LAN in 3.0.11.

</details>

<details>
<summary><strong>5. How devices connect</strong></summary>

<br>

| Feature | FileApex | Blip | LocalSend | Pushbullet | KDE Connect | LinkMyMac | DashBeam | Bridgey | AirDroid | Join | Rabbit Link |
| --- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| Pair with a QR code | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ QR is for share links | ❌ roadmap | — | ❌ | ✅ |
| Pair with a short code | ✅ 6 digits, or auto-detect a broadcast | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ both confirm a 6-digit code | ❌ | ❌ | — |
| Pair by accept prompt | ❌ | — | ❌ tap a nearby device | ❌ same account | ✅ | ✅ | ✅ Nearby, since 0.7.0 | ✅ mutual confirm | ✅ same account | ❌ same Google account | — |
| Optional PIN | ✅ | — | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ encryption password only | — |
| Works with no internet | ✅ LAN | ✅ LAN direct | ✅ | ❌ | ✅ LAN | ✅ | ✅ LAN | ✅ LAN | ✅ local mode | — | — |
| Works across the internet | ✅ opt-in: Tailscale, Iroh (public or self-hosted relay), or Google | ✅ direct or their relay | ❌ | ✅ their cloud | ❌ needs your own VPN | ❌ | ✅ Iroh, optional self-hosted relay | ❌ | ✅ remote mode | ✅ Google push, App Engine, Drive | ❌ |
| Dial a device by key, not IP | ✅ Iroh | — | ❌ | ❌ account device ID | ❌ | ❌ | ✅ | ❌ | ❌ account | ❌ account device ID | ❌ |
| Type an IP by hand | ❌ | — | ✅ | ❌ | ✅ | ✅ manual pairing fallback | ❌ | ❌ | ❌ | — | — |
| Survives a network change mid-transfer | ✅ reconnect and resume | — | ❌ | ❌ | ❌ | ❌ | ✅ same connection | ✅ reconnects; file restarts | — | — | — |
| USB | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Bluetooth | ❌ | ❌ | ❌ | ❌ | ✅ after OS pairing | ❌ reconnect assist only | ❌ | ❌ after v1.0 | ❌ | ❌ | ✅ Android 15+ |
| Guest link, no install on the receiver | ✅ one-time link at getfileapex.com/drop | ❌ invite by email | ✅ browser link, same network | ❌ | ❌ | ❌ | ✅ link or QR opens the web app | ❌ | ❌ web client needs login | ❌ web app needs login | ❌ |
| Encryption as documented | mTLS with pinned keys; TLS 1.3 over QUIC (Iroh); E2EE clipboard, notifications, and share links | mTLS over TLS 1.3; relay can't read files | HTTPS, per-device certificate | optional AES-256-GCM for notifications, clipboard, SMS; not files | TLS between paired devices | direct on the LAN or USB | TLS 1.3 over QUIC | AES-GCM, pinned signing keys | HTTPS for the remote path | HTTPS; optional password-based AES; Drive files rely on Google | "encrypted", protocol not named |

**Notes**

- **FileApex** direct transfers stay between paired devices on LAN, Tailscale (`tsnet`), or Iroh. The Google account is optional. It's used for presence, wake, and an optional Drive relay for Bulletin Board files. Cellular clipboard sharing sends ciphertext through Firebase.
- **FileApex Share as link** (new in v0.20.5) is opt-in, one file, one download, from any browser at getfileapex.com/drop. The sender's app must stay open. The link expires after 15 minutes to 24 hours. The file is end-to-end encrypted through an Iroh relay that stores nothing, and the key sits after `#`, so it never reaches the website.
- **Blip** requires a verified email. Commercial use is on the honor system.
- **LocalSend**'s browser link serves files from the sender's app over plain HTTP on the same network. The sender has to stay open.
- **DashBeam and FileApex** both use Iroh. DashBeam is a sender, with tickets, paired devices, and a browser receiver. FileApex uses Iroh as one path between devices you've already paired, alongside LAN and Tailscale.
- **AirDroid Nearby** sends photos and videos to someone close by with no account and no internet. Everything else needs an account, and remote use draws from the monthly quota.
- **Join**'s optional encryption doesn't cover who a message is addressed to. The Windows Store app has no encryption.
- **Rabbit Link** never routes content through its servers, but the Mac checks your trial or license online.

</details>

<details>
<summary><strong>6. Phone and computer together</strong></summary>

<br>

| Feature | FileApex | Blip | LocalSend | Pushbullet | KDE Connect | LinkMyMac | DashBeam | Bridgey | AirDroid | Join | Rabbit Link |
| --- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| Phone notifications on the computer | ✅ choose the apps | ❌ | ❌ | ✅ free; actions on Pro | ✅ | ✅ | ❌ | ✅ per-app filter | ✅ | ✅ choose the apps | ✅ native Mac alerts; choose the apps |
| Reply from the computer | ✅ when there's a reply action | ❌ | ❌ | ✅ Pro | ✅ some apps | ✅ where Android allows | ❌ | ✅ inline | ✅ SMS | ✅ | ✅ |
| Dismiss on the phone from the computer | ✅ | ❌ | ❌ | ✅ | ✅ | — | ❌ | ✅ | ✅ | ✅ dismiss everywhere | ✅ |
| Clipboard | ✅ either direction, opt-in | ❌ | ❌ | ✅ Pro | ✅ text | ✅ text, images, URLs | ❌ | ✅ text and HTML | ✅ | ✅ manual or automatic | ✅ text; one-time codes |
| Open a link on the other device | ✅ | ❌ | ❌ | ✅ | ✅ | ✅ | ❌ | ❌ in the v0.6 prerelease only | ✅ | ✅ | ✅ beta, Android 15+ |
| Bulletin to every paired device | ✅ text or file | ❌ | ❌ | ✅ a push to all devices | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ push to a device group | ❌ |
| SMS on the computer | ❌ notifications with reply only | ❌ | ❌ | ✅ Android; 100/month free | ✅ Android, both ways | ✅ SMS, MMS, RCS | ❌ | ❌ notification reply only | ✅ view and reply | ✅ SMS, MMS, group texts | ❌ notifications with reply only |
| Call controls | ❌ | ❌ | ❌ | ❌ call notifications only | ✅ call notice; pause media | ✅ answer, decline, mute, end, place | ❌ | ✅ answer, decline, hang up, dial | ✅ place and manage | ✅ answer, end, mute, place | ✅ answer, decline |
| Find or ring the phone | ✅ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ✅ | ✅ with location | ✅ ring and locate | ❌ |
| Battery and device details | ✅ battery, CPU, storage, RAM, network | ❌ | ❌ | ❌ | ✅ battery | ✅ battery in the menu bar | ❌ | ✅ battery | — | ✅ battery, device info | ✅ battery, signal, ringer |
| Low-battery notice | ✅ posted to the bulletin | ❌ | ❌ | ❌ | ✅ low and discharging | ❌ | ❌ | ❌ level only | — | ✅ low and full | — |
| Screen mirroring | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ Mac and iPad | ❌ | ❌ v0.9 experiment | ✅ plus recording | ❌ screenshots and recording only | ❌ |
| Control the phone from the computer | ❌ | ❌ | ❌ | ❌ | ✅ mouse and keyboard, no screen view | ✅ tap, scroll, open apps | ❌ | ❌ | ✅ mouse and keyboard | ✅ type text, open apps, volume; no screen | ❌ |
| Phone as webcam or microphone | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ Mac webcam and mic | ❌ | ❌ | ✅ remote camera; Premium off the LAN | ❌ | ✅ camera |
| Phone as trackpad or keyboard | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ v0.7 | ❌ | ❌ | ❌ |
| Media control | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ | ❌ | ❌ in the v0.6 prerelease only | — | ✅ | — |
| Run a command on the computer | ❌ | ❌ | ❌ | ❌ API only | ✅ | ❌ | ❌ | ❌ v0.9 | ❌ | ✅ Desktop, EventGhost, Tasker | ❌ |
| Presentation remote | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ v0.7 | ❌ | ❌ | ❌ |
| Contacts | ❌ | ❌ | ❌ | ❌ | ✅ phone to desktop | ✅ | ❌ | ❌ | ✅ | ❌ only in the SMS view | ✅ |

**Notes**

- **FileApex** requests no SMS permissions. Messaging apps show up because their notifications are mirrored, and a reply works when the notification has a reply action. Verification codes stay on the phone by default. Nothing from the notification stream is stored.
- **Pushbullet** free mirrors notifications. Notification actions, Universal Copy & Paste, and more than 100 messages a month are Pro. Its optional end-to-end encryption doesn't cover files in Pushbullet storage. Free API accounts get 500 pushes a month.
- **KDE Connect** can also send the computer's notifications to the phone, pause media when a call rings, and inhibit the screensaver. On Android 15, sensitive notification text is hidden unless the app gets `RECEIVE_SENSITIVE_NOTIFICATIONS`.
- **LinkMyMac Duo Mode** connects one phone to two Macs at once. Advanced Mirroring opens individual Android apps in their own Mac windows.
- **Bridgey** does notification replies only, with no SMS inbox, contacts, or call log. Call audio stays on the phone for Bridgey, LinkMyMac, and Rabbit Link.
- **AirDroid**'s desktop app and website differ. Find Phone and remote erase are web-only. Remote keyboard, notification management, queued dialing, and auto backup are desktop-only. Free remote-controls 1 Android device at a time with 2 attached. Premium attaches 3 (up to 10) and controls up to 4.
- **Join** isn't the default SMS app, so it can't delete messages. Its Play build still asks for SMS and call-log permissions, and no official statement of Google's approval was found.
- **Rabbit Link** also syncs Do Not Disturb to macOS Focus and feeds phone app usage into the Mac's Screen Time.

</details>

<details>
<summary><strong>7. Where FileApex sits</strong></summary>

<br>

**Where it leads.** FileApex is the one free, MIT-licensed app here that's a file manager and a device hub on Android, macOS, Windows, and Linux, with no account. It combines:

- browse and send
- a bulletin board
- opt-in clipboard
- per-app notification mirroring with reply and dismiss
- device details and find-my-phone
- Friends and Groups for file-only sharing
- a one-time browser link for people without FileApex

Direct transfers resume from a byte offset and carry a SHA-256. Off the LAN, paired devices meet over Tailscale or by Iroh device key, with an optional self-hosted relay. A CLI, a terminal dashboard, a Docker node, and a Firefox extension are included.

**Where a neighbor leads.**

- **Blip** sends files of any size to other people, including on iPhone, and resumes after a drop. Personal use is free, business use is per seat, and both sides sign in with an email.
- **LocalSend** is the room-scale sender, with iPhone support, a browser app, and a browser share link, and no pairing or account. It stays on the local network and doesn't resume.
- **Pushbullet** is the cloud account with a browser client. File size, storage, message count, clipboard, and notification actions are metered. Its iPhone app is gone, and development has slowed.
- **KDE Connect** has the longest plugin list: SMS as a real inbox, the phone as a trackpad, presentation remote, run command, media, contacts, Bluetooth, and a Linux-first desktop. iOS is a reduced client, and off the LAN you bring your own VPN.
- **LinkMyMac** is the Mac-and-Android product with screen mirroring on Mac and iPad, phone control, a virtual webcam, USB, RCS, and full call controls. It's a one-time purchase with no Windows or Linux app.
- **DashBeam** sends to other people with no account, resumes from verified chunks, and can be received in a browser. A transfer keeps going when a device changes networks.
- **Bridgey** is the open-source Android–Mac companion with the smallest permission list and real call controls. It covers two devices on one network, and it retries a failed file from the first byte.
- **AirDroid** is the remote-control console: mirroring, mouse, keyboard, camera, SMS, calls, find, and erase, from a desktop app or a browser. The remote path is capped unless you pay.
- **Join** is the Tasker-native hub: notifications with reply, real SMS and MMS from any browser, clipboard, find-my-phone, calls, media, screenshots, and a URL API for IFTTT, Node-RED, MQTT, and EventGhost. Everything runs through a Google account and your Google Drive, and it has no iOS app.
- **Rabbit Link** is the polished, paid Android–Mac companion, with native-looking notifications, call answering, the phone as a Mac camera, photo streaming, contacts, and Bluetooth. It's closed-source, Mac only, one phone to one Mac, and local only.

**Gaps that still matter for FileApex.**

- There's no iPhone or iPad app.
- There's no screen mirroring, remote control of the phone, virtual webcam, or call controls.
- SMS comes through the notification stream rather than a real SMS app.
- The browser link is one file and one download, and the sender has to stay online.

</details>

---

<details>
<summary><strong>Sources</strong></summary>

<br>

Checked 2026-11-06 unless a page names an earlier date.

- **FileApex:** [GitHub releases](https://github.com/byhowiecreations/FileApex/releases) (v0.20.5), [README](https://github.com/byhowiecreations/FileApex/blob/main/README.md), [PRIVACY](https://github.com/byhowiecreations/FileApex/blob/main/PRIVACY.md), [Google Play](https://play.google.com/store/apps/details?id=com.fileapex), [getfileapex.com/drop](https://getfileapex.com/drop/)
- **Blip:** [blip.net](https://www.blip.net/), [pricing](https://www.blip.net/pricing), [download](https://blip.net/download), [security](https://www.blip.net/security), [App Store](https://apps.apple.com/us/app/blip-send-files-fast/id6463305181), [Google Play](https://play.google.com/store/apps/details?id=net.blip.android)
- **LocalSend:** [localsend.org](https://localsend.org/), [changelog](https://localsend.org/changelog), [releases](https://github.com/localsend/localsend/releases), [README](https://github.com/localsend/localsend/blob/main/README.md), [protocol](https://github.com/localsend/protocol), [web app](https://github.com/localsend/web)
- **Pushbullet:** [file size help](https://help.pushbullet.com/articles/is-there-a-file-size-limit), [API docs](https://docs.pushbullet.com/), [Google Play](https://play.google.com/store/apps/details?id=com.pushbullet.android). The Pro price is from an indexed copy of pushbullet.com/pro, because the live page didn't render.
- **KDE Connect:** [kdeconnect.kde.org](https://kdeconnect.kde.org/), [download](https://kdeconnect.kde.org/download.html), [UserBase](https://userbase.kde.org/KDEConnect), [F-Droid](https://f-droid.org/packages/org.kde.kdeconnect_tp/), [Gear 26.08.3](https://kde.org/announcements/gear/26.08.3/)
- **LinkMyMac:** [linkmymac.com](https://linkmymac.com/), [features guide](https://linkmymac.com/features-guide), [FAQ](https://linkmymac.com/faq), [App Store](https://apps.apple.com/us/app/linkmymac/id6755784154), [Google Play](https://play.google.com/store/apps/details?id=com.kdg.beam_android)
- **DashBeam:** [GitHub](https://github.com/tonyantony300/dashbeam), [releases](https://github.com/tonyantony300/dashbeam/releases), [downloads](https://www.dashbeam.net/en/downloads), [Google Play](https://play.google.com/store/apps/details?id=com.dashbeam.android)
- **Bridgey:** [GitHub](https://github.com/singeol/bridgey), [releases](https://github.com/singeol/bridgey/releases), [bridgey.ai](https://bridgey.ai/)
- **AirDroid Personal:** [personal](https://www.airdroid.com/personal/), [file transfer](https://www.airdroid.com/personal/file-transfer-management/), [remote control](https://www.airdroid.com/personal/remote-control-android-devices/), [pricing](https://www.airdroid.com/pricing/airdroid-personal/)
- **Join:** [joaoapps.com/join](https://joaoapps.com/join/), [FAQ](https://joaoapps.com/join/faq/), [API](https://joaoapps.com/join/api/), [changelog](https://joaoapps.com/join/changelog/), [desktop](https://joaoapps.com/join/desktop/), [privacy](https://joaoapps.com/join/privacy-policy/), [Google Play](https://play.google.com/store/apps/details?id=com.joaomgcd.join), [Chrome Web Store](https://chromewebstore.google.com/detail/join-by-joaoapps/flejfacjooompmliegamfbpjjdlhokhj), [JoinDesktop](https://github.com/joaomgcd/JoinDesktop)
- **Rabbit Link:** [rabbit-link.app](https://rabbit-link.app/), [privacy](https://rabbit-link.app/privacy), [Gumroad](https://pradeepkod.gumroad.com/l/rabbit), [Google Play](https://play.google.com/store/apps/details?id=com.stem.rabbit), [Product Hunt](https://www.producthunt.com/products/rabbit-4/launches/rabbit-5)

</details>
