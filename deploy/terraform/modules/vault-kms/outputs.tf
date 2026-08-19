output "kek_uri" {
  description = "Hodnota do konfigurace aplikace (VAULT_KEK_URI)."
  value       = "aws-kms://${aws_kms_key.vault.arn}"
}

output "key_arn" {
  description = "ARN vault klíče."
  value       = aws_kms_key.vault.arn
}

output "key_alias" {
  description = "Alias klíče — čitelnější než ARN, použitelný v konzoli."
  value       = aws_kms_alias.vault.name
}

output "app_iam_user_name" {
  description = "IAM uživatel, pro kterého se v konzoli vygeneruje access key."
  value       = aws_iam_user.app.name
}

output "app_iam_user_arn" {
  description = "ARN uživatele aplikace — modul key-audit podle něj pozná cizí volání nad klíčem."
  value       = aws_iam_user.app.arn
}
