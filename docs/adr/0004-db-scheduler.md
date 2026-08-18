# 0004 — db-scheduler nad Postgresem místo fronty

- **Stav:** Přijato
- **Datum:** 2026-08-18
- **Kontext fáze:** F0

## Kontext

Potřebujeme periodické joby (ingest recenzí po 30 minutách, denní snapshoty ratingů, health
kontroly credentials) i jednorázové úlohy (publikace odpovědi do storu, retry po chybě).
Musí to fungovat i ve dvou instancích workeru bez toho, aby se ingest spustil dvakrát —
a musí to fungovat u self-hostera, který nemá Redis ani cloud služby.

## Rozhodnutí

**db-scheduler** (`com.github.kagkarlsson:db-scheduler`) nad stejným Postgresem. Recurring i
one-shot tasky, zamykání přes `SELECT … FOR UPDATE`, retry a backoff v knihovně.

Joby běží výhradně v roli `worker` ([0006](0006-jeden-image-dve-role.md)).

## Důsledky

- Nula dalších infrastrukturních závislostí — to je hlavní důvod. `docker compose up` = app + DB.
- Škálování workerů je horizontální a bezpečné: víc instancí si úlohy rozebere zámky.
- Zatížení DB roste s frekvencí pollingu; při desítkách tisíc úloh za minutu by to nestačilo —
  tenhle systém je o třídy níž.
- Zpracování je „at least once“ — každý task musí být idempotentní (ingest je upsert,
  publikace odpovědi se váže na `store_review_id`).

## Zvažované alternativy

- **AWS SQS + EventBridge.** Škáluje líp, ale rozbíjí self-host příběh a přidává vendor lock.
- **Quartz.** Těžší, XML/JDBC konfigurace, horší ergonomie z Kotlinu.
- **Cron v kontejneru.** Neřeší zamykání mezi instancemi ani retry.
