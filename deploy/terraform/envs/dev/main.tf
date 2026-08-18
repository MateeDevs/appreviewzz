terraform {
  required_version = ">= 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # Stav v S3 se zámkem. Bucket a DynamoDB tabulka se zakládají mimo tenhle stav —
  # viz deploy/terraform/README.md.
  backend "s3" {
    key          = "appreviewzz/dev/terraform.tfstate"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = var.aws_region
}

module "appreviewzz" {
  source = "../../modules/appreviewzz"

  environment        = "dev"
  aws_region         = var.aws_region
  availability_zones = var.availability_zones
  image_tag          = var.image_tag

  # Dev jede nejmenší smysluplnou konfiguraci — škálování je jen počet tasků.
  api_desired_count        = 1
  worker_desired_count     = 1
  task_cpu                 = 512
  task_memory              = 1024
  db_instance_class        = "db.t4g.small"
  db_backup_retention_days = 7
  db_deletion_protection   = false
  log_retention_days       = 14
}
