# Nasazení na Coolify

Aplikace běží mimo AWS, z AWS se používá jen KMS klíč — viz
[ADR 0008](../../docs/adr/0008-hosting-coolify-kek-v-kms.md).

Image staví GitHub Actions a publikuje do `ghcr.io/mateedevs/appreviewzz`. Coolify ho jen
stahuje; na hostiteli se nic nekompiluje.

Staví se pro **linux/amd64 i linux/arm64**, každá architektura na svém nativním runneru —
ARM hostitelé (Hetzner) jsou běžní a build pod QEMU emulací by trval násobně dýl.

## Proměnné prostředí

Vyplňují se v Coolify UI, nikdy do repozitáře.

| Proměnná | Odkud ji vzít |
|---|---|
| `POSTGRES_PASSWORD` | vygeneruj náhodné, min. 32 znaků |
| `VAULT_KEK_URI` | výstup `vault_kek_uri` z `deploy/terraform/envs/dev` |
| `AWS_REGION` | `eu-north-1` |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | access key IAM uživatele `appreviewzz-dev-app` |
| `APP_VERSION` | tag image, default `latest` |
| `APPREVIEWZZ_ENV` | `dev` |

Access key se generuje ručně v AWS konzoli (IAM → Users → `appreviewzz-dev-app` →
Security credentials → Create access key → *Application running outside AWS*), aby se
tajná část nikdy neocitla v terraform state.

## První nasazení

1. V Coolify vytvoř resource typu **Docker Compose** nad `deploy/coolify/compose.yaml`.
2. Vyplň proměnné z tabulky výše.
3. Vystav ven **jen službu `api`** na portu 8080 (doména se zapisuje ve tvaru
   `https://domena:8080` — ten port říká proxy, kam routovat uvnitř kontejneru).
   Worker ani Postgres doménu nepotřebují.
4. Zapni **Connect To Predefined Network**. Bez toho kontejnery nejsou na `coolify`
   síti, Traefik nezjistí jejich IP a vrací `503 no available server` — i když
   aplikace uvnitř běží úplně v pořádku.
5. Domain nastav na subdoménu pro webhooky — Coolify vyřídí TLS přes Let's Encrypt.
6. Deploy.

Kontrola po nasazení:

```bash
curl -s https://<doména>/health/ready
```

Musí vrátit `{"status":"UP","checks":{"database":"UP"}}`.

`/metrics` běží na portu 8081, který se schválně nikde nevystavuje — Prometheus endpoint
prozrazuje vnitřní stav aplikace i provoz na jednotlivých endpointech. Scrape si pro něj
chodí po interní síti.

## Automatický deploy z CI

Workflow `.github/workflows/deploy.yml` po pushi na `epic/v2` postaví image, publikuje ho
do GHCR a zavolá deploy webhook Coolify. Potřebuje repozitářové secrets:

| Secret | Kde ho vzít |
|---|---|
| `COOLIFY_WEBHOOK_URL` | Coolify → resource → Webhooks → Deploy Webhook |
| `COOLIFY_TOKEN` | Coolify → Keys & Tokens → API tokens |

Když nejsou nastavené, workflow se přeskočí a image se jen publikuje.

## Verze, která běží

Compose sahá po tagu `latest` a služby mají `pull_policy: always`, takže každé nasazení
stáhne aktuální image. Bez toho by Docker u pohyblivého tagu použil lokální kopii
a nasazení by tiše zůstalo na první stažené verzi.

Co reálně běží, řekne `/health/live` — vrací verzi i git SHA. Když se neshoduje
s `HEAD` na `epic/v2`, nasazení neproběhlo.

## Zálohy

Postgres v kontejneru nemá PITR. Zálohovací job (`pg_dump` do object storage) a vyzkoušená
obnova jsou součástí F1 — do té doby v databázi nejsou žádná data, o která by šlo přijít.
