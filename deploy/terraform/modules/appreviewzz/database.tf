resource "aws_db_subnet_group" "this" {
  name       = local.name
  subnet_ids = [for subnet in aws_subnet.private : subnet.id]
  tags       = local.tags
}

resource "random_password" "database" {
  length  = 32
  special = false
}

resource "aws_db_instance" "this" {
  identifier     = local.name
  engine         = "postgres"
  engine_version = "17"
  instance_class = var.db_instance_class

  allocated_storage     = var.db_allocated_storage
  max_allocated_storage = var.db_allocated_storage * 5
  storage_type          = "gp3"
  storage_encrypted     = true

  db_name  = "appreviewzz"
  username = "appreviewzz"
  password = random_password.database.result

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.database.id]
  publicly_accessible    = false

  backup_retention_period   = var.db_backup_retention_days
  copy_tags_to_snapshot     = true
  deletion_protection       = var.db_deletion_protection
  skip_final_snapshot       = !var.db_deletion_protection
  final_snapshot_identifier = var.db_deletion_protection ? "${local.name}-final" : null

  auto_minor_version_upgrade = true
  apply_immediately          = var.environment != "prod"

  enabled_cloudwatch_logs_exports = ["postgresql"]

  tags = local.tags
}

# Heslo se do task definice nedostane jako plaintext — kontejner si ho vyzvedne přes secret ARN.
resource "aws_secretsmanager_secret" "database" {
  name        = "${local.name}/database"
  description = "Přihlašovací údaje k RDS pro ${local.name}"
  tags        = local.tags
}

resource "aws_secretsmanager_secret_version" "database" {
  secret_id = aws_secretsmanager_secret.database.id
  secret_string = jsonencode({
    username = aws_db_instance.this.username
    password = random_password.database.result
    host     = aws_db_instance.this.address
    port     = aws_db_instance.this.port
    dbname   = aws_db_instance.this.db_name
    jdbc_url = "jdbc:postgresql://${aws_db_instance.this.endpoint}/${aws_db_instance.this.db_name}"
  })
}
