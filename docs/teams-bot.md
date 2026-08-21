# Microsoft Teams — Azure Bot, oprávnění a připojení kanálu

Teams se od Slacku liší v jedné věci, kterou je dobré si uvědomit dřív, než něco nastavíš:
**bot je jeden na celý deployment.** V cloudu náš, v self-hostu ten, kterého si založíš podle
tohohle návodu. Klienti se od sebe liší jen tenantem, který se ukládá zašifrovaný ve vaultu
příkazem `teams connect` — žádná registrace na klienta, žádné heslo v konfiguraci workflow.

Dnešní n8n má proti tomu `client_secret` v plaintextu ve čtyřech uzlech a tenant natvrdo
v URL, takže „přidat klienta" znamená editovat workflow. To je i důvod, proč je secret
z n8n [na seznamu k rotaci](00-n8n-inventura.md).

## 1. Založení bota

1. [Azure Portal](https://portal.azure.com) → **Create a resource** → *Azure Bot*.
   - **Type of App**: *Multi-tenant*, pokud má bot obsluhovat víc zákaznických tenantů
     (náš cloud). *Single-tenant* stačí pro self-host uvnitř jedné firmy.
   - **Creation type**: *Create new Microsoft Entra ID app*.
2. Po vytvoření: **Configuration → Messaging endpoint**:
   `https://<PUBLIC_BASE_URL>/webhooks/teams/messages`
   Endpoint musí být HTTPS; Bot Connector na jiné schéma zprávy neposílá.
3. **Configuration → Microsoft App ID** si opiš, vedle je *Manage Password* →
   **New client secret**. Hodnota se ukáže jednou.
4. **Channels → Microsoft Teams** → zapnout.

Bot nepotřebuje žádná Graph oprávnění. Posílá a upravuje zprávy přes Bot Connector, což je
autorizované jeho vlastní registrací — **žádná delegovaná ani aplikační oprávnění k poště,
souborům ani kalendáři nechceme a nemáme.**

## 2. Konfigurace aplikace

```bash
TEAMS_BOT_APP_ID=…            # povinné: bez něj se messaging endpoint nezaregistruje
TEAMS_BOT_APP_PASSWORD=…      # client secret z kroku 1
TEAMS_BOT_TENANT_ID=…         # jen u single-tenant registrace; multi-tenant nechej prázdné
PUBLIC_BASE_URL=https://appreviewzz.example.com
```

`TEAMS_BOT_APP_ID` je zároveň `aud`, které musí sedět v každém tokenu od Bot Connectoru —
token vystavený pro cizího bota tak na našem endpointu neprojde. Ověření navíc porovnává
`serviceUrl` z tokenu s tělem aktivity, takže se odpovědi nedají přesměrovat jinam.

## 3. Instalace do týmu klienta

Bot se do Teams dostane přes aplikační balíček (manifest + dvě ikony) nahraný v Teams admin
centru, nebo přes *Apps → Manage your apps → Upload an app* v klientovi. V manifestu stačí
`bots[0].botId = TEAMS_BOT_APP_ID` a scope `team`.

Po přidání aplikace do týmu se připojí tenant:

```bash
docker compose exec api appreviewzz teams connect --org islegrow \
    --tenant <TENANT_ID> --team-name "IsleGrow"
```

`--tenant` je Microsoft Entra tenant ID klienta (UUID; v Teams admin centru, nebo v URL Azure
portálu). `--service-url` vyplň jen mimo Evropu — výchozí je `https://smba.trafficmanager.net/emea`,
tedy evropský Bot Connector. Příkaz rovnou zkusí získat token pro bota, takže se špatné heslo
pozná hned, ne až první nedoručenou zprávou.

Uloží se credential typu `TEAMS_BOT_REF`. **Není v něm žádné tajemství bota** — jen tenant
a endpoint; heslo zůstává v konfiguraci deploymentu.

## 4. Připojení kanálu

ID kanálu (`19:…@thread.tacv2`) se v Teams vezme z **⋯ → Kopírovat odkaz na kanál**; je to
část odkazu mezi `/channel/` a `/`.

```bash
docker compose exec api appreviewzz credential list --org islegrow      # najdi ID připojení
docker compose exec api appreviewzz channel add --org islegrow --app <APP_ID> \
    --credential <ID_PRIPOJENI> --teams-channel 19:…@thread.tacv2 --locale cs
docker compose exec api appreviewzz channel test --org islegrow --app <APP_ID>
```

`channel test` založí v kanálu vlákno s „✅ Kanál je připojený". Když nedorazí, řekne rovnou
proč. Každá recenze pak dostane **vlastní vlákno** — odpovědi a chybová hlášení tak nezaplevelí
kanál, na rozdíl od Slacku, kde je zpráva přímo v kanálu a chyba jde do vlákna pod ni.

Klik na **🚀 Odeslat** publikuje odpověď do storu a kartu přepíše na „✅ Odpověděli jste";
vstup i tlačítko zmizí.

## 5. Když něco nechodí

| Projev | Příčina |
|---|---|
| `jobs failed` hlásí `deliver-review` s `AUTH` | vypršel nebo byl odvolaný client secret — obnov ho v Azure a přenastav `TEAMS_BOT_APP_PASSWORD` |
| `jobs failed` hlásí `deliver-review` s `NOT_FOUND` | aplikace není v týmu, nebo je ID kanálu z jiného týmu |
| Klik na Odeslat nic neudělá | Messaging endpoint v kroku 1 nesedí, nebo API neběží na HTTPS |
| V logu `Teams messaging endpoint: aktivita odmítnuta (WRONG_AUDIENCE)` | `TEAMS_BOT_APP_ID` neodpovídá registraci, ze které zpráva přišla |
| V logu `… (SERVICE_URL_MISMATCH)` | někdo zkouší podstrčit cizí endpoint — aktivita se zahodila, není co řešit |
| Zpráva dorazí bez návrhu odpovědi | `AI_PROVIDER`/`AI_API_KEY` nejsou nastavené (nebo AI selhala — je to v logu) |
