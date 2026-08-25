# ZRUŠENO. Tohle prostředí nahradily envs/staging a envs/prod (ADR 0017). Zůstává v repu
# jen proto, aby se dal pustit `tofu destroy` — po něm se celý adresář smaže.
terraform {
  required_version = ">= 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # Bucket se zakládá mimo tenhle stav — nemůže být uložený sám v sobě.
  # Postup je v deploy/terraform/README.md.
  backend "s3" {
    key          = "appreviewzz/dev/terraform.tfstate"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = var.aws_region
}

# Aplikace běží na Coolify, z AWS se používá jen správa klíčů (ADR 0008).
# Kompletní ECS/RDS stack leží nepoužitý v modules/appreviewzz.
module "vault" {
  source = "../../modules/vault-kms"

  environment = "dev"
}

# Zálohy databáze (F1.8) — bucket a práva pro tentýž IAM uživatel, který sahá na KMS.
module "backups" {
  source = "../../modules/backups"

  environment       = "dev"
  app_iam_user_name = module.vault.app_iam_user_name
}

# Auditní stopa a alarmy nad vault klíčem (F1.9) — objem rozbalování, cizí principal,
# odmítnutá volání. Adresa pro alarmy se předává přes TF_VAR_alarm_email nebo tfvars,
# aby nebyla v repozitáři.
module "key_audit" {
  source = "../../modules/key-audit"

  environment      = "dev"
  vault_key_arn    = module.vault.key_arn
  app_iam_user_arn = module.vault.app_iam_user_arn
  alarm_email      = var.alarm_email
}
