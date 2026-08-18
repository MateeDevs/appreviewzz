output "alb_dns_name" {
  description = "Veřejná adresa API (webhooky, REST)."
  value       = aws_lb.this.dns_name
}

output "ecr_repository_url" {
  description = "Kam CI pushuje image."
  value       = aws_ecr_repository.this.repository_url
}

output "ecs_cluster_name" {
  description = "Jméno ECS clusteru."
  value       = aws_ecs_cluster.this.name
}

output "ecs_service_names" {
  description = "Služby k force-new-deployment po pushi image."
  value = {
    api    = aws_ecs_service.api.name
    worker = aws_ecs_service.worker.name
  }
}

output "database_secret_arn" {
  description = "Secrets Manager secret s přihlašovacími údaji k RDS."
  value       = aws_secretsmanager_secret.database.arn
}

output "vault_kek_uri" {
  description = "URI KEK klíče pro credential vault (ADR 0005)."
  value       = "aws-kms://${aws_kms_key.vault.arn}"
}
