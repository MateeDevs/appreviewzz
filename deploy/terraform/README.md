# Terraform

AWS zdroje pro appreviewzz. Rozdělené podle toho, co je nasazené a co čeká.

| Modul | Stav |
|---|---|
| `modules/vault-kms` | **nasazené** — KMS klíč pro credential vault a IAM uživatel aplikace |
| `modules/backups` | **připravené** — S3 bucket na dumpy databáze a práva pro aplikaci (F1.8); čeká na `apply` |
| `modules/appreviewzz` | **zaparkované** — kompletní ECS Fargate + RDS + ALB stack, zatím se nepoužívá |

Aplikace běží na Coolify a z AWS potřebuje jen správu klíčů — viz
[ADR 0008](../../docs/adr/0008-hosting-coolify-kek-v-kms.md). ECS/RDS modul zůstává
v repozitáři nedotčený; je zvalidovaný (`tofu validate`), ale nikdy neproběhl `apply`.

## Prostředí dev

```bash
cd deploy/terraform/envs/dev
tofu init -backend-config=backend.hcl
tofu plan
```

`backend.hcl` není v repozitáři (obsahuje jméno bucketu). Obsah:

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

## Co přijde později

- CloudTrail metric filter nad `kms:Decrypt` s alarmem na neobvyklý objem odemykání (F1)
- `envs/prod` — až bude potřeba produkční prostředí
