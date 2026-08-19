locals {
  name = "appreviewzz-${var.environment}"

  tags = merge(
    {
      Project     = "appreviewzz"
      Environment = var.environment
      ManagedBy   = "terraform"
    },
    var.tags,
  )

  container_port = 8080
  # Metriky nejsou v target group ani v security group — ven se nedostanou.
  management_port = 8081
}
