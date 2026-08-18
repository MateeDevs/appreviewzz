# 0003 — PostgreSQL + Exposed + Flyway (ne jOOQ)

- **Stav:** Přijato
- **Datum:** 2026-08-18
- **Kontext fáze:** F0
- **Řeší otevřenou otázku:** plán §13.3 (Exposed vs. jOOQ)

## Kontext

Datový model je malý a stabilní (organizace, appky, credentials, recenze, odpovědi, snapshoty
ratingů). Postgres už drží i scheduler ([0004](0004-db-scheduler.md)) a bude držet historii
ratingů z n8n. Otázka byla jen, čím nad ním psát dotazy.

## Rozhodnutí

- **PostgreSQL 16+** jako jediné úložiště.
- **Flyway** pro verzované SQL migrace (`server/persistence/src/main/resources/db/migration`),
  spouštěné při startu role `api`; worker je nepouští, aby dvě instance nezávodily.
- **Exposed 1.x** (JetBrains) jako DSL vrstva.

## Důsledky

- Žádný codegen krok v buildu — schéma se mění v SQL, Exposed tabulky se dopisují ručně.
  Cena: rozjezd mezi SQL a Kotlinem není hlídaný kompilátorem, hlídají ho Testcontainers testy.
- Migrace jsou lineární a forward-only; rollback = nová migrace.
- Exposed 1.x je čerstvé API (oproti 0.x se mění balíčky) — upgrady chtějí čtení changelogu.

## Zvažované alternativy

- **jOOQ.** Silnější typová vazba na reálné schéma a lepší podpora složitého SQL. Ale vyžaduje
  codegen proti živé DB v buildu (nebo commitnuté vygenerované zdroje) a komerční licenci pro
  ne-open-source DB. Pro tenhle rozsah dotazů to nevyváží režii.
- **Holé JDBC + ruční mapování.** Nejmíň závislostí, nejvíc opakovaného kódu.
- **Hibernate/JPA.** Nechceme lazy loading, cache vrstvu a překvapení v generovaném SQL.
