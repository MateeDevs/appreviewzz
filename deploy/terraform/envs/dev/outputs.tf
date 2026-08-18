output "alb_dns_name" {
  value = module.appreviewzz.alb_dns_name
}

output "ecr_repository_url" {
  value = module.appreviewzz.ecr_repository_url
}

output "ecs_cluster_name" {
  value = module.appreviewzz.ecs_cluster_name
}

output "ecs_service_names" {
  value = module.appreviewzz.ecs_service_names
}

output "vault_kek_uri" {
  value = module.appreviewzz.vault_kek_uri
}
