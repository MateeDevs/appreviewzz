# DEPLOYMENT

This stack builds on the n8n self-hosted template (architecture, queue mode, worker scaling, etc.).  
**Source:** [brunosergi/self-hosted-n8n-template](https://github.com/brunosergi/self-hosted-n8n-template)

This repo adds:
- **Traefik** reverse proxy with automatic **Let’s Encrypt** TLS
- Extra **.env** variables for domain/TLS: `N8N_HOST`, `WEBHOOK_HOST`, `WEBHOOK_URL`, `LETSENCRYPT_EMAIL`
- Predefined **Review Bot** workflows & credentials
- **One-time workflow import** via a dedicated `n8n-import` service (reads from `./data`)

---

## Prerequisites

- Two **DNS A records** pointing to your server:
  - `N8N_HOST` (e.g., `reviewbot.yourcompany.com`)
  - `WEBHOOK_HOST` (e.g., `webhook.yourcompany.com`)
- Inbound **ports 80 & 443** open (Traefik uses them for HTTP→HTTPS and ACME).
- A valid email for Let’s Encrypt notifications.

---

## Environment

Use the **`.env.example`** in this repo as your starting point (copy to `.env`).  
Fill the required values, especially the Traefik-related ones:
- `N8N_HOST` → public hostname for the n8n UI/API
- `WEBHOOK_HOST` → public hostname for webhooks
- `WEBHOOK_URL` → must be **HTTPS** (e.g., `https://webhook.example.com/`)
- `LETSENCRYPT_EMAIL` → ACME email for TLS certificates

Make sure `N8N_HOST` and `WEBHOOK_HOST` resolve to your server **before** you start the stack.

---

## How workflows & credentials get included (one-time import)

- Put your files into the repo under:
  - **Workflows:** `./data/workflows/` (one JSON per file; exported via n8n “export workflow”)
  - **Credentials:** `./data/credentials/` (one JSON per file)
- On the first `docker compose up -d`, the **`n8n-import`** service runs:
  - imports credentials (if any) from `/seed/credentials`
  - imports workflows from `/seed/workflows`
  - writes a flag file at `/seed-flag/done` (persisted in the `seed-flag` volume)
- Import runs **once**. Subsequent `up` will skip seeding automatically.

## Quick Start (any Linux VM)

1) Create `.env` from `.env.example` and fill values.  
2) Start the stack:

        docker compose up -d


Open:
- n8n UI → `https://<N8N_HOST>`
- Webhook base → `https://<WEBHOOK_HOST>/`

Then proceed to **Credentials.md** and **App_Definition.md** (deploy first → create credentials in n8n → bind **Get App Config** workflow to your Google Sheet).


## AWS Lightsail (example)

**Minimum spec (tested OK):** **2 GB RAM**, **2 vCPU**, **60 GB SSD (Ubuntu)**.  
**Not enough:** the smallest plan (≈ **512 MB RAM**, 2 vCPU, 20 GB SSD) — `docker compose up` will not complete reliably.

### 1) Upload files

        # variables
        KEY=~/YourKeyPath/LightSailKey.pem
        IP=<YOUR_INSTANCE_IP>
        USER=ubuntu

        chmod 600 "$KEY"

        # upload compose + env
        scp -i "$KEY" docker-compose.yml .env "$USER@$IP:/home/$USER/"

        # upload data/ if present
        scp -i "$KEY" -r ./data "$USER@$IP:/home/$USER/"

### 2) SSH in & install Docker

        ssh -i "$KEY" "$USER@$IP"

        # quick install
        curl -fsSL https://get.docker.com | sudo sh
        sudo usermod -aG docker $USER
        newgrp docker

        docker --version
        docker compose version

### 3) Run the stack

        cd ~
        ls -la   # should show docker-compose.yml  .env  (and data/ if used)

        docker compose pull
        docker compose up -d

        docker compose ps
        docker logs -f traefik   # watch certificates being issued

If Traefik can’t obtain certs, check:
- A records for `N8N_HOST` and `WEBHOOK_HOST` point to the instance IP
- Lightsail networking/firewall allows **80/443**
- No other service is occupying ports 80/443

---

## Post-deploy Checklist

- `https://<N8N_HOST>` loads n8n over HTTPS  
- `https://<WEBHOOK_HOST>/` responds (webhooks base)  
- All credentials created in n8n (see **Credentials.md**)  
- **Get App Config**: Google Sheets credential selected, correct spreadsheet/tab chosen, `app_id` filter returns the expected row
