# Terraform

Infrastruktura pro cloudovou instanci appreviewzz. Modul `modules/appreviewzz`
je celý stack; `envs/dev` a později `envs/prod` jsou tenké obálky s parametry.

> **Stav: nenasazeno.** Kód vznikl ve F0, ale zatím nikdy neproběhl `terraform apply` —
> na stroji, kde vznikal, nebyl AWS účet ani přístup. První `plan` proto berte jako
> revizi, ne jako hotovou věc.

## Co stack staví

- VPC se dvěma veřejnými (ALB, ECS tasky) a dvěma privátními subnety (RDS)
- ECS Fargate cluster, služby `api` (za ALB) a `worker` — [jeden image, dvě role](../../docs/adr/0006-jeden-image-dve-role.md)
- RDS PostgreSQL 17, šifrovaná, privátní, s automatickými zálohami
- KMS CMK pro credential vault + IAM task role s právem jen na `GenerateDataKey`/`Decrypt`
- Secrets Manager s přihlašovacími údaji k databázi (kontejner je čte přes secret ARN,
  v task definici není plaintext)
- ECR s immutable tagy, scan on push a lifecycle politikou
- CloudWatch log groups

## Bootstrap (jednorázově na účet)

Stav se drží v S3, ale bucket samotný nemůže vzniknout ve stavu, který v něm má být uložený.
Založte ho ručně:

```bash
aws s3api create-bucket --bucket matee-terraform-state --region eu-central-1 \
  --create-bucket-configuration LocationConstraint=eu-central-1
aws s3api put-bucket-versioning --bucket matee-terraform-state \
  --versioning-configuration Status=Enabled
aws s3api put-public-access-block --bucket matee-terraform-state \
  --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
```

Zamykání používá nativní S3 lockfile (`use_lockfile`), DynamoDB tabulka není potřeba.

## Nasazení dev

```bash
cd deploy/terraform/envs/dev
cp backend.hcl.example backend.hcl     # doplň bucket
terraform init -backend-config=backend.hcl
terraform plan
terraform apply
```

Pořadí při úplně prvním běhu: `apply` založí ECR ještě prázdné, takže ECS služby se
nerozeběhnou, dokud CI nepushne první image. Buď spusťte `apply` s `-target=module.appreviewzz.aws_ecr_repository.this`,
pushněte image a pak dojeďte zbytek, nebo nechte služby chvíli v selhávajícím stavu.

## Deploy z CI

`.github/workflows/deploy-dev.yml` se autentizuje přes GitHub OIDC (žádné dlouhožijící AWS
klíče v repozitáři), pushne image tagovaný git SHA do ECR a přepíše `image_tag`.
Potřebuje IAM roli s trust policy na `token.actions.githubusercontent.com` a repozitářovou
proměnnou `AWS_DEPLOY_ROLE_ARN`.

## Co ještě chybí (F5)

- WAF před ALB s rate limity na webhook endpointy
- CloudFront + S3 pro konzoli
- ACM certifikát a doména (`certificate_arn` je zatím `null` → jen HTTP)
- Alerting (CloudWatch alarmy → Slack), OTel collector
- `envs/prod` s deletion protection a většími instancemi
