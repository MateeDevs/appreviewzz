# Architecture Decision Records

Krátké záznamy o rozhodnutích, která se těžko vracejí. Formát: kontext → rozhodnutí → důsledky.

| # | Rozhodnutí | Stav |
|---|---|---|
| [0001](0001-zaznamenavame-adr.md) | Zaznamenáváme architektonická rozhodnutí | Přijato |
| [0002](0002-modularni-monolit-kotlin-ktor.md) | Modulární monolit v Kotlin/Ktor | Přijato |
| [0003](0003-postgres-exposed-flyway.md) | PostgreSQL + Exposed + Flyway (ne jOOQ) | Přijato |
| [0004](0004-db-scheduler.md) | db-scheduler nad Postgresem místo fronty | Přijato |
| [0005](0005-envelope-encryption.md) | Envelope encryption s DEK per organizaci | Přijato |
| [0006](0006-jeden-image-dve-role.md) | Jeden image, role `api` a `worker` | Přijato |
| [0007](0007-agpl-3.md) | Licence AGPL-3.0 | Přijato |
| [0008](0008-hosting-coolify-kek-v-kms.md) | Aplikace na Coolify, KEK zůstává v AWS KMS | Přijato |
| [0009](0009-domenove-schema-tenancy.md) | Doménové schéma: `org_id` všude, enumy jako text s CHECK | Přijato |

Nové ADR: zkopíruj strukturu z 0001, další číslo v pořadí, přidej řádek do tabulky.
