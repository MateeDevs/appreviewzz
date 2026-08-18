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
