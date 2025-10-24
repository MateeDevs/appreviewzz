# Credentials

Create these in **n8n → Credentials**. Use the suggested names for consistency.

---

## Required

### Google Sheets — App Definitions (OAuth2)
**Name (recommended):** `ReviewBot: App Definitions Google Sheet`  
**Purpose:** Read the Google Sheet that holds your app configuration.  
**How to create (high level):**
1. In Google Cloud, create an OAuth 2.0 client (Web application).
2. Add redirect URI: `https://<YOUR_N8N_HOST>/rest/oauth2-credential/callback`
3. In n8n, create a **Google Sheets OAuth2** credential and connect with your Google account.
4. Make sure that Google account has access to the Sheet.

---

### Gemini (PaLM) API — Suggestions
**Name (recommended):** `ReviewBot: Gemini`  
**Purpose:** Generate short suggested replies.  
**How to create (high level):**
1. Enable Gemini/PaLM and get an API key (Google AI Studio or GCP).
2. In n8n, create a **Google PaLM / Gemini API** credential and paste the API key.

---

### Google Play Developer API — Android Replies (Service Account)
**Name (recommended):** `ReviewBot: Google Play Service Account`  
**Purpose:** Post replies to reviews on Google Play.  
**How to create (high level):**
1. In Google Cloud, create a **Service Account** and download the JSON key.
2. In Google Play Console, grant this service account permissions to reply to reviews for your app(s).
3. In n8n, create a **Google API (Service Account)** credential and upload the JSON key.

---

### Microsoft Teams Bot — Azure Bot Messaging Endpoint (Relay)

**Why this is needed:**  
Azure Bot can only point to a **single Messaging Endpoint URL**, but our n8n setup uses **two webhooks**: one for **PROD** and one for **DEV/test**. Without a relay, you’d have to keep switching the endpoint manually.

**Solution:**  
We created an **Azure Function App** that acts as a **bridge**. The Bot always sends to the Function’s URL, and the Function forwards the request to the right webhook:

- `…/webhook-test/<id>` → if the request comes from a DEV channel (listed in `DEV_CHANNEL_IDS`)  
- `…/webhook/<id>` → otherwise (all other channels are treated as PROD)

**How it works (high level):**
- The Function receives the full Bot Framework activity + auth header.  
- It checks the incoming channel ID.  
- If it matches a DEV channel → send to the test webhook; otherwise → send to the prod webhook.  
- The **Authorization header is preserved**, so n8n can still validate the JWT exactly as before.  

**Setup steps:**
1. Create an Azure Function App (Node.js, Consumption plan).  
2. Add env vars:  
   - `DEV_CHANNEL_IDS` → comma-separated list of test channel IDs  
   - `N8N_BASE_URL` → e.g. `https://aut.matee.cz`  
   - `N8N_WEBHOOK_PATH` → webhook UUID from n8n  
3. Deploy the Function code.  
4. In Azure Bot config, set the Messaging Endpoint to your Function URL  
   (e.g. `https://<function>.azurewebsites.net/api/bf-endpoint`).  

**In short:**  
The Function is a **bridge** that makes DEV/PROD separation possible while respecting Azure Bot’s single-endpoint limitation.

---

## Optional (enable only what you need)

### Slack Bot Token — Notifications
**Name (recommended):** `ReviewBot: Slack Bot`  
**Purpose:** Post review notifications to Slack.  
**How to create (high level):**
1. Create a Slack App with a **Bot User**; grant posting scopes (e.g., `chat:write`).
2. Install to your workspace and copy the **Bot User OAuth Token** (starts with `xoxb-`).
3. In n8n, create a **Slack** credential and paste the token.

---

### Apple App Store Connect — iOS Reviews & Replies
**Name (recommended):** `ReviewBot: App Store Connect`  
**Purpose:** Fetch and/or reply to App Store reviews.  
**How to create (high level):**
1. In App Store Connect, generate an **API Key**; record **Issuer ID** and **Key ID**, download the `.p8` key.
2. In n8n, create a credential that can sign **App Store Connect JWT** with those values (or configure an HTTP credential that injects the JWT).

---

### Database (if you persist processed review IDs)
**Name (recommended):** `ReviewBot: Postgres` *(or your DB of choice)*  
**Purpose:** Store processed review IDs to avoid duplicates.  
**How to create (high level):**
1. Ensure your DB is reachable from the n8n container (host, port, user, password, database, SSL if needed).
2. In n8n, create the corresponding DB credential (e.g., **Postgres**) with those values.

---

## Tips
- Use the exact names above to keep workflows consistent.
- After creating the **Google Sheets** credential, re-open the sheet-reading node and **select the spreadsheet + tab from the dropdown**.
- Keep credentials minimal-scoped: read-only for Sheets, only the apps you need in Play/App Store, and only posting scopes for Slack/Teams.

