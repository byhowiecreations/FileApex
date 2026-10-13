<div align="center">

# FileApex

**A local-first file manager and sharing hub for Android, macOS and Windows.  
Browse, send and sync between your own devices without a cloud in the middle.**

[![Latest release](https://img.shields.io/github/v/release/byhowiecreations/FileApex?label=Download&color=brightgreen)](https://github.com/byhowiecreations/FileApex/releases/latest)
[![Platform](https://img.shields.io/badge/Platform-Android_|_macOS_|_Windows_|_Linux-blue.svg)](#download)
[![License](https://img.shields.io/badge/License-Non_commercial-orange.svg)](#license)
[![Status: new and moving fast](https://img.shields.io/badge/status-new_%26_moving_fast-f2a516.svg)](#download)

[**Download**](#download) • [**Features**](#features) • [**Pairing**](#pairing) • [**Notifications & Bulletin Board**](#notifications--bulletin-board) • [**Networking**](#networking) • [**Privacy**](#privacy) • [**License**](#license)

[**Google Play**](https://play.google.com/store/apps/details?id=com.fileapex)

</div>
<p align="center">
  <img width="480" alt="Kinetic menu" src="https://github.com/user-attachments/assets/946d73d8-afaa-43f5-b694-134374cff2f4" />
  <img width="480" alt="Bulletin Board" src="https://github.com/user-attachments/assets/26d7b1b5-ef22-4146-9e19-19077c08e28c" />
  <br>
  (See more in <a href="screenshots.md">screenshots.md</a>)
</p>

Pair your phone, Mac and PC once, then move files, share the clipboard, send messages and see your phone's notifications from any of them. Files go straight between your devices over your network. Anything that touches the internet is opt-in.

---

## Download

| Platform | Get it |
| --- | --- |
| **Android** | Get the `.apk` from the [latest release](https://github.com/byhowiecreations/FileApex/releases/latest) or [Google Play](https://play.google.com/store/apps/details?id=com.fileapex) |
| **macOS** (Apple Silicon / Intel) | Installer from the [latest release](https://github.com/byhowiecreations/FileApex/releases/latest) |
| **Windows** | Installer from the [latest release](https://github.com/byhowiecreations/FileApex/releases/latest) |
| **Linux** | `.deb`, `.rpm`, or AppImage from the [latest release](https://github.com/byhowiecreations/FileApex/releases/latest) |
| **FireFox Extension** | Installer from the [latest release](https://github.com/byhowiecreations/FileApex/releases/latest) |

Builds from GitHub can check for updates and install them from inside the app (opt-in). Play builds update through Google Play.

### Linux Installation

Download one file from the release page.

**Ubuntu, Debian, ChromeOS Linux container (`.deb`):**

```bash
sudo apt install ./FileApex-v0.17.0-amd64.deb
```

Start FileApex from the application menu.

**Fedora, openSUSE (`.rpm`):**

```bash
sudo dnf install ./FileApex-v0.17.0-x86_64.rpm
```

**Any distribution (AppImage):**

```bash
chmod +x FileApex-v0.17.0-x86_64.AppImage
./FileApex-v0.17.0-x86_64.AppImage
```

Nothing is installed: the file is the app, and deleting it removes the app. The first launch adds a FileApex entry to the application menu and a shortcut on the desktop, both pointing at that file, so keep it where it is or launch it again from its new location.

If the AppImage does not start and mentions FUSE, install the FUSE 2 library (`sudo apt install libfuse2t64` on Ubuntu 24.04 and later, `libfuse2` on older releases) or run it with `--appimage-extract-and-run`.

Received files go to the Downloads folder under `FileApex`.

Updating: download the new file and replace the old one (AppImage) or install the new package over it. Quit FileApex first; an AppImage that is already running keeps running the old version.

Linux rpm/deb builds do not update themselves.

Chromebook: the Linux container sits behind its own network, so other devices cannot reach it yet. Use FileApex on a Chromebook only after the container port forwarding and address override are added.

---

## Features

* **Local-first explorer:** Browse and move files between paired devices on the same network.
* **Multi-target sending:** Push files and folders to several online devices at once.
* **Receive folder:** Incoming files land in `Download/FileApex` on Android and `~/Downloads/FileApex` on desktop.
* **System sharing:** macOS Finder and Share extension, Windows Share and Send To, and Android Direct Share targets for paired devices.
* **Clipboard sharing (opt-in):** Send your clipboard or a link to any paired device, in any direction. Links open in the default browser.
* **Device details:** Battery, charging state, processor, storage, RAM and network for every paired device.
* **Locate phone:** Make a paired phone ring or show where it is from your computer.
* **Themes and layouts:** Pick the layout that suits you, including Simple, built for one phone and one computer.
* **Background persistence (Android):** Keeps the share server reachable, with battery guidance, a watchdog and optional restart after reboot.
* **Command line:** A CLI and terminal dashboard on desktop for scripting and headless use.
* **Browser extension:** Send the active browser tab to your Bulletin Board.

---

## Pairing

No typing IP addresses. Generate a QR code on one device, then on the other use **Join** to scan it, enter the 6-digit code, or automatically detect code broadcast. An optional PIN lock gates pairing and browsing.

---

## Notifications & Bulletin Board

**Bulletin Board** sends a text message or file to all your paired devices at once. Delete a message on one device or on all of them, and the notification is removed from the shade too. With a Google account linked it can also reach Android devices on cellular.

**Phone notifications on your computer (opt-in):** Choose which apps on your phone share their notifications. Messages, email and everything else appear in separate lists on the computer, where you can reply to messages or dismiss them on the phone. Nothing is stored, and verification codes stay on the phone by default.

**Low battery alert:** An Android device that hits low battery posts a notice to the board, and removes it once it is charging again.

---

## Networking

|  |  |
| --- | --- |
| **Same network** | Discovery and transfer over Wi-Fi or Ethernet, after paired. |
| **Tailscale** | Reach paired devices across networks through your tailnet. |
| **Docker** | Run the FileApex daemon as an always-on node. See [DOCKER.md](https://www.google.com/search?q=./DOCKER.md). |
| **Google account (opt-in)** | Cloud presence and wake signals, plus optional Drive relay for Bulletin Board files. Files stay on your devices otherwise. |

---

## Privacy

FileApex has no ads, trackers or analytics. Read the full policy, with every permission and optional service explained, in [PRIVACY.md](https://www.google.com/search?q=./PRIVACY.md).

---

## License

Copyright (c) 2026 ByHowieCreations. Free for personal, non-commercial and educational use. Commercial use, including monetization, sale, rental or integration into paid products or services, needs written permission from the copyright holder.

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or share copies of the Software for personal, non-commercial educational purposes only.

COMMERCIAL USE, INCLUDING MONETIZATION, SALE, RENTAL, OR INTEGRATION INTO PAID PRODUCTS OR SERVICES, IS STRICTLY PROHIBITED WITHOUT EXPLICIT WRITTEN PERMISSION FROM THE COPYRIGHT HOLDER.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.