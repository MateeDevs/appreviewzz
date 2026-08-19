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
