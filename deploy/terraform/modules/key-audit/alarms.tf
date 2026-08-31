locals {
  metric_namespace = "appreviewzz/vault"

  # Filtry čtou JSON událost z CloudTrailu. `resources[0].ARN` je klíč, nad kterým volání
  # proběhlo — díky němu alarmy nereagují na jiné klíče v účtu (třeba aws/sns).
  key_event = "($.eventSource = \"kms.amazonaws.com\") && ($.resources[0].ARN = \"${var.vault_key_arn}\")"
}

# --- kam alarmy chodí ---------------------------------------------------------------

# Bez šifrování topicu schválně: CloudWatch alarmy neumí publikovat do topicu zašifrovaného
# AWS managed klíčem (na jeho key policy se nedá sáhnout) a vlastní KMS klíč jen kvůli
# doručení e-mailu s textem „proběhlo N rozbalení" nedává smysl. Obsah zprávy tajný není.
resource "aws_sns_topic" "alarms" {
  name = "${local.name}-alarms"
  tags = local.tags
}

resource "aws_sns_topic_policy" "alarms" {
  arn = aws_sns_topic.alarms.arn

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AllowCloudWatchAlarms"
        Effect    = "Allow"
        Principal = { Service = "cloudwatch.amazonaws.com" }
        Action    = "SNS:Publish"
        Resource  = aws_sns_topic.alarms.arn
        Condition = {
          StringEquals = { "AWS:SourceOwner" = data.aws_caller_identity.current.account_id }
        }
      },
      {
        Sid       = "AllowAccountAdministration"
        Effect    = "Allow"
        Principal = { AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root" }
        Action = [
          "SNS:GetTopicAttributes",
          "SNS:SetTopicAttributes",
          "SNS:AddPermission",
          "SNS:RemovePermission",
          "SNS:DeleteTopic",
          "SNS:Subscribe",
          "SNS:ListSubscriptionsByTopic",
          "SNS:Publish",
          "SNS:Receive"
        ]
        Resource = aws_sns_topic.alarms.arn
      },
    ]
  })
}

# Odběr je potřeba potvrdit klikem v e-mailu; do té doby je ve stavu „pending confirmation"
# a alarm nikam nedojde.
resource "aws_sns_topic_subscription" "email" {
  count = var.alarm_email == null ? 0 : 1

  topic_arn = aws_sns_topic.alarms.arn
  protocol  = "email"
  endpoint  = var.alarm_email
}

# --- objem rozbalování --------------------------------------------------------------

resource "aws_cloudwatch_log_metric_filter" "unwrap" {
  name           = "${local.name}-vault-unwrap"
  log_group_name = aws_cloudwatch_log_group.trail.name
  pattern        = "{ ${local.key_event} && ($.eventName = \"Decrypt\") }"

  metric_transformation {
    name          = "VaultKeyUnwrap"
    namespace     = local.metric_namespace
    value         = "1"
    default_value = "0"
    unit          = "Count"
  }
}

# Aplikace drží rozbalený DEK v paměti pět minut, takže víc než 12 volání za hodinu na
# organizaci legitimní provoz vůbec neumí. Práh je proto nad stropem cache pro deset klientů:
# nefiruje na provoz, ale na rozbitou cache, restart smyčku workeru nebo někoho, kdo si
# credentials prochází skriptem. Cílenou exfiltraci (jednotky volání) chytá až alarm níž.
resource "aws_cloudwatch_metric_alarm" "unwrap_volume" {
  alarm_name          = "${local.name}-vault-unwrap-volume"
  alarm_description   = "Neobvyklý objem rozbalování vault klíče (kms:Decrypt). Postup: docs/runbooks/vault-klic-alarm.md"
  namespace           = local.metric_namespace
  metric_name         = aws_cloudwatch_log_metric_filter.unwrap.metric_transformation[0].name
  statistic           = "Sum"
  period              = 3600
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = var.unwrap_threshold_per_hour
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]
  tags                = local.tags
}

# --- kdo klíč používá ---------------------------------------------------------------

resource "aws_cloudwatch_log_metric_filter" "foreign_principal" {
  name           = "${local.name}-vault-foreign-principal"
  log_group_name = aws_cloudwatch_log_group.trail.name

  # Envelope operace smí dělat jedině aplikace. Čtecí volání (DescribeKey, GetKeyPolicy)
  # sem schválně nepatří — ta dělá i terraform při každém `apply`.
  pattern = join(" && ", [
    "{ ${local.key_event}",
    "(($.eventName = \"Decrypt\") || ($.eventName = \"GenerateDataKey\") || ($.eventName = \"GenerateDataKeyWithoutPlaintext\") || ($.eventName = \"ReEncrypt\"))",
    "($.userIdentity.arn != \"${var.app_iam_user_arn}\") }",
  ])

  metric_transformation {
    name          = "VaultKeyForeignPrincipal"
    namespace     = local.metric_namespace
    value         = "1"
    default_value = "0"
    unit          = "Count"
  }
}

resource "aws_cloudwatch_metric_alarm" "foreign_principal" {
  alarm_name          = "${local.name}-vault-foreign-principal"
  alarm_description   = "Vault klíč použil někdo jiný než aplikace. Postup: docs/runbooks/vault-klic-alarm.md"
  namespace           = local.metric_namespace
  metric_name         = aws_cloudwatch_log_metric_filter.foreign_principal.metric_transformation[0].name
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  tags                = local.tags
}

# --- odmítnutá volání ---------------------------------------------------------------

# Bez vazby na konkrétní klíč: u odmítnutého volání CloudTrail zdroj často neuvede a v tomhle
# účtu je KMS stejně jenom jedno. Odmítnutí znamená buď rozbitou konfiguraci, nebo někoho,
# kdo zkouší, kam až dosáhne.
resource "aws_cloudwatch_log_metric_filter" "denied" {
  name           = "${local.name}-vault-denied"
  log_group_name = aws_cloudwatch_log_group.trail.name

  pattern = join(" && ", [
    "{ ($.eventSource = \"kms.amazonaws.com\")",
    "(($.errorCode = \"AccessDenied*\") || ($.errorCode = \"*UnauthorizedOperation\") || ($.errorCode = \"*NotAuthorized*\")) }",
  ])

  metric_transformation {
    name          = "VaultKeyDenied"
    namespace     = local.metric_namespace
    value         = "1"
    default_value = "0"
    unit          = "Count"
  }
}

resource "aws_cloudwatch_metric_alarm" "denied" {
  alarm_name          = "${local.name}-vault-denied"
  alarm_description   = "Odmítnuté volání KMS — chybí právo, nebo někdo zkouší cizí klíč. Postup: docs/runbooks/vault-klic-alarm.md"
  namespace           = local.metric_namespace
  metric_name         = aws_cloudwatch_log_metric_filter.denied.metric_transformation[0].name
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  tags                = local.tags
}
