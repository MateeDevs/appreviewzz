variable "aws_region" {
  description = "AWS region dev prostředí."
  type        = string
  default     = "eu-north-1"
}

variable "alarm_email" {
  description = "Adresa pro alarmy nad vault klíčem. Nastavuje se přes TF_VAR_alarm_email nebo dev.auto.tfvars (ani jedno není v repozitáři)."
  type        = string
  default     = null
}
