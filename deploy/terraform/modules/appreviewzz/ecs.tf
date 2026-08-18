resource "aws_ecs_cluster" "this" {
  name = local.name

  setting {
    name  = "containerInsights"
    value = var.environment == "prod" ? "enabled" : "disabled"
  }

  tags = local.tags
}

resource "aws_cloudwatch_log_group" "api" {
  name              = "/ecs/${local.name}/api"
  retention_in_days = var.log_retention_days
  tags              = local.tags
}

resource "aws_cloudwatch_log_group" "worker" {
  name              = "/ecs/${local.name}/worker"
  retention_in_days = var.log_retention_days
  tags              = local.tags
}

locals {
  image = "${aws_ecr_repository.this.repository_url}:${var.image_tag}"

  common_environment = [
    { name = "APPREVIEWZZ_ENV", value = var.environment },
    { name = "SERVER_PORT", value = tostring(local.container_port) },
    { name = "DATABASE_URL", value = "jdbc:postgresql://${aws_db_instance.this.endpoint}/${aws_db_instance.this.db_name}" },
    { name = "VAULT_KEK_URI", value = "aws-kms://${aws_kms_key.vault.arn}" },
  ]

  common_secrets = [
    { name = "DATABASE_USER", valueFrom = "${aws_secretsmanager_secret.database.arn}:username::" },
    { name = "DATABASE_PASSWORD", valueFrom = "${aws_secretsmanager_secret.database.arn}:password::" },
  ]
}

resource "aws_ecs_task_definition" "api" {
  family                   = "${local.name}-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([
    {
      name      = "api"
      image     = local.image
      essential = true

      portMappings = [
        { containerPort = local.container_port, protocol = "tcp" },
      ]

      environment = concat(local.common_environment, [
        { name = "APPREVIEWZZ_ROLE", value = "api" },
        { name = "DATABASE_MIGRATE_ON_START", value = "true" },
      ])
      secrets = local.common_secrets

      healthCheck = {
        command     = ["CMD-SHELL", "curl -fsS http://127.0.0.1:${local.container_port}/health/live || exit 1"]
        interval    = 15
        timeout     = 5
        retries     = 3
        startPeriod = 60
      }

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.api.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "api"
        }
      }
    },
  ])

  tags = local.tags
}

resource "aws_ecs_task_definition" "worker" {
  family                   = "${local.name}-worker"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([
    {
      name      = "worker"
      image     = local.image
      essential = true

      environment = concat(local.common_environment, [
        { name = "APPREVIEWZZ_ROLE", value = "worker" },
        # Migrace vlastní role api — dvě instance by se přetahovaly o Flyway zámek.
        { name = "DATABASE_MIGRATE_ON_START", value = "false" },
      ])
      secrets = local.common_secrets

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.worker.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "worker"
        }
      }
    },
  ])

  tags = local.tags
}

resource "aws_ecs_service" "api" {
  name            = "${local.name}-api"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.api.arn
  desired_count   = var.api_desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = [for subnet in aws_subnet.public : subnet.id]
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.api.arn
    container_name   = "api"
    container_port   = local.container_port
  }

  health_check_grace_period_seconds  = 90
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  depends_on = [aws_lb_listener.http]

  tags = local.tags
}

resource "aws_ecs_service" "worker" {
  name            = "${local.name}-worker"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.worker.arn
  desired_count   = var.worker_desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = [for subnet in aws_subnet.public : subnet.id]
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = true
  }

  # Worker nemá rolling window přes ALB; při deployi se krátce nepřekrývá.
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100

  tags = local.tags
}
