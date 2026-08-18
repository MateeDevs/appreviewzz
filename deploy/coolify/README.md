# Nasazení na Coolify

Aplikace běží mimo AWS, z AWS se používá jen KMS klíč — viz
[ADR 0008](../../docs/adr/0008-hosting-coolify-kek-v-kms.md).

Image staví GitHub Actions a publikuje do `ghcr.io/mateedevs/appreviewzz`. Coolify ho jen
stahuje; na hostiteli se nic nekompiluje.

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
3. Vystav ven **jen službu `api`** na portu 8080. Worker ani Postgres port nepotřebují.
4. Domain nastav na subdoménu pro webhooky — Coolify vyřídí TLS přes Let's Encrypt.
5. Deploy.

Kontrola po nasazení:

```bash
curl -s https://<doména>/health/ready
```

Musí vrátit `{"status":"UP","checks":{"database":"UP"}}`.

## Automatický deploy z CI

Workflow `.github/workflows/deploy.yml` po pushi na `epic/v2` postaví image, publikuje ho
do GHCR a zavolá deploy webhook Coolify. Potřebuje repozitářové secrets:

| Secret | Kde ho vzít |
|---|---|
| `COOLIFY_WEBHOOK_URL` | Coolify → resource → Webhooks → Deploy Webhook |
| `COOLIFY_TOKEN` | Coolify → Keys & Tokens → API tokens |

Když nejsou nastavené, workflow se přeskočí a image se jen publikuje.

## Zálohy

Postgres v kontejneru nemá PITR. Zálohovací job (`pg_dump` do object storage) a vyzkoušená
obnova jsou součástí F1 — do té doby v databázi nejsou žádná data, o která by šlo přijít.
