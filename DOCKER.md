# FileApex Docker Deployment Guide

Welcome to the official deployment and configuration guide for running the **FileApex Daemon** inside Docker.

---

## Quick Start & Network Configuration

For advanced environments or dedicated setups requiring an independent IP address on your physical subnet, you can deploy using Docker's **macvlan** network driver.

### 1. Create the Macvlan Network (if you need)
Create the network by specifying your subnet, gateway, and physical host interface name:

```bash
docker network create -d macvlan \
  --subnet=xxx.xx.xx.0/24 \
  --gateway=xxx.xx.xx.1 \
  -o parent=[INTERFACE] \
  fileapex-macvlan
```

### 2. Run the Container
Run the container attaching it to your macvlan network with a dedicated static IP address:

```bash
docker run --name fileapex-daemon --rm -it \
  --net fileapex-macvlan --ip 172.16.16.8 \
  -v fileapex-data:/app/data fileapex/fileapex-daemon:latest
```

---

## CLI Flags Reference

FileApex daemon supports helpful command-line flags to manage your configuration and setup flow:

| Flag | Purpose | Description |
| :--- | :--- | :--- |
| `--reset` | Clear Configuration | Clears the existing configuration file (`/app/data/config.json`) and forces a full re-initialization on startup. |
| `--setup` | Interactive Menu | A restart of an already joined node resumes on its own; use this flag to reopen the menu. Launches the full interactive configuration menu with 4 choices: <br>1. Join local cluster (Auto-detect or manual code entry)<br>2. Connect to Tailscale via browser sign-in<br>3. Enter Tailscale pre-auth key<br>4. Reset configuration and setup new |
| `--tailscale` | Direct Tailscale Setup | Bypasses the main menu and goes directly to Tailscale connection methods (URL browser sign-in or pre-auth key). |

### Example Usage with Flags
```bash
docker run --name fileapex-daemon --rm -it \
  -v fileapex-data:/app/data fileapex/fileapex-daemon:latest --setup
```

---

## Standard Deployment Options

For everyday users, managing raw macvlan network flags can be cumbersome. We recommend standardizing deployment using **Docker Compose** or via your NAS management interface.

### 1. Docker Compose (Recommended)
Create a `docker-compose.yml` file in your project directory:

```yaml
version: "3.8"

services:
  fileapex-daemon:
    image: fileapex/fileapex-daemon:latest
    container_name: fileapex-daemon
    restart: unless-stopped
    network_mode: "host" # Or standard bridge if preferred
    volumes:
      - fileapex-data:/app/data

volumes:
  fileapex-data:
```

Bring your stack up in the background:
```bash
docker compose up -d
```

### 2. OpenMediaVault (OMV) & Portainer Deployment
1. Navigate to your **Portainer / Stacks** panel and click **Add Stack**.
2. Paste the `docker-compose.yml` configuration above.
3. Click **Deploy the stack**.

*(For OMV native users, ensure you have the `openmediavault-compose` plugin installed via System -> Plugins).*

### 3. Deploy on Unraid
1. Navigate to your **Docker** page and click **Add Container**.
2. Enter the following:
```
Name = fileapex-daemon
Repository = fileapex/fileapex-daemon:latest
Network type = host
Click "Add another path"
Container path = /app/data
Host path = /mnt/user/appdata/fileapex
````

3. Click **Apply** then start.  
It will automatically enter pairing mode the first time, so on your other device click Add Device and generate the code.


### 4. Interacting with Running Containers
If you need to trigger interactive setup flags on an already deployed container via CLI:
```bash
docker exec -it fileapex-daemon /app/fileapex-daemon --setup
```

---

## Maintenance & Updates

To clean up, pull the latest image updates, and redeploy:

```bash
docker rm -f fileapex-daemon
docker rmi fileapex/fileapex-daemon:latest
docker pull fileapex/fileapex-daemon:latest

---

## Encrypted connections (TLS)

The daemon listens for pinned mutual TLS on the file port + 1 (8081 by default; set `FILEAPEX_TLS_PORT` to change it).
Publish it when you do not use host or macvlan networking: `-p 8080:8080 -p 8081:8081`.

- Its key and certificate live in the data volume (`tls_key.pem`, `tls_cert.pem`), with the pairing key
  (`pair_key`) and the pins it has accepted (`tls_peers.json`). Keep the volume to keep the identity.
- The log prints a key fingerprint at start (`TLS listening ... key fingerprint abcd-1234-...`).
- After a device is paired, apps and the daemon exchange their keys on their own, using the pairing key.
  No re-pair and no prompt are needed. From then on that device talks to the daemon over TLS only, and the
  daemon refuses plain HTTP that names it, apart from pairing, presence and the key exchange.
- Devices on older builds, and any device that has not exchanged keys yet, keep using plain HTTP.
- If a paired device later shows a different key, the daemon keeps the one it has and logs the change.
  Re-pair that device (or run `--reset`) after checking it is really yours.
- `--reset` also clears the accepted pins. The daemon's own key stays.
