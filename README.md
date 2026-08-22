# appreviewzz

Recenze z Google Play a App Store do Slacku a Teams — s AI návrhem odpovědi, kterou pošlete
zpátky do storu jedním kliknutím. Plus denní přehled ratingů a trendů.

> **Stav: F5 — hardening a příprava na veřejné repo.** Recenze se stahují z Google Play
> i App Store, chodí do Slacku i do Microsoft Teams s AI návrhem odpovědi a kliknutí na
> *Odeslat* publikuje odpověď zpátky do storu. Každé ráno přijde do stejného kanálu přehled
> hodnocení s vývojem proti minulému dni. Klient si celé nastavení projde sám ve webové
> konzoli — od účtu přes klíče ke storu až po kanál (viz [roadmapa](#roadmapa)).

## Rychlý start (self-host / lokální vývoj)

```bash
cp .env.example .env      # vyplň POSTGRES_PASSWORD
docker compose up --build
```

Aplikace poslouchá na `http://localhost:8080`:

| Endpoint | Co dělá |
|---|---|
| `GET /health/live` | proces žije; vrací verzi a git SHA |
| `GET /health/ready` | umíme obsloužit provoz (kontroluje databázi) |
| `GET /metrics` | Prometheus scrape — na portu 8081, ne na veřejném |
| `POST /webhooks/slack/interactivity` | odpovědi ze Slacku; existuje jen se `SLACK_SIGNING_SECRET` |
| `GET /slack/install` | „Add to Slack" — odkaz vydává `slack install-url` |
| `GET /` | konzole (React SPA ze stejného image; v lokálním buildu bez `npm run build` chybí) |
| `/api/**` | API konzole — přihlášení, organizace, appky, klíče, kanály, recenze |

## Konzole

Klient si v ní projde onboarding sám: účet → organizace → appka → klíč ke storu (s okamžitým
ověřením proti API storu) → připojení Slacku → kanál se zkušební zprávou. Pak už jen vidí
recenze, odpovídá na ně a na *Přehledu* pozná, proč něco nedorazilo.

V *Zabezpečení účtu* si každý může zapnout **druhý faktor** — kód z autentizační appky
(Google Authenticator, 1Password, Aegis). Součástí zapnutí je deset záchranných kódů pro
případ ztraceného telefonu ([ADR 0015](docs/adr/0015-druhy-faktor-totp.md)).

Buildí se do statických souborů, které **servíruje Ktor ze stejného image** — žádný CDN,
žádný druhý deploy. Vývoj konzole: [console/README.md](console/README.md).

## Vývoj bez Dockeru

```bash
docker compose up -d postgres
DATABASE_URL=jdbc:postgresql://localhost:5432/appreviewzz \
DATABASE_USER=appreviewzz \
DATABASE_PASSWORD=<z .env> \
./gradlew :server:app:run
```

Build, lint a testy najednou:

```bash
./gradlew build
```

Automatické doformátování Kotlinu:

```bash
./gradlew ktlintFormat
```

## Seed CLI

Totéž, co konzole, umí i příkazová řádka — stejný binárek jako server, jen dostane příkaz
místo role. Hodí se na provozní zásahy přes `docker compose exec` a na první organizaci
v čerstvé instalaci:

```bash
docker compose run --rm api org create --name "Isle Grow"
```

Onboarding klienta od nuly (`appreviewzz help` vypíše všechny příkazy):

```bash
appreviewzz org create --name "Isle Grow"
appreviewzz user add --org isle-grow --email klient@example.com --role owner
appreviewzz app create --org isle-grow --name IsleGrow \
  --gp-package cz.matee.islegrow --asc-app-id 1234567890 --notify-from now
appreviewzz credential add --org isle-grow --type gp --label "Play SA" --file service-account.json
appreviewzz credential attach --org isle-grow --app <APP_ID> --credential <CREDENTIAL_ID>
appreviewzz credential validate --org isle-grow --app <APP_ID> --credential <CREDENTIAL_ID>
appreviewzz slack connect --org isle-grow --token xoxb-…   # nebo `slack install-url` pro cizí workspace
appreviewzz channel add --org isle-grow --app <APP_ID> \
  --credential <ID_INSTALACE> --slack-channel C0123456789
appreviewzz channel test --org isle-grow --app <APP_ID>     # do kanálu přistane „✅ Kanál je připojený"
appreviewzz ingest run --org isle-grow --app <APP_ID>
appreviewzz ratings run --org isle-grow --app <APP_ID>      # denní přehled hned, ne až zítra ráno
```

Do Microsoft Teams to vypadá stejně, jen se místo workspace připojuje tenant:

```bash
appreviewzz teams connect --org isle-grow --tenant <TENANT_ID> --team-name "Isle Grow"
appreviewzz channel add --org isle-grow --app <APP_ID> \
  --credential <ID_PRIPOJENI> --teams-channel 19:…@thread.tacv2
```

Klíč do App Store Connect se poskládá ze staženého `.p8` a údajů opsaných z ASC
(individuální klíč nemá Issuer ID, tak se `--issuer-id` vynechá):

```bash
appreviewzz credential add --org isle-grow --type asc --label "IsleGrow ASC" \
  --file AuthKey_ABC123DEFG.p8 --key-id ABC123DEFG --issuer-id 69a6de70-…
```

Několik věcí, které stojí za zmínku:

- `--notify-from now` je watermark: starší recenze se uloží kvůli historii, ale do kanálů
  nepůjdou — připojení staré appky tedy Slack nezaplaví.
- Payload klíče z vaultu nikdy nevyjde. CLI tiskne jen fingerprint a neutrální nápovědu
  (`client_email`, Key ID) — přesně to, co uvidí klient v konzoli.
- Nová appka se rozjede sama, worker si ji vyzvedne při nejbližším sweepu.
- Návratové kódy: `0` hotovo, `1` příkaz neprošel (neexistující organizace, klíč odmítnutý
  storem), `2` špatně zadaný příkaz. Skript nad tím pozná překlep od odmítnutí storu.
- `channel test` je jediný způsob, jak hned po nastavení poznat odvolaný token, chybějící
  scope a bota nepozvaného do privátního kanálu — jinak se na ně přijde až tím, že první
  recenze nikam nedorazí.
- `jobs failed` vypíše úlohy, které se nepovedly ani po opakování (DLQ). Dokud není konzole,
  je to jediný pohled na to, co se v provozu nedoručilo a proč.

Lokálně bez Dockeru vyrobí spustitelný `appreviewzz` příkaz `./gradlew :server:app:installDist`;
najdeš ho v `server/app/build/install/appreviewzz/bin/`.

## Slack

Jedna Slack App pro všechny klienty ([ADR 0012](docs/adr/0012-slack-jedna-app-oauth-install.md))
se třemi oprávněními: `chat:write`, `chat:write.public`, `channels:read`.

Připojit workspace jde dvěma způsoby. Do **vlastního workspace** (self-host i náš provoz do
schválení v App Directory) se appka nainstaluje z api.slack.com a token se vloží příkazem
`slack connect`. Do **cizího workspace** dostane klient odkaz z `slack install-url` a appku si
nainstaluje sám; výsledek je v obou případech tentýž zašifrovaný credential.

Nová recenze dorazí do kanálu s předvyplněným návrhem odpovědi. Po kliknutí na *Odeslat* se
odpověď publikuje do storu a zpráva se přepíše na „✅ Recenze byla zpracována"; když ji store
odmítne, přistane důvod ve vlákně a formulář zůstane, takže jde odpověď opravit a poslat znovu.

Založení vlastní Slack Appky (self-host) a řešení potíží: [docs/slack-app.md](docs/slack-app.md).

## Teams

Jeden Azure Bot na celý deployment ([ADR 0013](docs/adr/0013-teams-tenka-vrstva-app-level-bot.md)),
bez jediného oprávnění do Graphu — bot posílá a upravuje zprávy přes Bot Connector, což
autorizuje jeho vlastní registrace.

Klient přidá aplikaci do týmu a připojí se jeho tenant (`teams connect`, nebo tlačítko
v konzoli). Ve vaultu se ukládá **jen tenant a regionální endpoint**; heslo bota je proměnná
prostředí deploymentu, ne per klient.

Každá recenze zakládá v kanálu **vlastní vlákno** s Adaptive Card. Po kliknutí na *🚀 Odeslat*
se karta přepíše na odeslanou odpověď; když ji store odmítne, přistane důvod ve vlákně a karta
i s formulářem zůstane.

Založení bota, instalace do týmu a řešení potíží: [docs/teams-bot.md](docs/teams-bot.md).

## Hodnocení

Každý den v čase nastaveném u aplikace (a v její zóně) přijde do kanálu přehled: celkový průměr
obou platforem, změna proti minulému přehledu, počet nových hodnocení po hvězdách. Vývoj je
vidět i v konzoli jako graf.

Odkud se čísla berou:

| Platforma | Primárně | Doplněk / záloha |
|---|---|---|
| iOS | iTunes lookup (oficiální, bez klíče) — průměr a počet | listing App Storu — rozpad po hvězdách |
| Android | reporting Play Console (`--gp-bucket`) — oficiální průměr | listing Play Storu — průměr i rozpad, když do Play Console nevidíme |

Zdroje se **slučují, ne vylučují**: oficiální data vyhrávají u průměru, veřejný listing dodá
to, co oficiální cesta nedává. Klient bez přístupu do Play Console tak přehled dostane taky,
jen s číslem zaokrouhleným tak, jak ho ukazuje store.

Sémantika delty je vědomě jiná než ve starším n8n řešení —
[ADR 0014](docs/adr/0014-ratings-delta-proti-minulemu-prehledu.md) říká proč.

## Struktura repa

```
server/
  core/            doména a porty — čistý Kotlin, bez frameworků
  persistence/     Postgres: Exposed + Flyway migrace
  crypto/          credential vault: AEAD, KEK providery (KMS / local / Vault)
  connectors/      googleplay, appstore — fetch recenzí, publikace odpovědí, ratingy
  channels/        slack, teams — doručení, interaktivita, aktualizace zpráv
  ai/              pluggable návrhy odpovědí (Gemini / Anthropic / OpenAI / none)
  backup/          zálohy databáze: pg_dump, úložiště (S3 / soubor), obnova
  jobs/            db-scheduler tasky (ingest, zálohy, ratingy, health)
  app/             Ktor: REST, webhooky, DI, konfigurace, entrypoint
console/           React SPA konzole (Vite + TypeScript + TanStack Query)
deploy/docker/     Dockerfile
deploy/terraform/  AWS prostředí
docs/adr/          architektonická rozhodnutí
docs/runbooks/     provozní postupy
```

Jeden image běží ve dvou rolích podle `APPREVIEWZZ_ROLE` (`api` | `worker`) —
viz [ADR 0006](docs/adr/0006-jeden-image-dve-role.md).

## Nasazení

Aplikace běží jako kontejner vedle Postgresu; jediná externí závislost je KMS klíč pro
credential vault. CI staví image a publikuje ho do `ghcr.io/mateedevs/appreviewzz`.

- **Coolify** — [deploy/coolify/](deploy/coolify/) (aktuální provozní prostředí)
- **AWS ECS + RDS** — [deploy/terraform/](deploy/terraform/), modul připravený, zatím nenasazený
- **Cokoli s Dockerem** — `compose.yaml` v kořeni

Proč je to rozdělené takhle, vysvětluje [ADR 0008](docs/adr/0008-hosting-coolify-kek-v-kms.md).

## Konfigurace

Vše přes proměnné prostředí (12-factor), žádný konfigurační soubor v image.

| Proměnná | Default | Popis |
|---|---|---|
| `APPREVIEWZZ_ROLE` | `api` | `api` (HTTP) nebo `worker` (joby) |
| `APPREVIEWZZ_ENV` | `local` | `local` / `dev` / `prod` — jen pro logy a telemetrii |
| `SERVER_HOST` | `0.0.0.0` | |
| `SERVER_PORT` | `8080` | veřejný port — REST, webhooky, healthchecky |
| `MANAGEMENT_PORT` | `8081` | `/metrics`; **nikdy nevystavovat ven** |
| `DATABASE_URL` | — | povinné, např. `jdbc:postgresql://host:5432/appreviewzz` |
| `DATABASE_USER` | — | povinné |
| `DATABASE_PASSWORD` | — | povinné |
| `DATABASE_MAX_POOL_SIZE` | `10` | |
| `DATABASE_MIGRATE_ON_START` | `true` | pouští Flyway; u role `worker` nastavit `false` |
| `VAULT_KEK_URI` | — | KEK provider: `aws-kms://arn:…`, `local://cesta` nebo `vault://transit/klíč`; povinné pro roli `worker` |
| `SCHEDULER_THREADS` | `5` | souběžně běžící joby jednoho workeru |
| `SCHEDULER_POLLING_SECONDS` | `10` | jak často se worker ptá na úlohy, které jsou na řadě |
| `INGEST_SWEEP_SECONDS` | `60` | jak často se fronta ingestu sesouhlasí se seznamem aplikací |
| `BACKUP_TARGET` | — | kam se ukládají zálohy: `s3://bucket/prefix` nebo `file:///cesta`; prázdné = zálohy vypnuté |
| `BACKUP_AT` | `02:30` | denní čas zálohy v UTC |
| `BACKUP_RETENTION_DAYS` | `30` | po kolika dnech se stará záloha maže |
| `BACKUP_KEEP_AT_LEAST` | `7` | kolik posledních záloh zůstává bez ohledu na stáří |
| `BACKUP_S3_ENDPOINT` | — | jiné S3 než AWS (MinIO, Backblaze); zapíná path-style adresaci |
| `PG_DUMP_PATH` / `PG_RESTORE_PATH` | `pg_dump` / `pg_restore` | cesty ke klientským nástrojům Postgresu |
| `AI_PROVIDER` | `none` | `gemini` nebo `none`; bez AI chodí do Slacku prázdný vstup a odpověď píše člověk |
| `AI_MODEL` | `gemini-2.5-flash` | model providera |
| `AI_API_KEY` | — | povinné pro `AI_PROVIDER=gemini` |
| `SLACK_SIGNING_SECRET` | — | bez něj se interactivity endpoint nezaregistruje a ze Slacku nejde odpovídat |
| `SLACK_CLIENT_ID` / `SLACK_CLIENT_SECRET` | — | zapínají „Add to Slack" do cizích workspaců; do vlastního stačí `slack connect` |
| `PUBLIC_BASE_URL` | — | veřejná adresa API; z ní se skládá OAuth redirect a instalační odkaz |
| `TEAMS_BOT_APP_ID` | — | `client_id` Azure Bota; bez něj se messaging endpoint nezaregistruje a z Teams nejde odpovídat |
| `TEAMS_BOT_APP_PASSWORD` | — | client secret téže registrace |
| `TEAMS_BOT_TENANT_ID` | — | jen u single-tenant registrace; multi-tenant bot si o token říká přes `botframework.com` |
| `TRUSTED_PROXY_HOPS` | `1` | kolik reverzních proxy stojí před aplikací; podle toho se z `X-Forwarded-For` bere adresa klienta. `0` = aplikace visí na internetu přímo |
| `RATE_LIMIT_ENABLED` | `true` | vypni jen tam, kde limity řeší proxy |
| `RATE_LIMIT_API_PER_MINUTE` | `240` | strop na `/api` per adresa |
| `RATE_LIMIT_AUTH_PER_5M` | `20` | přihlašovací endpointy per adresa |
| `RATE_LIMIT_AUTH_PER_IDENTITY` | `8` | přihlašovací endpointy per cílový účet |
| `RATE_LIMIT_WEBHOOK_PER_MINUTE` | `240` | webhooky per adresa; limit sedí před ověřením podpisu |
| `MAIL_LOG_LINKS` | jen lokálně | smí náhradní odesílatel vypsat do logu i tělo e-mailu (a v něm jednorázový odkaz) |

Chybějící povinná proměnná shodí start s konkrétní hláškou — žádný tichý fallback.

## Bezpečnost

Klientské klíče ke storům se ukládají šifrovaně (envelope encryption, DEK per organizaci,
KEK v KMS nebo lokálním keysetu) — detaily v [ADR 0005](docs/adr/0005-envelope-encryption.md).
Zranitelnosti hlaste podle [SECURITY.md](SECURITY.md).

Před čím konkrétně se bráníme, kde vedou hranice důvěry a co jsme se rozhodli **nechat být**,
je v [threat modelu](docs/threat-model.md). Řádek po řádku projitý
[OWASP ASVS úrovně 2](docs/asvs-l2-audit.md) je vedle něj — včetně toho, co nesplňujeme.

Co drží přihlášení do konzole: argon2id, zamčení účtu po sérii špatných hesel, limity
požadavků per adresa i per cílový účet, CSRF token nasazený na stromě cest (nová sekce API
ho nemůže vynechat), volitelný druhý faktor a CSP bez `unsafe-inline` u skriptů. Webhooky mají
kromě ověření podpisu i **ochranu proti přehrání** — ověřený podpis říká jen to, že požadavek
někdy poslal ten, kdo zná tajemství, ne že ho poslal teď a poprvé.

Použití KEK se **hlídá**, ne jen povoluje: aplikace vystavuje metriku
`appreviewzz_vault_kek_unwrap_total` (rozbalení datového klíče) a v našem provozu k ní běží
CloudTrail alarm na neobvyklý objem, na cizí volající a na odmítnutá volání
([ADR 0011](docs/adr/0011-audit-vault-klice.md), [runbook](docs/runbooks/vault-klic-alarm.md)).
Rozbalený klíč žije jen v paměti a jen pět minut, takže běžný provoz je plochá čára a skok
je vidět.

## Zálohy

Worker každou noc udělá `pg_dump` a nahraje ho do `BACKUP_TARGET`; obnova se dělá příkazem
`backup restore` do vedlejší databáze. Postup, drill a řešení potíží jsou v
[runbooku](docs/runbooks/zalohy-a-obnova.md), rozhodnutí v
[ADR 0010](docs/adr/0010-zalohy-pg-dump.md).

Kdo self-hostuje s lokálním keysetem: **keyset zálohuj zvlášť**, bez něj jsou credentials
v obnovené databázi nečitelné.

## Roadmapa

| Fáze | Obsah | Stav |
|---|---|---|
| **F0** | Repo, CI, Docker, Ktor + Flyway + healthcheck, ADR, Terraform dev | hotovo |
| **F1** | Datový model, credential vault, konektory Google Play a App Store, ingest pipeline, scheduler, seed CLI, zálohy s vyzkoušenou obnovou, audit použití klíče | hotovo |
| **F2** | Slack end-to-end: OAuth install, Block Kit s AI návrhem, publikace odpovědi | hotovo |
| **F3** | Konzole: auth, organizace a role, onboarding wizard, správa klíčů a kanálů, review inbox, delivery health, audit | hotovo |
| **F4** | Teams bot, ratings pipeline, denní digesty a trendy | hotovo |
| **F5** | Hardening: limity požadavků, ochrana proti přehrání, druhý faktor, redakce logů, threat model, sebe-audit ASVS L2 | probíhá |
| **F6** | Migrace ze staršího n8n řešení a jeho vypnutí | |

## Dokumentace

- [ADR](docs/adr/) — architektonická rozhodnutí a jejich důvody
- [Runbooky](docs/runbooks/) — provozní postupy (zálohy a obnova, alarm na vault klíč)
- [Slack App](docs/slack-app.md) — založení appky, oprávnění, připojení kanálu, řešení potíží
- [Teams bot](docs/teams-bot.md) — založení Azure Bota, instalace do týmu, připojení kanálu
- [Threat model](docs/threat-model.md) — co chráníme, před kým, a jaká zbytková rizika neseme
- [Sebe-audit ASVS L2](docs/asvs-l2-audit.md) — OWASP ASVS 4.0.3 řádek po řádku, včetně mezer
- [SECURITY.md](SECURITY.md) — hlášení zranitelností a jak zacházíme s klíči
- [CONTRIBUTING.md](CONTRIBUTING.md) — jak rozjet vývoj a co se hlídá v review

## Licence

[AGPL-3.0](LICENSE). Licence se týká kódu, ne dat, která přes systém protečou. Běžný self-host
bez úprav zdrojáků nic zveřejňovat nemusí. Komerční licence na vyžádání — [info@matee.cz](mailto:info@matee.cz).
