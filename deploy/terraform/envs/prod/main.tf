terraform {
  required_version = ">= 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    archive = {
      source  = "hashicorp/archive"
      version = "~> 2.0"
    }
  }

  # Bucket se zakládá mimo tenhle stav — nemůže být uložený sám v sobě.
  # Postup je v deploy/terraform/README.md.
  backend "s3" {
    key          = "appreviewzz/prod/terraform.tfstate"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = var.aws_region
}

# Aplikace běží na Coolify, z AWS se používá jen správa klíčů, zálohy a audit
# (ADR 0008, ADR 0016). Kompletní ECS/RDS stack leží nepoužitý v modules/appreviewzz.
#
# Produkční klíč vzniká nový a prázdný. Přestěhovat KEK ze stagingu nejde: AwsKmsKekProvider
# rozbaluje s explicitním keyId, takže data zabalená jiným klíčem už nikdo neotevře (ADR 0017).
module "vault" {
  source = "../../modules/vault-kms"

  environment = "prod"
}

# Klientská data, proto delší retence než u stagingu. Lifecycle je pojistka pro případ,
# že by úklid v aplikaci neběžel — drž ho delší než BACKUP_RETENTION_DAYS.
module "backups" {
  source = "../../modules/backups"

  environment       = "prod"
  app_iam_user_name = module.vault.app_iam_user_name
  retention_days    = 35
}

# Auditní stopa a alarmy nad vault klíčem (F1.9). Trail sbírá management events celého
# účtu, takže je v něm vidět i použití stagingového klíče — druhý trail by se už platil.
module "key_audit" {
  source = "../../modules/key-audit"

  environment      = "prod"
  vault_key_arn    = module.vault.key_arn
  app_iam_user_arn = module.vault.app_iam_user_arn
  alarm_email      = var.alarm_email
}

# Kontrola, že záloha reálně dosedla mimo hostitele. Poplach jde do stejného topicu
# jako alarmy nad klíčem — jedno místo, kam se chodí dívat.
module "backup_freshness" {
  source = "../../modules/backup-freshness"

  environment     = "prod"
  bucket_name     = module.backups.bucket_name
  alarm_topic_arn = module.key_audit.alarm_topic_arn
}
