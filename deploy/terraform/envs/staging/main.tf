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
    key          = "appreviewzz/staging/terraform.tfstate"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = var.aws_region
}

# Vlastní klíč, ne sdílený s produkcí. Je to jediná věc, která brání tomu, aby se
# stagingovým access keyem daly rozbalit produkční credentials (ADR 0017).
module "vault" {
  source = "../../modules/vault-kms"

  environment = "staging"
}

# Staging zálohujeme kvůli tomu, aby se obnova zkoušela jinde než v produkci.
# Data jsou syntetická, retence proto krátká.
module "backups" {
  source = "../../modules/backups"

  environment       = "staging"
  app_iam_user_name = module.vault.app_iam_user_name
  retention_days    = 10
}

# Modul key-audit tu schválně není. CloudTrail účtuje první kopii management events
# zdarma jen jednou; produkční trail sbírá celý účet, takže použití tohohle klíče je
# vidět v něm. Druhý trail by platil totéž dvakrát.
