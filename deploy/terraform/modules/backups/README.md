# Modul `backups`

Bucket pro logické zálohy databáze (`pg_dump -Fc`) a oprávnění, kterými do něj smí sahat
aplikace. Navazuje na modul `vault-kms` — používá jeho IAM uživatele, nový nezakládá.

| | |
|---|---|
| Bucket | `appreviewzz-<env>-backups`, verzování, SSE-S3, blokovaný veřejný přístup, TLS-only |
| Prefix | `postgres/` — aplikace nemá právo sahat jinam |
| Lifecycle | maže objekty po `retention_days` (výchozí 35) a staré verze po 7 dnech |
| IAM | `PutObject`, `GetObject`, `DeleteObject` pod prefixem, `ListBucket` na prefix |

Retence je schválně delší než `BACKUP_RETENTION_DAYS` v aplikaci: primárně uklízí aplikace,
lifecycle je pojistka pro případ, že by přestala běžet.

Výstup `backup_target` se vyplňuje do proměnné `BACKUP_TARGET` (Coolify).
Postup obnovy je v [runbooku](../../../../docs/runbooks/zalohy-a-obnova.md).
