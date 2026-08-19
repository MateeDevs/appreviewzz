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

# Bucket na dumpy databáze (F1.8). Dump obsahuje osobní údaje autorů recenzí a credentials
# klientů — ty sice zabalené vault klíčem, ale bucket se i tak chová jako citlivé úložiště:
# žádný veřejný přístup, šifrování na serveru, jen HTTPS, verzování proti přepsání.
resource "aws_s3_bucket" "backups" {
  bucket = "${local.name}-backups"
  tags   = local.tags
}

resource "aws_s3_bucket_public_access_block" "backups" {
  bucket = aws_s3_bucket.backups.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "backups" {
  bucket = aws_s3_bucket.backups.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

# SSE-S3, ne SSE-KMS: dumpy nesou credentials už zabalené naším vault klíčem a provoz přes KMS
# by navíc zašuměl metriku objemu rozbalování klíčů, na které stojí CloudTrail alarm (F1.9).
resource "aws_s3_bucket_server_side_encryption_configuration" "backups" {
  bucket = aws_s3_bucket.backups.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
    bucket_key_enabled = true
  }
}

# Verzování drží starou verzi i po přepsání nebo smazání — chrání proti chybě v aplikaci
# i proti tomu, kdo by se ke klíči aplikace dostal a zálohy chtěl zahladit.
resource "aws_s3_bucket_versioning" "backups" {
  bucket = aws_s3_bucket.backups.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "backups" {
  bucket     = aws_s3_bucket.backups.id
  depends_on = [aws_s3_bucket_versioning.backups]

  rule {
    id     = "expire-old-backups"
    status = "Enabled"

    filter {
      prefix = "${var.prefix}/"
    }

    expiration {
      days = var.retention_days
    }

    noncurrent_version_expiration {
      noncurrent_days = 7
    }
  }

  rule {
    id     = "abort-incomplete-uploads"
    status = "Enabled"

    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

resource "aws_s3_bucket_policy" "tls_only" {
  bucket = aws_s3_bucket.backups.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "DenyInsecureTransport"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource = [
          aws_s3_bucket.backups.arn,
          "${aws_s3_bucket.backups.arn}/*",
        ]
        Condition = {
          Bool = { "aws:SecureTransport" = "false" }
        }
      },
    ]
  })
}

# Aplikace smí se zálohami pracovat jen pod svým prefixem a nesmí sahat na nastavení bucketu.
# Právo mazat potřebuje kvůli retenci — lifecycle je pojistka, ne primární úklid.
resource "aws_iam_user_policy" "app_backups" {
  name = "backups-read-write"
  user = var.app_iam_user_name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ListOwnPrefix"
        Effect   = "Allow"
        Action   = "s3:ListBucket"
        Resource = aws_s3_bucket.backups.arn
        Condition = {
          StringLike = { "s3:prefix" = ["${var.prefix}/*", "${var.prefix}"] }
        }
      },
      {
        Sid      = "ReadWriteBackups"
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"]
        Resource = "${aws_s3_bucket.backups.arn}/${var.prefix}/*"
      },
    ]
  })
}
