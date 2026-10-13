# FileApex vs Blip, LocalSend, KDE Connect, DashBeam and LinkMyMac

Researched 2026-10-13. The data comes from their official sites and repositories (as available). These apps change fast, so check the vendor's page before relying on a detail.

Legend: ✅ yes · ❌ no, or not offered or not listed in the sources checked · ❔ could not be confirmed

---

## The quick view

| | **FileApex** | **Blip** | **LocalSend** | **KDE Connect** | **DashBeam** | **LinkMyMac** |
| --- | :---: | :---: | :---: | :---: | :---: | :---: |
| **What it is** | File transfer, clipboard sharing and device hub for your own devices | Fast file sender between people and devices | Nearby file and text sender | Phone-to-computer integration suite | Peer-to-peer file sender, works across the internet | Android-to-Mac companion |
| **Price** | Free | Free for personal use, paid for work use | Free | Free | Free | $14.99 |
| **Open source** | ✅ MIT | ❌ | ✅ Apache-2.0 | ✅ GPL | ✅ AGPL-3.0 | ❌ |
| **Android** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **iPhone / iPad** | ❌ | ✅ | ✅ | ✅ limited | ❌ | ✅ iPad (iPhone via the separate LinkMyDrop app) |
| **Mac** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Windows** | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ |
| **Linux** | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ |
| **No account needed** | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ |
| **Works over the internet** | ✅ via Tailscale or optional Google | ✅ | ❌ same network only | ❌ same network or VPN | ✅ | ❌ Wi-Fi or USB |
| **Send files** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Phone notifications on your computer** | ✅ | ❌ | ❌ | ✅ | ❌ | ✅ |
| **Clipboard sync** | ✅ | ❌ | ❌ | ✅ text | ❌ | ✅ |
| **Browse the other device's files** | ✅ | ❌ | ❌ | ✅ | ❌ | ✅ |
| **Receiver needs no install** | ❌ | ❌ | ❔ | ❌ | ✅ web app | ❌ |
| **Resume a broken transfer** | ✅ | ✅ | ❌ | ❔ | ✅ | ❔ |

**Pick by need**

- Send a file to anyone, anywhere: DashBeam or Blip.
- Send a file to someone in the room: LocalSend.
- Make your phone and computer behave like one device: FileApex, KDE Connect or LinkMyMac.
- Your own devices, with Windows and Linux support and no account: FileApex or KDE Connect.
- Mac plus Android with screen mirroring and a phone webcam: LinkMyMac.

<details>
<summary><strong>See more: the full technical breakdown</strong></summary>

## 1. Identity and licensing

| | FileApex | Blip | LocalSend | KDE Connect | DashBeam | LinkMyMac |
| --- | :---: | :---: | :---: | :---: | :---: | :---: |
| **Source code public** | ✅ | ❌ | ✅ | ✅ | ✅ | ❌ |
| **Price** | Free | Free non-commercial, paid for commercial | Free | Free | Free | $14.99 |
| **First released** (best estimate) | Jul 2026 | Jan 2024 | Jan 2023 | 2013 | Oct 2025 | Dec 2025 |
| **Store listings** | Google Play, GitHub | App stores | Stores, package managers | Play, F-Droid, App Store, Microsoft Store | Play, GitHub | App Store, Google Play |

## 2. Platforms

| | FileApex | Blip | LocalSend | KDE Connect | DashBeam | LinkMyMac |
| --- | :---: | :---: | :---: | :---: | :---: | :---: |
| **Android** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ phone and tablet |
| **iOS / iPadOS** | ❌ | ✅ | ✅ | ✅ no notification sync, no SMS, connection drops when the app is not on screen; remote input, run command, slideshow remote and clipboard are one-way (iOS to desktop) | ❌ | ✅ iPad |
| **macOS** | ✅ Apple Silicon and Intel | ✅ | ✅ | ✅ stable release, also on Homebrew | ✅ | ✅ |
| **Windows** | ✅ | ✅ | ✅ | ✅ Microsoft Store and offline installer | ✅ | ❌ |
| **Linux** | ✅ .deb, .rpm, AppImage | ✅ | ✅ | ✅ main target | ✅ .deb, .rpm, AppImage | ❌ |
| **Web / browser** | ❌ | ❌ | ✅ web.localsend.org | ❌ | ✅ app.dashbeam.net | ❌ |
| **Command line** | ✅ plus terminal dashboard | ❌ | ✅ via 3rd party | ✅ | ✅ | ❌ |
| **Docker / headless node** | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **Browser extension** | ✅ Firefox | ❌ | ❌ | ✅ Chrome and Firefox via Plasma integration | ❌ | ❌ |

## 3. Core function

| | FileApex | Blip | LocalSend | KDE Connect | DashBeam | LinkMyMac |
| --- | :---: | :---: | :---: | :---: | :---: | :---: |
| **One-line purpose** | Local-first file transfer and sharing between your own paired devices, with a file manager, clipboard, messages and phone notifications | Send files and folders of any size to people or your own devices | Send files and text to nearby devices | Connect a phone to a desktop with many small plugins | Send files device to device over QUIC, anywhere | Make an Android phone work with a Mac |
| **Main audience** | Your own phones and computers | Individuals and teams | Anyone in the same room or network | Linux and Plasma users with Android phones | Anyone sending to anyone | Mac users with Android phones |

## 4. Feature checklist

### File transfer

