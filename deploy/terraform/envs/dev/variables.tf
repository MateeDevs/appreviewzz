variable "aws_region" {
  description = "AWS region dev prostředí."
  type        = string
  default     = "eu-central-1"
}

variable "availability_zones" {
  description = "Dvě AZ (RDS subnet group a ALB je vyžadují)."
  type        = list(string)
  default     = ["eu-central-1a", "eu-central-1b"]
}

variable "image_tag" {
  description = "Tag image v ECR. CI ho nastavuje na git SHA."
  type        = string
  default     = "latest"
}
