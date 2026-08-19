output "vault_kek_uri" {
  description = "Do konfigurace aplikace jako VAULT_KEK_URI."
  value       = module.vault.kek_uri
}

output "vault_key_alias" {
  value = module.vault.key_alias
}

output "app_iam_user_name" {
  description = "Pro tohoto uživatele se v konzoli vygeneruje access key."
  value       = module.vault.app_iam_user_name
}

output "backup_target" {
  description = "Do konfigurace aplikace jako BACKUP_TARGET."
  value       = module.backups.backup_target
}

output "alarm_topic_arn" {
  description = "SNS topic s alarmy nad vault klíčem. Odběr e-mailem je potřeba potvrdit klikem v došlé zprávě."
  value       = module.key_audit.alarm_topic_arn
}

output "audit_log_group_name" {
  description = "CloudWatch Logs skupina, ve které se dohledává použití vault klíče."
  value       = module.key_audit.log_group_name
}
