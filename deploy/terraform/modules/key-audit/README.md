# Modul `key-audit`

Auditní stopa a alarmy nad vault klíčem (F1.9). Klíč sám nikomu nic nepoví — kdo a kolikrát
ho použil, ví jenom CloudTrail. Modul tu stopu zapne, doručí ji do CloudWatch Logs a postaví
nad ní tři alarmy. Navazuje na `vault-kms`, jehož klíč a IAM uživatele bere jako vstup.

| Zdroj | Poznámka |
|---|---|
| `aws_cloudtrail` | jeden region, management events včetně čtecích (`Decrypt` je čtecí operace), validace souborů |
| `aws_s3_bucket` | `appreviewzz-<env>-cloudtrail` — archiv, verzování, SSE-S3, TLS-only, lifecycle |
| `aws_cloudwatch_log_group` | `/aws/cloudtrail/appreviewzz-<env>`, retence `log_retention_days` (90) |
| `aws_cloudwatch_log_metric_filter` ×3 | objem rozbalování, cizí principal, odmítnutá volání |
| `aws_cloudwatch_metric_alarm` ×3 | totéž jako alarmy, akce → SNS |
| `aws_sns_topic` (+ odběr) | e-mail z `alarm_email`; odběr je nutné potvrdit klikem |

## Alarmy

| Alarm | Kdy se ozve | Co to znamená |
|---|---|---|
| `…-vault-unwrap-volume` | `Decrypt` nad vault klíčem > `unwrap_threshold_per_hour` (150) za hodinu | rozbitá cache DEK, nebo někdo hromadně odemyká credentials |
| `…-vault-foreign-principal` | envelope operaci nad klíčem udělal někdo jiný než IAM uživatel aplikace | únik access keye, nebo ruční sáhnutí na klíč z konzole |
| `…-vault-denied` | KMS odmítlo volání (`AccessDenied` a spol.) | zkažená konfigurace práv, nebo někdo zkouší, kam dosáhne |

Práh objemu vychází z toho, že aplikace drží rozbalený DEK v paměti pět minut. Legitimní
provoz proto neumí překročit **12 volání za hodinu na organizaci** a výchozích 150 unese deset
klientů se dvěma appkami i s ratings pipeline. Vzorec, tabulka a postup, jak práh nastavit
podle naměřených dat, jsou v [runbooku](../../../../docs/runbooks/vault-klic-alarm.md).

Alarm na objem je hlídač zdraví systému, ne pojistka proti exfiltraci: útočník s uniklým
access keyem potřebuje na všechny klienty jen tolik volání, kolik je organizací, a v objemu
je neviditelný. Na to je druhý alarm — cizí principal, práh 1.

## Náklady

První kopie management events v CloudTrailu je zdarma, platí se až uložení. Reálně jednotky
centů měsíčně za S3 a CloudWatch Logs plus **$0,30 za tři alarmy**. Retenci logů (a tím
i účet) škrtí `log_retention_days` a `trail_retention_days`.

## Co modul schválně nedělá

- **Nešifruje SNS topic.** CloudWatch alarm neumí publikovat do topicu zašifrovaného AWS
  managed klíčem a vlastní klíč jen kvůli e-mailu s počtem volání nedává smysl.
- **Nehlídá GuardDuty ani Security Hub.** Na jeden účet s jedním klíčem je to dělo na vrabce;
  až bude produkce ve vlastním účtu (F5), stojí za to se k tomu vrátit.
- **Nespoléhá se jen na cloud.** Aplikace počítá volání KEK i sama do metriky
  `appreviewzz_vault_kek_unwrap_total` — self-host bez CloudTrailu tak má stejný signál.
