output "alarm_topic_arn" {
  description = "SNS topic, na který chodí alarmy. Odběr e-mailem je potřeba potvrdit klikem."
  value       = aws_sns_topic.alarms.arn
}

output "log_group_name" {
  description = "CloudWatch Logs skupina s auditní stopou — v ní se dohledává, kdo klíč použil."
  value       = aws_cloudwatch_log_group.trail.name
}

output "trail_bucket_name" {
  description = "Bucket se surovými CloudTrail soubory (archiv pro zpětné dohledávání)."
  value       = aws_s3_bucket.trail.bucket
}

output "alarm_names" {
  description = "Jména alarmů — hodí se pro ruční ověření v konzoli."
  value = [
    aws_cloudwatch_metric_alarm.unwrap_volume.alarm_name,
    aws_cloudwatch_metric_alarm.foreign_principal.alarm_name,
    aws_cloudwatch_metric_alarm.denied.alarm_name,
  ]
}
