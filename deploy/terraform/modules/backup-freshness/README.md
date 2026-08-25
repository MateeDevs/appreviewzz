# Modul `backup-freshness`

Denní kontrola, že v bucketu se zálohami **reálně leží čerstvý dump**.

Aplikace vystavuje metriku `appreviewzz_backup_last_success_age_seconds`, ta je ale na
neveřejném portu a hlavně říká jen to, že job doběhl. Při obnově záleží na něčem jiném:
jestli objekt dosedl mimo ten stroj. Tahle kontrola se proto ptá S3, ne aplikace, a běží
jinde než ona — takže přežije i stav, kdy je hostitel po smrti.

## Jak to funguje

EventBridge jednou denně (výchozí 06:00 UTC) spustí Lambdu, ta projde prefix v bucketu,
najde nejnovější objekt a zapíše jeho stáří v hodinách jako metriku
`Appreviewzz/Backups / BackupAgeHours`. Alarm nad metrikou hlásí do SNS topicu z modulu
`key-audit` — poplachy tak chodí na jedno místo.

Prázdný bucket se hlásí jako stáří jednoho roku, ne jako nula: žádná záloha je nejhorší
možný stav, ne nejlepší.

`treat_missing_data = "breaching"` znamená, že alarm zazvoní i tehdy, když přestane běžet
sama kontrola. Hlídač, o kterém nevíš, že umřel, je horší než žádný.

## Použití

```hcl
module "backup_freshness" {
  source = "../../modules/backup-freshness"

  environment     = "prod"
  bucket_name     = module.backups.bucket_name
  alarm_topic_arn = module.key_audit.alarm_topic_arn
}
```

## Ověření

```bash
aws lambda invoke --function-name appreviewzz-prod-backup-freshness /dev/stdout
```

Vrátí `age_hours`. Pro zkoušku alarmu se dá dočasně snížit `max_age_hours` na `0`
a počkat na další vyhodnocení.

## Náklady

Jedno spuštění denně, jedna metrika, jeden alarm — v rámci free tier, tedy jednotky centů
za alarm ($0,10/měs).
