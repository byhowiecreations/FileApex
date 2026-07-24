# FileApex

FileApex is an ecosystem file manager designed to seamlessly sync, manage, and broadcast files across multiple Android devices and macOS. 


<img width="439" height="988" alt="home" src="https://github.com/user-attachments/assets/c423486d-6691-44ed-8d22-d437067d5c15" />
<br>
<img width="437" height="986" alt="send-to" src="https://github.com/user-attachments/assets/b673b122-63d8-4ed2-8e33-f4dd50c68f33" />


## Features

* **Multi-Target File Broadcasting:** Push files to multiple online devices simultaneously from a single view.
* **Smart Ingestion Paths:** Incoming files are automatically routed to a designated fallback directory (`Download/FileApex`) to keep file systems clean.
* **macOS Finder Integration:** Right-click files directly inside native macOS Finder to broadcast across your local cluster via the Share Extension. 
* **Check for Updates:** Keep your network deployment current with integrated, platform-native updating directly from GitHub releases.


## Local Configuration

Files broadcasted across devices route automatically to the local device storage paths:
* **Android:** `/storage/emulated/0/Download/FileApex/`
* **macOS:** `~/Downloads/FileApex/`

## Privacy & Permissions Disclosures

To provide cross-platform file access and seamless system integration, the app requests the following system permissions and capabilities:

* **File System Access:** Core functionality. Allows the app to navigate, list, and read the user's local directory structure to facilitate remote file management.
* **Unrestricted Battery Usage (Android):** Prevents the OS from aggressively putting the background service to sleep, ensuring the device does not unexpectedly appear "Offline" to connected clients.
* **Internet & External Network Access:** This is strictly **Opt-In**. Used solely to validate Google Account authentication and to safely query for software updates directly from Github.
    * **Strict Privacy Boundary:** No actual files, folders, or personal user data will ever touch Firebase. Firestore will be used strictly as a serverless "virtual registry" to exchange public keys, random device IDs, and local network connection strings. Using Firebase (Google Account) is entirely **Opt-In**.
* **Local Network (LAN) Sockets:** Initiates local network traffic to discover peer devices and stream file data securely between your machines. No personal file data ever leaves your local network.
* **Finder & Share Menu Extensions (macOS):** Integrates directly with the native macOS file manager to provide quick-access context menus and enables sending files to your device pipeline using the system Share menu.
















## License

Copyright (c) 2026 ByHowieCreations

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or share copies of the Software for personal, non-commercial educational purposes only.

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

COMMERCIAL USE, INCLUDING MONETIZATION, SALE, RENTAL, OR INTEGRATION INTO PAID PRODUCTS OR SERVICES, IS STRICTLY PROHIBITED WITHOUT EXPLICIT WRITTEN PERMISSION FROM THE COPYRIGHT HOLDER.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
