variable "environment" {
  description = "Název prostředí (dev, prod) — jde do jmen zdrojů a tagů."
  type        = string
}

variable "vault_key_arn" {
  description = "ARN vault klíče (výstup modulu vault-kms), jehož použití se hlídá."
  type        = string
}

variable "app_iam_user_arn" {
  description = "ARN IAM uživatele aplikace (výstup modulu vault-kms). Kdokoliv jiný nad klíčem je podezřelý."
  type        = string
}

variable "alarm_email" {
  description = "Adresa, na kterou chodí alarmy. Prázdné = topic vznikne bez odběratele a nikam se nepíše."
  type        = string
  default     = null
}

variable "unwrap_threshold_per_hour" {
  description = "Kolik rozbalení klíče za hodinu je ještě normální. Cache DEK (5 minut) stropí legitimní provoz na 12 volání za hodinu na organizaci, takže výchozích 150 unese deset klientů i s ratings pipeline. Vzorec a tabulka jsou v runbooku."
  type        = number
  default     = 150
}

variable "log_retention_days" {
  description = "Retence CloudWatch Logs skupiny s auditní stopou. Delší retence = vyšší účet za uložené logy."
  type        = number
  default     = 90
}

variable "trail_retention_days" {
  description = "Po kolika dnech lifecycle smaže surové CloudTrail soubory v S3. Drž delší než log_retention_days — tohle je archiv, ze kterého se dohledává zpětně."
  type        = number
  default     = 365
}

variable "tags" {
  description = "Tagy přidané ke všem zdrojům."
  type        = map(string)
  default     = {}
}
