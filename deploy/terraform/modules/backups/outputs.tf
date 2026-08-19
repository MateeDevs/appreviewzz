output "bucket_name" {
  description = "Jméno bucketu se zálohami."
  value       = aws_s3_bucket.backups.bucket
}

output "backup_target" {
  description = "Hodnota do konfigurace aplikace (BACKUP_TARGET)."
  value       = "s3://${aws_s3_bucket.backups.bucket}/${var.prefix}"
}
