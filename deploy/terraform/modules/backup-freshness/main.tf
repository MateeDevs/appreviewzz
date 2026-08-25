locals {
  name = "appreviewzz-${var.environment}"

  metric_namespace = "Appreviewzz/Backups"
  metric_name      = "BackupAgeHours"

  tags = merge(
    {
      Project     = "appreviewzz"
      Environment = var.environment
      ManagedBy   = "terraform"
    },
    var.tags,
  )
}

data "archive_file" "source" {
  type        = "zip"
  source_file = "${path.module}/src/index.py"
  output_path = "${path.module}/.build/backup-freshness.zip"
}

# Kontrola smí přečíst jen výpis vlastního prefixu a zapsat jednu metriku. Na obsah dumpů
# nesahá — a nemá jak, chybí jí GetObject.
resource "aws_iam_role" "check" {
  name = "${local.name}-backup-freshness"
  tags = local.tags

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "check" {
  name = "list-backups-and-publish-metric"
  role = aws_iam_role.check.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = "s3:ListBucket"
        Resource = "arn:aws:s3:::${var.bucket_name}"
        Condition = {
          StringLike = { "s3:prefix" = ["${var.prefix}/*", var.prefix] }
        }
      },
      {
        Effect   = "Allow"
        Action   = "cloudwatch:PutMetricData"
        Resource = "*"
        Condition = {
          StringEquals = { "cloudwatch:namespace" = local.metric_namespace }
        }
      },
      {
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = "${aws_cloudwatch_log_group.check.arn}:*"
      },
    ]
  })
}

# Skupina vzniká terraformem, ne Lambdou samotnou — jinak by neměla retenci a logy by
# se v účtu hromadily napořád.
resource "aws_cloudwatch_log_group" "check" {
  name              = "/aws/lambda/${local.name}-backup-freshness"
  retention_in_days = var.log_retention_days
  tags              = local.tags
}

resource "aws_lambda_function" "check" {
  function_name    = "${local.name}-backup-freshness"
  role             = aws_iam_role.check.arn
  runtime          = "python3.12"
  handler          = "index.handler"
  filename         = data.archive_file.source.output_path
  source_code_hash = data.archive_file.source.output_base64sha256
  timeout          = 30
  tags             = local.tags

  environment {
    variables = {
      BUCKET           = var.bucket_name
      PREFIX           = var.prefix
      METRIC_NAMESPACE = local.metric_namespace
      METRIC_NAME      = local.metric_name
      ENVIRONMENT      = var.environment
    }
  }

  depends_on = [aws_cloudwatch_log_group.check]
}

resource "aws_cloudwatch_event_rule" "daily" {
  name                = "${local.name}-backup-freshness"
  description         = "Denní kontrola, že v bucketu leží čerstvý dump databáze."
  schedule_expression = var.schedule_expression
  tags                = local.tags
}

resource "aws_cloudwatch_event_target" "daily" {
  rule = aws_cloudwatch_event_rule.daily.name
  arn  = aws_lambda_function.check.arn
}

resource "aws_lambda_permission" "events" {
  statement_id  = "AllowExecutionFromEventBridge"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.check.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.daily.arn
}

# `breaching` u chybějících dat je tu to podstatné: alarm pak nehlídá jen starou zálohu,
# ale i to, že kontrola sama přestala běžet. Tiše mrtvý hlídač je horší než žádný.
resource "aws_cloudwatch_metric_alarm" "stale" {
  alarm_name          = "${local.name}-backup-stale"
  alarm_description   = "Poslední záloha v s3://${var.bucket_name}/${var.prefix} je starší než ${var.max_age_hours} h (nebo kontrola neběží)."
  namespace           = local.metric_namespace
  metric_name         = local.metric_name
  dimensions          = { Environment = var.environment }
  statistic           = "Maximum"
  period              = 86400
  evaluation_periods  = 1
  threshold           = var.max_age_hours
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [var.alarm_topic_arn]
  ok_actions          = [var.alarm_topic_arn]
  tags                = local.tags
}
