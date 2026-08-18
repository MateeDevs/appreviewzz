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
}
