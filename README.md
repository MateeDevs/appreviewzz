# appreviewzz

Recenze z Google Play a App Store do Slacku a Teams — s AI návrhem odpovědi, kterou pošlete
zpátky do storu jedním kliknutím. Plus denní přehled ratingů a trendů.

> **Stav: F0 — základy repa.** Běží kostra aplikace (Ktor + Flyway + healthcheck), CI a
> Docker. Store konektory, vault, Slack/Teams a konzole přicházejí ve fázích F1–F4
> (viz [roadmapa](#roadmapa)).

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

## Struktura repa

```
server/
  core/            doména a porty — čistý Kotlin, bez frameworků
  persistence/     Postgres: Exposed + Flyway migrace
  crypto/          credential vault: AEAD, KEK providery (KMS / local / Vault)
  connectors/      googleplay, appstore — fetch recenzí, publikace odpovědí, ratingy
  channels/        slack, teams — doručení, interaktivita, aktualizace zpráv
  ai/              pluggable návrhy odpovědí (Gemini / Anthropic / OpenAI / none)
  jobs/            db-scheduler tasky (ingest, ratingy, health)
  app/             Ktor: REST, webhooky, DI, konfigurace, entrypoint
console/           React SPA (F3)
deploy/docker/     Dockerfile
deploy/terraform/  AWS prostředí
docs/adr/          architektonická rozhodnutí
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

Chybějící povinná proměnná shodí start s konkrétní hláškou — žádný tichý fallback.

## Bezpečnost

Klientské klíče ke storům se ukládají šifrovaně (envelope encryption, DEK per organizaci,
KEK v KMS nebo lokálním keysetu) — detaily v [ADR 0005](docs/adr/0005-envelope-encryption.md).
Zranitelnosti hlaste podle [SECURITY.md](SECURITY.md).

## Roadmapa

| Fáze | Obsah | Stav |
|---|---|---|
| **F0** | Repo, CI, Docker, Ktor + Flyway + healthcheck, ADR, Terraform dev | hotovo |
| **F1** | Datový model, credential vault, konektory Google Play a App Store, ingest pipeline, scheduler | |
| **F2** | Slack end-to-end: OAuth install, Block Kit s AI návrhem, publikace odpovědi | |
| **F3** | Konzole: auth, organizace, onboarding wizard, review inbox, delivery health | |
| **F4** | Teams bot, ratings pipeline, denní digesty a trendy | |
| **F5** | Hardening, rate limity, zálohy, dokumentace, OSS launch | |
| **F6** | Migrace ze staršího n8n řešení a jeho vypnutí | |

## Dokumentace

- [ADR](docs/adr/) — architektonická rozhodnutí a jejich důvody
- [SECURITY.md](SECURITY.md) — hlášení zranitelností a jak zacházíme s klíči

## Licence

[AGPL-3.0](LICENSE). Licence se týká kódu, ne dat, která přes systém protečou. Běžný self-host
bez úprav zdrojáků nic zveřejňovat nemusí. Komerční licence na vyžádání — [info@matee.cz](mailto:info@matee.cz).
