data "aws_caller_identity" "current" {}

# KEK pro credential vault (ADR 0005). Klíč neopustí KMS — aplikace volá jen
# GenerateDataKey/Decrypt a každý unwrap je vidět v CloudTrailu.
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
        Sid       = "AllowTaskRoleEnvelopeOperations"
        Effect    = "Allow"
        Principal = { AWS = aws_iam_role.task.arn }
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
