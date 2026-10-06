# FileApex Docker Deployment Guide

Welcome to the official deployment and configuration guide for running the **FileApex Daemon** inside Docker.

---

## Quick Start & Network Configuration

For advanced environments or dedicated setups requiring an independent IP address on your physical subnet, you can deploy using Docker's **macvlan** network driver.

### 1. Create the Macvlan Network
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
| `--setup` | Interactive Menu | Launches the full interactive configuration menu with 4 choices: <br>1. Join local cluster (Auto-detect or manual code entry)<br>2. Connect to Tailscale via browser sign-in<br>3. Enter Tailscale pre-auth key<br>4. Reset configuration and setup new |
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

### 3. Interacting with Running Containers
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