data "aws_caller_identity" "current" {}

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
}

# KEK pro credential vault (ADR 0005). Klíč neopustí KMS — aplikace umí jen požádat
# o zabalení/rozbalení DEK a každá taková žádost je vidět v CloudTrailu.
resource "aws_kms_key" "vault" {
  description             = "${local.name} credential vault KEK"
  enable_key_rotation     = true
  deletion_window_in_days = 30

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AllowAccountAdministration"
        Effect    = "Allow"
        Principal = { AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root" }
        Action    = "kms:*"
        Resource  = "*"
      },
      {
        Sid       = "AllowApplicationEnvelopeOperations"
        Effect    = "Allow"
        Principal = { AWS = aws_iam_user.app.arn }
        Action = [
          "kms:GenerateDataKey",
          "kms:Decrypt",
          "kms:DescribeKey",
        ]
        Resource = "*"
      },
    ]
  })

  tags = local.tags
}

resource "aws_kms_alias" "vault" {
  name          = "alias/${local.name}-vault"
  target_key_id = aws_kms_key.vault.key_id
}

# Aplikace běží mimo AWS, takže se nemůže autentizovat instance rolí a potřebuje
# vlastního IAM uživatele. Nesmí umět nic než envelope operace nad tímhle jedním klíčem.
resource "aws_iam_user" "app" {
  name = "${local.name}-app"
  tags = local.tags
}

resource "aws_iam_user_policy" "app" {
  name = "vault-envelope-only"
  user = aws_iam_user.app.name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "kms:GenerateDataKey",
          "kms:Decrypt",
          "kms:DescribeKey",
        ]
        Resource = [aws_kms_key.vault.arn]
      },
    ]
  })
}
