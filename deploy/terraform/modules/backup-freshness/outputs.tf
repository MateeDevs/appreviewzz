output "function_name" {
  description = "Jméno kontroly — pro ruční spuštění při ověřování (`aws lambda invoke`)."
  value       = aws_lambda_function.check.function_name
}

output "alarm_name" {
  description = "Alarm nad stářím poslední zálohy."
  value       = aws_cloudwatch_metric_alarm.stale.alarm_name
}
