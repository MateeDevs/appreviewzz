# 0010 — Logické zálohy `pg_dump` do object storage, obnova vedle

- **Stav:** Přijato
- **Datum:** 2026-08-19
- **Kontext fáze:** F1

## Kontext

Databáze běží v kontejneru na vlastním serveru ([0008](0008-hosting-coolify-kek-v-kms.md)),
takže nemá PITR ani automatické snapshoty, které by dalo RDS. V databázi jsou přitom recenze
klientů, jejich watermarky a credentials ke storům — data, která se z n8n nedají znovu vyrobit.

Zároveň má systém běžet i u self-hostera, který nemá S3 ani cloud, a smí mít jedinou povinnou
závislost, Postgres.

## Rozhodnutí

**Noční `pg_dump --format=custom` z workeru, výsledek do object storage, retence v aplikaci.**

- Úloha `backup-database` v db-scheduleru ([0004](0004-db-scheduler.md)) — stejný plánovač,
  stejná role `worker`, žádný cron na hostiteli.
- Cíl podle `BACKUP_TARGET`: `s3://bucket/prefix` (náš provoz i každé S3 kompatibilní
  úložiště) nebo `file:///cesta` (self-host).
- Retence maže aplikace (`BACKUP_RETENTION_DAYS`, výchozí 30) a **vždycky nechává posledních
  sedm záloh** — kdyby zálohy přestaly vznikat, stáří by jinak smazalo i tu poslední.
  V S3 je nad tím ještě lifecycle jako pojistka.
- Každý běh, úspěšný i neúspěšný, jde do tabulky `backup_run`. Z ní se počítá metrika
  `appreviewzz_backup_last_success_age_seconds`, nad kterou stojí jediný smysluplný alarm:
  „poslední záloha je starší než den".
- **Obnova jde vždycky do vedlejší databáze**, ne přes běžící provoz. CLI `backup restore`
  odmítne obnovovat do provozní databáze a po obnově vypíše počty řádků.
- Obnova je součástí CI: test `BackupDrillTest` udělá dump, uloží ho, obnoví do prázdné
  databáze a porovná data.

## Důsledky

- Do runtime image přibyl `postgresql-client-17`. Klient musí být stejně starý nebo novější
  než server, jinak `pg_dump` zálohu odmítne udělat — verzi image a databáze proto zvedáme
  spolu.
- Záloha je konzistentní k okamžiku svého startu, ne k libovolnému bodu v čase. RPO je den,
  RTO desítky minut. Pro dnešní objem dat (jednotky klientů) je to přiměřené; až bude
  potřeba PITR, znamená to přejít na spravovaný Postgres (F5).
- Dump nese credentials zabalené vault klíčem ([0005](0005-envelope-encryption.md)), ne
  otevřeně. Kdo získá bucket, credentials nerozbalí — potřeboval by k tomu ještě KMS.
  **Keyset self-hostera je ale samostatné tajemství a musí se zálohovat zvlášť**, jinak jsou
  credentials po obnově nečitelné.
- Zálohy jsou zapnuté podle konfigurace, ne ve výchozím stavu: bez `BACKUP_TARGET` se úloha
  nezaregistruje a worker to při startu hlásí varováním.

## Zvažované alternativy

- **`pg_basebackup` + WAL archiv (PITR).** Lepší RPO, ale znamená to držet archiv WAL segmentů
  a obnovu řešit přes `recovery.conf`; provozní složitost neodpovídá dnešní velikosti systému.
- **Snapshot volume na hostiteli.** Rychlé, ale nekonzistentní k transakcím a nepřenositelné
  mezi stroji ani verzemi Postgresu.
- **Samostatný kontejner s cronem a `aws cli`.** Nevyžaduje klienta v image, zato duplikuje
  konfiguraci, obchází [0006](0006-jeden-image-dve-role.md) a self-hostera nutí přidat službu.
- **Šifrování dumpu vlastním klíčem.** Zvažováno; SSE-S3 plus zabalené credentials dnes stačí
  a vlastní klíč by přidal další tajemství, o které jde přijít zrovna při havárii.
