variable "environment" {
  description = "Název prostředí (dev, prod) — jde do jmen zdrojů a tagů."
  type        = string
}

variable "aws_region" {
  description = "AWS region."
  type        = string
}

variable "vpc_cidr" {
  description = "CIDR blok VPC."
  type        = string
  default     = "10.20.0.0/16"
}

variable "availability_zones" {
  description = "AZ, do kterých se rozprostřou subnety (min. 2 kvůli RDS subnet group a ALB)."
  type        = list(string)
}

variable "image_tag" {
  description = "Tag image v ECR, který má běžet. CI ho přepisuje na git SHA."
  type        = string
  default     = "latest"
}

variable "api_desired_count" {
  description = "Počet tasků role api."
  type        = number
  default     = 1
}

variable "worker_desired_count" {
  description = "Počet tasků role worker."
  type        = number
  default     = 1
}

variable "task_cpu" {
  description = "Fargate CPU jednotky na task."
  type        = number
  default     = 512
}

variable "task_memory" {
  description = "Fargate paměť (MiB) na task."
  type        = number
  default     = 1024
}

variable "db_instance_class" {
  description = "Třída RDS instance."
  type        = string
  default     = "db.t4g.small"
}

variable "db_allocated_storage" {
  description = "Velikost úložiště RDS v GB."
  type        = number
  default     = 20
}

variable "db_backup_retention_days" {
  description = "Doba držení automatických záloh (PITR okno)."
  type        = number
  default     = 7
}

variable "db_deletion_protection" {
  description = "Ochrana proti smazání RDS. V dev vypnuto, v prod zapnout."
  type        = bool
  default     = true
}

variable "log_retention_days" {
  description = "Retence CloudWatch logů."
  type        = number
  default     = 30
}

variable "tags" {
  description = "Tagy přidané ke všem zdrojům."
  type        = map(string)
  default     = {}
}

variable "certificate_arn" {
  description = "ACM certifikát pro HTTPS listener. Null = jen HTTP (dev)."
  type        = string
  default     = null
}
