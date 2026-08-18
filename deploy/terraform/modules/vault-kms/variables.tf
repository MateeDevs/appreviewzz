variable "environment" {
  description = "Název prostředí (dev, prod) — jde do jmen zdrojů a tagů."
  type        = string
}

variable "tags" {
  description = "Tagy přidané ke všem zdrojům."
  type        = map(string)
  default     = {}
}
