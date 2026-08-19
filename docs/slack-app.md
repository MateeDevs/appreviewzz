# Slack App — založení, oprávnění a připojení kanálu

Návod platí pro dvě situace a liší se jen posledním krokem:

- **Jeden workspace** (self-host, a do konce F5 i náš provoz): appku si nainstaluješ přímo
  z api.slack.com a token vložíš příkazem `slack connect`. OAuth flow ani veřejný install
  odkaz nepotřebuješ.
- **Víc workspaců** (po schválení v App Directory): klient dostane instalační odkaz
  a appku si nainstaluje sám.

Nastavení appky je v obou případech stejné, takže se přechodem z prvního na druhé nic
nepřepisuje — přibude jen `SLACK_CLIENT_ID`/`SLACK_CLIENT_SECRET`.

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
   Potřeba až pro instalaci do cizích workspaců; při jednom workspace tenhle krok přeskoč.
4. **Interactivity & Shortcuts** → zapnout, **Request URL**:
   `https://<PUBLIC_BASE_URL>/webhooks/slack/interactivity`.
   Slack čeká na potvrzení do tří sekund; endpoint proto odpověď jen zařadí do fronty.
5. **Basic Information** → z *App Credentials* opsat `Client ID`, `Client Secret` a
   `Signing Secret`.

## 2. Konfigurace aplikace

```bash
SLACK_SIGNING_SECRET=…    # povinné: bez něj se interactivity endpoint vůbec nezaregistruje
PUBLIC_BASE_URL=https://appreviewzz.example.com
SLACK_CLIENT_ID=…         # jen pro „Add to Slack" do cizích workspaců
SLACK_CLIENT_SECRET=…
```

**Interactivity URL musí být HTTPS na standardním portu** — na `https://…:8080` Slack tlačítko
*Odeslat* nepošle. Tohle platí i pro jediný vlastní workspace.

Signing secret je app-level, tedy **jeden pro všechny klienty** — tím padá dnešní stav, kdy
má každý klient v n8n vlastní HMAC secret nalepený v uzlu.

## 3a. Instalace do jednoho workspace (self-host, náš provoz do konce F5)

V appce **Install App → Install to Workspace**, zkopíruj *Bot User OAuth Token* (`xoxb-…`) a:

```bash
docker compose exec api appreviewzz slack connect --org islegrow --token xoxb-…
```

Příkaz token rovnou ověří proti Slacku (`auth.test`), doplní název workspace i schválené
scopes a uloží ho **zašifrovaný ve vaultu** jako credential typu `SLACK_INSTALL` — tedy přesně
to, co by vzniklo po instalaci odkazem. Token se pak dá v CLI jen připojit ke kanálu, přečíst
už ho nikdo nemůže.

Jeden workspace zvládne víc organizací: každý partner má vlastní organizaci a vlastní kanál,
credential se do každé organizace vloží zvlášť (šifruje se klíčem té organizace). Až bude mít
partner vlastní workspace, přibude jen nová instalace a nový kanál — data se nestěhují.

## 3b. Instalace do workspace klienta (po App Directory)

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
