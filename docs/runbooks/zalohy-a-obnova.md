# Runbook — zálohy a obnova databáze

Rozhodnutí a proč to tak je: [ADR 0010](../adr/0010-zalohy-pg-dump.md).
Tenhle dokument je návod pro chvíli, kdy je zle — čte se odshora dolů a nic se v něm nedomýšlí.

## Jak to běží

| | |
|---|---|
| Co | `pg_dump --format=custom` celé databáze |
| Kdy | denně v `BACKUP_AT` (výchozí 02:30 UTC), úloha `backup-database` ve workeru |
| Kam | `BACKUP_TARGET` — `s3://bucket/prefix` (náš provoz) nebo `file:///cesta` (self-host) |
| Jak dlouho | `BACKUP_RETENTION_DAYS` (30), posledních 7 záloh zůstává vždycky |
| Stopa | tabulka `backup_run` + metrika `appreviewzz_backup_last_success_age_seconds` |

Zálohy jsou vypnuté, když `BACKUP_TARGET` není nastavené. Worker to při startu hlásí
varováním `BACKUP_TARGET není nastavený — databáze se nezálohuje`.

**Keyset se nezálohuje spolu s databází.** U self-hostu (`VAULT_KEK_URI=local://…`) je
zašifrovaný keyset jediný klíč k credentials v dumpu — bez něj se po obnově klíče ke storům
nerozbalí. Zálohuj ho zvlášť a jinam než dumpy. V našem provozu je KEK v KMS, takže tenhle
problém nemáme.

## Denní kontrola

```bash
docker exec -it <kontejner-api> /opt/appreviewzz/bin/appreviewzz backup list
```

Vypíše obsah úložiště a posledních pět běhů. Alarm si postav nad metrikou stáří poslední
úspěšné zálohy, práh ~30 hodin (jedna zmeškaná noc):

```
appreviewzz_backup_last_success_age_seconds > 108000
```

Metrika chybí nebo je `NaN` → zálohy nikdy neproběhly, respektive nejsou zapnuté.

## Ruční záloha

Před každým rizikovým zásahem (migrace schématu, ruční UPDATE, upgrade Postgresu):

```bash
docker exec -it <kontejner-api> /opt/appreviewzz/bin/appreviewzz backup run
```

Skončí nenulovým kódem, když se záloha nepovede — dá se pověsit do skriptu před zásah.

## Obnova

Obnovuje se **vždycky do vedlejší databáze**, nikdy přes běžící provoz. CLI to jinak neumí:
obnovu do provozní databáze odmítne.

1. **Vyber zálohu.**

   ```bash
   docker exec -it <kontejner-api> /opt/appreviewzz/bin/appreviewzz backup list
   ```

2. **Obnov ji vedle.** Otisk se porovná s historií běhů, takže se pozná poškozený soubor.

   ```bash
   docker exec -it <kontejner-api> /opt/appreviewzz/bin/appreviewzz \
     backup restore --key postgres/appreviewzz-2026-08-19T02-30-00Z.dump --database appreviewzz_obnova
   ```

   Výstup vypíše verzi schématu a počty řádků v hlavních tabulkách. Porovnej je s tím,
   co čekáš — tady se pozná neúplná záloha.

3. **Přepni aplikaci na obnovenou databázi.** Zastav `api` i `worker`, přepiš `DATABASE_URL`
   na novou databázi a nastartuj. Původní databáze zůstává netknutá — když se obnova ukáže
   jako špatná, jde se zpátky přepnutím proměnné.

4. **Po přepnutí zkontroluj:**

   ```bash
   curl -s https://<doména>/health/ready
   docker exec -it <kontejner-api> /opt/appreviewzz/bin/appreviewzz org list
   docker exec -it <kontejner-api> /opt/appreviewzz/bin/appreviewzz credential list --org <slug>
   ```

   Credentials musí jít rozbalit (`credential validate`) — když ne, chybí keyset nebo přístup
   ke KMS, ne data.

Opakovaná obnova do stejné databáze potřebuje `--drop-existing true`. Ta databázi zahodí
i s daty, takže si dvakrát přečti, co je za `--database`.

## Drill (čtvrtletně, a po každé změně schématu)

Cvičná obnova. Nezapisuje se, jestli proběhla — zapisuje se, **jak dlouho trvala** a co se
při ní nepovedlo, protože to je jediné číslo použitelné při skutečné havárii.

1. `backup run` — ruční záloha, ať se cvičí na čerstvých datech.
2. `backup restore --key … --database appreviewzz_drill_<datum>` a změř čas.
3. Porovnej počty řádků s produkcí:
   ```sql
   SELECT count(*) FROM organization; SELECT count(*) FROM review;
   ```
4. Zahoď cvičnou databázi: `DROP DATABASE appreviewzz_drill_<datum>;`
5. Zapiš datum a naměřený čas do tabulky níž.

| Datum | Kdo | Velikost zálohy | Doba obnovy | Poznámka |
|---|---|---|---|---|
| 2026-08-19 | Tadeáš | 51,5 KiB | ~4 s | první drill po nasazení F1.8; databáze byla ještě prázdná, ověřený je tedy mechanismus, ne objem — opakovat, až budou v systému klienti |

Automatizovaná verze téhož běží v CI při každém buildu (`BackupDrillTest`): dump → úložiště →
obnova do prázdné databáze → porovnání dat. Drill na produkci ověřuje navíc objem dat
a přístup k úložišti, což test v CI ze své podstaty neumí.

## Když zálohy přestanou chodit

1. `backup list` — kdy byla poslední úspěšná a co říká poslední selhání.
2. Log workeru: `docker logs <kontejner-worker> | grep -i backup`.
3. Otevřený záznam v DLQ (`failed_job`, task `backup-database`) drží chybovou hlášku
   z posledního pokusu.

Nejčastější příčiny:

| Příznak | Příčina | Řešení |
|---|---|---|
| `pg_dump nešel spustit` | image bez klienta Postgresu | zkontroluj tag image; klient je v něm od F1.8 |
| `pg_dump skončil kódem 1: server version 18.x; pg_dump version 17.x` | databáze je novější než klient | zvedni verzi image, teprve pak Postgres |
| `Zálohu … nešlo nahrát` (403) | vypršelý nebo špatný AWS access key, chybí právo na prefix | rotuj klíč, zkontroluj IAM politiku z modulu `backups` |
| `No space left on device` | plný disk hostitele (dump se dělá do dočasného souboru) | uvolni místo, zkontroluj retenci u `file://` cíle |
| úloha vůbec neběží | chybí `BACKUP_TARGET` | doplň proměnnou a restartuj worker |

## Co záloha neřeší

- **Smazání dat chybou aplikace**, které si nikdo nevšimne dřív než za měsíc — retence je 30 dní.
- **Bod v čase.** Obnovit jde stav noční zálohy, ne stav před pěti minutami. Data z posledního
  dne (recenze, odpovědi) se po obnově dotáhnou dalším ingestem, protože watermark je v databázi
  a stáhne se od něj znovu.
- **Keyset u self-hostu.** Viz výš — samostatná záloha, samostatné místo.
