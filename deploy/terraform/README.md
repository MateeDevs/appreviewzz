# Terraform

AWS zdroje pro appreviewzz. Rozdělené podle toho, co je nasazené a co čeká.

| Modul | Stav |
|---|---|
| `modules/vault-kms` | **nasazené** — KMS klíč pro credential vault a IAM uživatel aplikace |
| `modules/backups` | **připravené** — S3 bucket na dumpy databáze a práva pro aplikaci (F1.8); čeká na `apply` |
| `modules/key-audit` | **připravené** — CloudTrail, metric filtry a alarmy nad použitím vault klíče (F1.9); čeká na `apply` |
| `modules/backup-freshness` | **připravené** — denní kontrola, že v bucketu leží čerstvý dump, a alarm nad jejím stářím |
| `modules/appreviewzz` | **zaparkované** — kompletní ECS Fargate + RDS + ALB stack, zatím se nepoužívá |

Aplikace běží na Coolify a z AWS potřebuje jen správu klíčů — viz
[ADR 0008](../../docs/adr/0008-hosting-coolify-kek-v-kms.md). ECS/RDS modul zůstává
v repozitáři nedotčený; je zvalidovaný (`tofu validate`), ale nikdy neproběhl `apply`.

## Přihlášení

Všechno jede přes SSO; `tofu` na rozdíl od `aws` CLI nezná `--profile` a čte proměnnou
prostředí. Bez ní se pokusí sáhnout na IMDS a skončí na `No valid credential sources found`:

```bash
aws sso login --profile appreviewzz-dev
export AWS_PROFILE=appreviewzz-dev
```

Profil se jmenuje `appreviewzz-dev` historicky — je to **účet, ne prostředí**, a platí pro
`envs/prod` i `envs/staging`.

## Prostředí

| Adresář | Co v něm je |
|---|---|
| `envs/prod` | vault klíč, zálohy (35 dní), CloudTrail a alarmy, kontrola stáří zálohy |
| `envs/staging` | vault klíč a zálohy (10 dní); trail schválně ne — první kopie management events je zdarma jen jednou |
| `envs/dev` | **zrušené**, nahradila ho dvojice výše ([ADR 0017](../../docs/adr/0017-staging-a-produkce-oddelena-prostredi.md)); zůstává jen kvůli `destroy` |

```bash
cd deploy/terraform/envs/prod
tofu init -backend-config=backend.hcl
tofu plan
```

`backend.hcl` není v repozitáři (obsahuje jméno bucketu), takže si ho po naklonování
v každém adresáři prostředí založ. Obsah je ve všech stejný:

```hcl
bucket = "<jméno state bucketu>"
region = "eu-north-1"
```

State bucket se zakládá mimo terraform — nemůže být uložený sám v sobě:

```bash
aws s3api create-bucket --bucket <jméno> --region eu-north-1 --create-bucket-configuration LocationConstraint=eu-north-1
```

Následně na něm zapnout versioning, blokovat veřejný přístup, zapnout šifrování a přidat
bucket policy odmítající nešifrovaný přenos. Zamykání state používá nativní S3 lockfile
(`use_lockfile`), DynamoDB tabulka není potřeba.

## Access key aplikace

Negeneruje se terraformem — tajná část by ležela ve state souboru. Vytváří se ručně:
IAM → Users → `appreviewzz-<env>-app` → *Security credentials* → *Create access key* →
use case *Application running outside AWS*. Hodnota jde rovnou do Coolify.

## Adresa pro alarmy

Modul `key-audit` posílá alarmy na e-mail a ten v repozitáři není. Předává se **souborem**
`<prostředí>.auto.tfvars` podle [šablony](envs/prod/prod.auto.tfvars.example) — `*.tfvars`
je v `.gitignore`:

```hcl
alarm_email = "nekdo@example.com"
```

Bez adresy se topic vytvoří bez odběratele a alarm nikam nedojde. Odběr je potřeba potvrdit
klikem v došlém e-mailu.

**Nepředávej adresu přes `TF_VAR_alarm_email`.** Funguje to, ale příští apply spuštěný bez té
proměnné odběr smaže — terraform ho v konfiguraci nevidí, takže ho odstraní, a příjemci přijde
„byl jste odhlášen". Soubor v adresáři prostředí se načítá vždycky, proměnná prostředí ne.

## Co přijde později

- `envs/prod` — až bude potřeba produkční prostředí
