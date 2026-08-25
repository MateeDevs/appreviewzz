variable "environment" {
  description = "Název prostředí (staging, prod) — jde do jmen zdrojů a tagů."
  type        = string
}

variable "bucket_name" {
  description = "Bucket se zálohami (výstup modulu backups)."
  type        = string
}

variable "prefix" {
  description = "Prefix, pod kterým aplikace ukládá dumpy. Musí sedět s prefixem modulu backups."
  type        = string
  default     = "postgres"
}

variable "alarm_topic_arn" {
  description = "SNS topic, na který jde poplach (výstup modulu key-audit) — ať nemáme dvě místa, kam chodí alarmy."
  type        = string
}

variable "max_age_hours" {
  description = "Kdy je poslední záloha moc stará. Denní záloha ve 02:30 plus rezerva na zpoždění a přechod času."
  type        = number
  default     = 26
}

variable "schedule_expression" {
  description = "Kdy se kontrola pouští. Výchozí 06:00 UTC dává noční záloze tři a půl hodiny náskok."
  type        = string
  default     = "cron(0 6 * * ? *)"
}

variable "log_retention_days" {
  description = "Retence logů samotné kontroly."
  type        = number
  default     = 30
}

variable "tags" {
  description = "Tagy přidané ke všem zdrojům."
  type        = map(string)
  default     = {}
}
