# Slack App — založení, oprávnění a připojení kanálu

Návod platí pro dvě situace: **self-host** (vlastní Slack App v jednom workspace) a **náš
provoz** (jedna App pro všechny klienty, míří do App Directory). Rozdíl je jen v tom, kdo
appku vlastní — nastavení je stejné.

## 1. Založení appky

1. <https://api.slack.com/apps> → **Create New App** → *From scratch*.
2. **OAuth & Permissions → Bot Token Scopes** — jen tyhle tři:

   | Scope | K čemu |
   |---|---|
   | `chat:write` | poslat zprávu s recenzí, přepsat ji po odeslání odpovědi |
   | `chat:write.public` | psát do veřejného kanálu, aniž by klient musel bota zvát |
   | `channels:read` | výběr kanálu při onboardingu (console, F3) |

   **Žádné user tokeny a žádný `channels:history`.** Stav „✅ odpovězeno" skládáme z vlastních
   dat, původní zprávu ze Slacku nečteme — a scope navíc je v App Directory otázka navíc.
3. **OAuth & Permissions → Redirect URLs**: `https://<PUBLIC_BASE_URL>/slack/oauth/callback`.
4. **Interactivity & Shortcuts** → zapnout, **Request URL**:
   `https://<PUBLIC_BASE_URL>/webhooks/slack/interactivity`.
   Slack čeká na potvrzení do tří sekund; endpoint proto odpověď jen zařadí do fronty.
5. **Basic Information** → z *App Credentials* opsat `Client ID`, `Client Secret` a
   `Signing Secret`.

## 2. Konfigurace aplikace

```bash
SLACK_SIGNING_SECRET=…    # bez něj se interactivity endpoint vůbec nezaregistruje
SLACK_CLIENT_ID=…         # jen pro „Add to Slack" install flow
SLACK_CLIENT_SECRET=…
PUBLIC_BASE_URL=https://appreviewzz.example.com
```

Signing secret je app-level, tedy **jeden pro všechny klienty** — tím padá dnešní stav, kdy
má každý klient v n8n vlastní HMAC secret nalepený v uzlu.

## 3. Instalace do workspace klienta

```bash
docker compose exec api appreviewzz slack install-url --org islegrow
```

Vypíše odkaz s podepsaným `state`, který instalaci váže na organizaci (odkaz platí 2 hodiny).
Klient ho otevře, schválí oprávnění a token workspace se uloží **zašifrovaný ve vaultu** jako
credential typu `SLACK_INSTALL`; v consoli je z něj vidět jen název workspace.

Opakovaná instalace téhož workspace přepíše payload existujícího credentialu — nevzniká druhý,
mrtvý token.

## 4. Připojení kanálu

ID kanálu (`C…`) se ve Slacku vezme z *View channel details* dole, nebo z odkazu na kanál.

```bash
docker compose exec api appreviewzz credential list --org islegrow      # najdi ID instalace
docker compose exec api appreviewzz channel add --org islegrow --app <APP_ID> \
    --credential <ID_INSTALACE> --slack-channel C0123456789 --locale cs
docker compose exec api appreviewzz channel list --org islegrow --app <APP_ID>
```

Od téhle chvíle chodí nové recenze do kanálu s předvyplněným návrhem odpovědi; kliknutí na
**Odeslat** publikuje odpověď do storu a zprávu přepíše na „✅ Recenze byla zpracována".

## 5. Ověření, že to celé funguje

```bash
docker compose exec api appreviewzz ingest run --org islegrow --app <APP_ID>
docker compose exec api appreviewzz review list --org islegrow --app <APP_ID> --limit 5
```

Když zpráva nedorazí, nejčastější příčiny v pořadí, v jakém je hledat:

| Projev | Příčina |
|---|---|
| V DLQ je `deliver-review` s `not_in_channel` | kanál je privátní — pozvi do něj bota (`/invite @appreviewzz`) |
| V DLQ je `deliver-review` s `invalid_auth` | instalace byla ve Slacku odvolaná, projdi krok 3 znovu |
| Klik na Odeslat nic neudělá | Request URL v kroku 4 nesedí, nebo API neběží na HTTPS bez portu |
| Zpráva dorazí bez návrhu odpovědi | `AI_PROVIDER`/`AI_API_KEY` nejsou nastavené (nebo AI selhala — je to v logu) |
