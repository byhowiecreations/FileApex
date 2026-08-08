# FileApex

FileApex is an ecosystem file manager designed to seamlessly sync, manage, and broadcast files across multiple Android devices, Mac and now Windows. 


<img width="439" height="988" alt="home" src="https://github.com/user-attachments/assets/c423486d-6691-44ed-8d22-d437067d5c15" />
<br>
<img width="437" height="986" alt="send-to" src="https://github.com/user-attachments/assets/b673b122-63d8-4ed2-8e33-f4dd50c68f33" />


## Features

* **Local-first P2P explorer:** Browse and move files between paired Android, macOS, and Windows devices on the same LAN.
* **Multi-Target File Broadcasting:** Push files to multiple online devices at once.
* **Smart receive folder:** Incoming files land in `Download/FileApex` (Android) or `~/Downloads/FileApex` (desktop).
* **QR / link pairing:** Pair devices without typing IPs.
* **PIN lock (optional):** Require a PIN before others can pair or browse.
* **macOS Finder / Share Extension:** Send from Finder or the system Share sheet.
* **Windows Share / Send To:** Can send files from right-click menu in Windows using Share or the Send To.
* **Android Direct Share:** Share sheet targets for paired devices.
* **Clipboard Sharing:** Added the ability to share your device clipboard content or URL with any other device. Works across Android/Mac/Windows in any direction (**opt-in** on all devices you wish to allow). URLs should automatically open in default browser.
* **Device Details:** You can view details of any device you are paired with, such as battery level, charging status, processor, storage, RAM, etc.
* **Background Persistence (Android):** Keep the share server alive with battery guidance, watchdog, and optional auto-launch after reboot.
* **Check for Updates:** Install newer builds from GitHub Releases (**opt-in**).
* **Google Account (opt-in):** Cloud presence / wake helpers only — files stay on your LAN.


## Local Configuration

Files broadcasted across devices route automatically to the local device storage paths:
* **Android:** `Download/FileApex/`
* **macOS/Windows:** `~/Downloads/FileApex/`

## Privacy & Permissions Disclosures

To provide cross-platform file access and seamless system integration, the app may request the following:

* **File system access:** Core functionality. Browse, list, and share files and folders on your device with paired machines on your local network.
* **Local network (LAN):** Discover paired devices and transfer files over Wi‑Fi or Ethernet. File data stays on your network; it is not uploaded to the cloud.
* **Background operation (Android):** Keeps the share server available so paired devices do not see you as unexpectedly offline. Includes foreground service, boot restart (when enabled in Settings), and recommended battery settings so the OS does not kill background sharing.
* **Notifications (Android):** Shows share-server status and, if you turn it on, alerts when files are received.
* **Nearby Wi‑Fi devices (Android):** Reads Wi‑Fi name and wireless connection details for the Device Details screen. Not used for location.
* **Camera (Android):** Scan a pairing QR code when you choose Scan to add a device.

**Optional features (only if you enable them):**

* **Google Account linking:** Uses the internet to sign in with Google and register your device for cloud-assisted pairing. No file contents are sent to Firebase — only device IDs and connection metadata needed to find peers.
* **Check for Updates:** Uses the internet to check GitHub Releases for newer builds. Nothing is ever sent to any other servers.
* **Install updates (Android):** Lets you install a downloaded APK from within the app.
* **Exact alarms (Android):** Supports the service watchdog when background persistence is enabled in Settings.
* **Phone state (Android):** Reads cellular network type, signal, and band for the Device Details screen when you are on mobile data (**opt-in**).


FileApex does not request location access.
















## License

Copyright (c) 2026 ByHowieCreations

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or share copies of the Software for personal, non-commercial educational purposes only.

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

COMMERCIAL USE, INCLUDING MONETIZATION, SALE, RENTAL, OR INTEGRATION INTO PAID PRODUCTS OR SERVICES, IS STRICTLY PROHIBITED WITHOUT EXPLICIT WRITTEN PERMISSION FROM THE COPYRIGHT HOLDER.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
