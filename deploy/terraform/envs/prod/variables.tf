variable "aws_region" {
  description = "AWS region produkčního prostředí."
  type        = string
  default     = "eu-north-1"
}

variable "alarm_email" {
  description = "Adresa pro alarmy. Nastavuje se přes TF_VAR_alarm_email nebo prod.auto.tfvars (ani jedno není v repozitáři)."
  type        = string
  default     = null
}
