variable "environment" {
  description = "Název prostředí (dev, prod) — jde do jmen zdrojů a tagů."
  type        = string
}

variable "app_iam_user_name" {
  description = "IAM uživatel aplikace (výstup modulu vault-kms), kterému se přidá právo psát zálohy."
  type        = string
}

variable "prefix" {
  description = "Prefix objektů v bucketu. Aplikace pod něj ukládá dumpy a jen tam smí sahat."
  type        = string
  default     = "postgres"
}

variable "retention_days" {
  description = "Po kolika dnech lifecycle smaže zálohu. Pojistka pro případ, že by úklid v aplikaci neběžel — drž ji stejnou nebo delší než BACKUP_RETENTION_DAYS."
  type        = number
  default     = 35
}

variable "tags" {
  description = "Tagy přidané ke všem zdrojům."
  type        = map(string)
  default     = {}
}
