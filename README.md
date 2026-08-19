# appreviewzz

Recenze z Google Play a App Store do Slacku a Teams — s AI návrhem odpovědi, kterou pošlete
zpátky do storu jedním kliknutím. Plus denní přehled ratingů a trendů.

> **Stav: F2 — Slack end-to-end.** Recenze se stahují z Google Play i App Store, chodí do
> Slacku s AI návrhem odpovědi a kliknutí na *Odeslat* publikuje odpověď zpátky do storu.
> Konzole (F3) a Teams s ratingy (F4) teprve přijdou (viz [roadmapa](#roadmapa)).

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

Dokud není konzole (F3), zakládá organizace, aplikace a klíče ke storům stejný binárek jako
server — jen dostane příkaz místo role:

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
appreviewzz slack install-url --org isle-grow      # odkaz pošli klientovi
appreviewzz channel add --org isle-grow --app <APP_ID> \
  --credential <ID_INSTALACE> --slack-channel C0123456789
appreviewzz ingest run --org isle-grow --app <APP_ID>
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

Lokálně bez Dockeru vyrobí spustitelný `appreviewzz` příkaz `./gradlew :server:app:installDist`;
najdeš ho v `server/app/build/install/appreviewzz/bin/`.

## Slack

Jedna Slack App pro všechny klienty, instalace přes OAuth ([ADR 0012](docs/adr/0012-slack-jedna-app-oauth-install.md)).
Klient dostane odkaz z `slack install-url`, schválí tři oprávnění (`chat:write`,
`chat:write.public`, `channels:read`) a vybere kanál — nic dalšího se za něj nenastavuje.

Nová recenze dorazí do kanálu s předvyplněným návrhem odpovědi. Po kliknutí na *Odeslat* se
odpověď publikuje do storu a zpráva se přepíše na „✅ Recenze byla zpracována"; když ji store
odmítne, přistane důvod ve vlákně a formulář zůstane, takže jde odpověď opravit a poslat znovu.

Založení vlastní Slack Appky (self-host) a řešení potíží: [docs/slack-app.md](docs/slack-app.md).

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
console/           React SPA (F3)
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
| `SLACK_CLIENT_ID` / `SLACK_CLIENT_SECRET` | — | zapínají „Add to Slack"; bez nich se appka instaluje ručně |
| `PUBLIC_BASE_URL` | — | veřejná adresa API; z ní se skládá OAuth redirect a instalační odkaz |

Chybějící povinná proměnná shodí start s konkrétní hláškou — žádný tichý fallback.

## Bezpečnost

Klientské klíče ke storům se ukládají šifrovaně (envelope encryption, DEK per organizaci,
KEK v KMS nebo lokálním keysetu) — detaily v [ADR 0005](docs/adr/0005-envelope-encryption.md).
Zranitelnosti hlaste podle [SECURITY.md](SECURITY.md).

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
| **F3** | Konzole: auth, organizace, onboarding wizard, review inbox, delivery health | |
| **F4** | Teams bot, ratings pipeline, denní digesty a trendy | |
| **F5** | Hardening, rate limity, dokumentace, OSS launch | |
| **F6** | Migrace ze staršího n8n řešení a jeho vypnutí | |

## Dokumentace

- [ADR](docs/adr/) — architektonická rozhodnutí a jejich důvody
- [Runbooky](docs/runbooks/) — provozní postupy (zálohy a obnova, alarm na vault klíč)
- [Slack App](docs/slack-app.md) — založení appky, oprávnění, připojení kanálu, řešení potíží
- [SECURITY.md](SECURITY.md) — hlášení zranitelností a jak zacházíme s klíči

## Licence

[AGPL-3.0](LICENSE). Licence se týká kódu, ne dat, která přes systém protečou. Běžný self-host
bez úprav zdrojáků nic zveřejňovat nemusí. Komerční licence na vyžádání — [info@matee.cz](mailto:info@matee.cz).
