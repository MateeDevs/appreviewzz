# Nasazení na Coolify

Aplikace běží mimo AWS, z AWS se používá jen KMS klíč — viz
[ADR 0008](../../docs/adr/0008-hosting-coolify-kek-v-kms.md).

Prostředí jsou dvě a **každé je samostatný resource s vlastní databází, vlastním KMS klíčem
a vlastním access keyem** ([ADR 0017](../../docs/adr/0017-staging-a-produkce-oddelena-prostredi.md)).
Postup níž platí pro obě, liší se jen hodnotami ve sloupci prostředí.

Image staví GitHub Actions a publikuje do `ghcr.io/mateedevs/appreviewzz`. Coolify ho jen
stahuje; na hostiteli se nic nekompiluje.

Staví se pro **linux/amd64 i linux/arm64**, každá architektura na svém nativním runneru —
ARM hostitelé (Hetzner) jsou běžní a build pod QEMU emulací by trval násobně dýl.

## Proměnné prostředí

Vyplňují se v Coolify UI, nikdy do repozitáře.

| Proměnná | staging | produkce |
|---|---|---|
| `APPREVIEWZZ_ENV` | `staging` | `prod` |
| `APP_VERSION` | `latest` | **`prod`** |
| `POSTGRES_PASSWORD` | vygeneruj náhodné, min. 32 znaků | totéž, ale jiné |
| `VAULT_KEK_URI` | výstup `vault_kek_uri` z `envs/staging` | z `envs/prod` |
| `AWS_REGION` | `eu-north-1` | `eu-north-1` |
| `AWS_ACCESS_KEY_ID` / `_SECRET_` | key uživatele `appreviewzz-staging-app` | `appreviewzz-prod-app` |
| `BACKUP_TARGET` | výstup `backup_target` z `envs/staging` | z `envs/prod` |
| `BACKUP_RETENTION_DAYS` | `7` | `30` |
| `TRUSTED_PROXY_HOPS` | `1` | `1` |
| `PUBLIC_BASE_URL` | `https://console.staging.appreviewzz.com` | `https://console.appreviewzz.com` |
| `CONSOLE_BASE_URL` | totéž | totéž |
| `CONSOLE_ALLOWED_HOSTS` | `console.staging.appreviewzz.com` | `console.appreviewzz.com` |
| `MAIL_SMTP_HOST` | `smtp.resend.com` | `smtp.resend.com` |
| `MAIL_SMTP_USER` | `resend` | `resend` |
| `MAIL_SMTP_PASSWORD` | API klíč `appreviewzz-staging` | API klíč `appreviewzz-prod` |
| `MAIL_FROM` | `staging@…` | `noreply@…` |

`CONSOLE_ALLOWED_HOSTS` vypisuj přesně, ne zástupným znakem: `*.appreviewzz.com` sedí
i na doménu druhého prostředí, takže by produkce uměla poslat odkaz na obnovu hesla
mířící na staging.

Access key se generuje ručně v AWS konzoli (IAM → Users → `appreviewzz-<prostředí>-app` →
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
6. **Vypni Auto Deploy** (Settings → *Auto Deploy*). Nasazení spouští CI, ne push do gitu.
7. Branch nastav podle prostředí: staging `epic/v2`, produkce `production`. Coolify si z ní
   bere `compose.yaml`, takže produkce nesmí sledovat vývojovou větev.
8. Deploy.

Kontrola po nasazení:

```bash
curl -s https://<doména>/health/ready
```

Musí vrátit `{"status":"UP","checks":{"database":"UP"}}`.

`/metrics` běží na portu 8081, který se schválně nikde nevystavuje — Prometheus endpoint
prozrazuje vnitřní stav aplikace i provoz na jednotlivých endpointech. Scrape si pro něj
chodí po interní síti.

## Automatický deploy z CI

Nasazuje se výhradně z `.github/workflows/deploy.yml`, podle toho, do které větve se pushlo:

| Push do | Co se stane |
|---|---|
| `epic/v2` | postaví se image, publikuje jako `:latest` a `:<sha>`, nasadí se staging |
| `production` | nic se nestaví; tag `:prod` se přesměruje na ten sha a nasadí se produkce |

Potřebuje repozitářové secrets:

| Secret | Kde ho vzít |
|---|---|
| `COOLIFY_STAGING_WEBHOOK_URL` | Coolify → resource stagingu → Webhooks → Deploy Webhook |
| `COOLIFY_PROD_WEBHOOK_URL` | totéž u produkčního resource |
| `COOLIFY_TOKEN` | Coolify → Keys & Tokens → API tokens |

URL deploy webhooku obsahuje UUID resource. Po smazání a znovuvytvoření resource je stará
URL mrtvá a secret se musí přepsat — nasazení pak končí HTTP 404, ne tichým nicneděláním.

**Auto Deploy musí být v Coolify u obou resourců vypnutý.** Git webhook chodí v okamžiku
pushe, tedy o celý build dřív, než je image v GHCR: staging by se tím trvale nasazoval
o jeden commit pozadu a produkce by se re-deployovala při každém pushi do vývojové větve,
protože `compose.yaml` si Coolify bere z git větve.

## Verze, která běží

Služby mají `pull_policy: always`, takže každé nasazení stáhne aktuální image. Bez toho by
Docker u pohyblivého tagu použil lokální kopii a nasazení by tiše zůstalo na první stažené
verzi.

**Staging jede na `latest`** — každý push do `epic/v2` ho nasadí. **Produkce jede na `prod`**
a ten tag se přesměruje výhradně pushem do větve `production`; postup je v
[runbooku](../../docs/runbooks/nasazeni-do-produkce.md). Kdo do produkčního `APP_VERSION`
napíše `latest`, obejde tím celé schvalování.

Co reálně běží, řekne `/health/live` — vrací verzi i git SHA.

## Zálohy

Postgres v kontejneru nemá PITR, zálohuje se proto logicky: worker každou noc udělá
`pg_dump -Fc` a nahraje ho do S3 bucketu z `BACKUP_TARGET`. Retence je 30 dní
(`BACKUP_RETENTION_DAYS`), posledních sedm záloh zůstává vždycky.

Postup obnovy, drill a co dělat, když zálohy přestanou chodit, je v
[runbooku](../../docs/runbooks/zalohy-a-obnova.md). Rychlá kontrola:

```bash
docker exec -it <kontejner-api> /opt/appreviewzz/bin/appreviewzz backup list
```

Alarm si postav nad metrikou `appreviewzz_backup_last_success_age_seconds` (port 8081) —
prahem je zhruba 30 hodin, tedy jedna zmeškaná noční záloha. Náš provoz to řeší mimo
hostitele terraform modulem `backup-freshness`, který se ptá přímo S3: to, že job doběhl,
totiž ještě neznamená, že objekt někam dosedl.