| Feature | FileApex | Blip | LocalSend | KDE Connect | DashBeam | LinkMyMac |
| --- | :---: | :---: | :---: | :---: | :---: | :---: |
| Send files | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Send folders | ✅ no zipping | ✅ no zipping | ✅ CLI only | ❔ | ✅ | ❔ |
| Send to several devices at once | ✅ Parallel | ❔ | ✅ | ❌ | ✅ Broadcast | ❌ |
| No file size limit | ✅ | ✅ | ✅ | ❔ | ✅ | ❔ |
| Resume after a drop | ✅ | ✅ | ❌ | ❔ | ✅ | ❔ |
| Pause and resume | ✅ | ❔ | ❌ | ❔ | ❔ | ❔ |
| Integrity check | ✅ | ❔ | ✅ optional SHA-256 | ❔ | ✅ BLAKE3 per chunk | ❔ |
| Send and receive at the same time | ✅ | ❔ | ❔ | ❔ | ❔ | ❔ |
| Receiver starts before the sender finishes | ✅ direct LAN and Tailscale, ❌ Drive relay | ✅ | ❌ | ❌ | ❌ | ❌ |
| Browse the other device's files | ✅ | ❌ | ❌ | ✅ SFTP in Dolphin | ❌ | ✅ Android storage on Mac |
| Reply to a text message | ✅ | ❌ | ✅ | ✅ | ❌ | ❌ |
| Operating-system share menu | ✅ | ✅ | ❔ | ✅ | ✅ | ❔ |

### Connecting devices

| Feature | FileApex | Blip | LocalSend | KDE Connect | DashBeam | LinkMyMac |
| --- | :---: | :---: | :---: | :---: | :---: | :---: |
| Pair with QR code | ✅ | ❌ | ❌ | ❌ | ✅ share QR | ✅ |
| Pair with a short code | ✅ | ❌ | ❌ | ❌ | ✅ | ❌ |
| Pair by accept prompt | ❌ | ❔ | ❌ | ✅ | ❌ | ❌ |
| Optional PIN | ✅ | ❔ | ✅ | ❌ | ❌ | ❌ |
| Connect by device key, not IP | ❌ | ❔ | ❌ | ❌ | ✅ | ❌ |
| Survives a network change mid-transfer | ✅ reconnects and resumes | ❔ | ❌ | ❌ | ✅ same connection | ❌ |
| Enter an IP by hand | ❌ | ❔ | ✅ | ✅ | ❌ | ❔ |
| Share with someone who has no app | ❌ | ❌ | ❔ | ❌ | ✅ link opens the web app | ❌ |
| Wired USB link | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Bluetooth link | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ |

### Phone and computer integration

| Feature | FileApex | Blip | LocalSend | KDE Connect | DashBeam | LinkMyMac |
| --- | :---: | :---: | :---: | :---: | :---: | :---: |
| Phone notifications on the computer | ✅ per-app choice | ❌ | ❌ | ✅ | ❌ | ✅ |
| Reply to messages from the computer | ✅ | ❌ | ❌ | ✅ some apps | ❌ | ✅ where Android allows |
| Dismiss on the phone from the computer | ✅ | ❌ | ❌ | ✅ | ❌ | ❔ |
| Clipboard sync | ✅ opt-in, any direction | ❌ | ❌ | ✅ text only | ❌ | ✅ text, images, URLs |
| Open a link on the other device | ✅ | ❌ | ❌ | ✅ | ❌ | ✅ |
| Bulletin board (message or file to all devices) | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| SMS and MMS on the computer | ✅ Inbound and reply | ❌ | ❌ | ✅ Android | ❌ | ✅ |
| Call controls | ❌ | ❌ | ❌ | ✅ telephony notifications | ❌ | ✅ answer, decline, mute |
| Find or ring the phone | ✅ | ❌ | ❌ | ✅ | ❌ | ❌ |
| Battery and device details | ✅ battery, storage, RAM, CPU, network | ❌ | ❌ | ✅ battery | ❌ | ❌ |
| Low battery alert | ✅ | ❌ | ❌ | ✅ | ❌ | ❌ |
| Screen mirroring | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Control the phone from the computer | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Phone as webcam or microphone | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ Mac only |
| Phone as trackpad or keyboard | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ |
| Media control | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ |
| Run commands on the computer | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ |
| Presentation remote | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ |
| Contacts sync | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ |

## 5. Honest summary of FileApex's position

- **Where it leads:** the combination of file manager, bulletin board, clipboard, phone notifications and device details, with no account, all MIT-licensed and on Android, macOS, Windows and Linux.
- **Where others lead:** DashBeam and Blip send across the internet with no setup and survive network changes. LocalSend and Blip run on iPhone. LinkMyMac does screen mirroring, calls and webcam. KDE Connect has the longest plugin list.
- **Gaps that matter:** no iOS app, no direct internet transfer without Tailscale or Google.
- **Ideas on the table:** an Iroh transport on Android arm64 plus desktop would cover internet reach, dial-by-key and roaming. A Safari-based web page would cover iOS on the same network.

## Sources

- DashBeam: [github.com/tonyantony300/dashbeam](https://github.com/tonyantony300/dashbeam) (README and comparison table)
- LocalSend: [github.com/localsend/localsend](https://github.com/localsend/localsend), [protocol spec](https://github.com/localsend/protocol), [localsend.org](https://localsend.org/)
- KDE Connect: [kdeconnect.kde.org](https://kdeconnect.kde.org/), [UserBase wiki](https://userbase.kde.org/KDEConnect), [kdeconnect-kde](https://invent.kde.org/network/kdeconnect-kde), [kdeconnect-meta](https://invent.kde.org/network/kdeconnect-meta)
- Blip: [blip.net](https://blip.net/) and third-party reviews (pricing and account details
- LinkMyMac: [linkmymac.com](https://linkmymac.com/), [features guide](https://linkmymac.com/features-guide), [App Store](https://apps.apple.com/qa/app/linkmymac/id6755784154?platform=mac), [Google Play](https://play.google.com/store/apps/details?id=com.kdg.beam_android)

</details>
