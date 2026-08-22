# 0016 — Produkce v1 zůstává na Coolify, ECS/RDS stack zůstává zaparkovaný

- **Stav:** Přijato
- **Datum:** 2026-08-22
- **Kontext fáze:** F5

## Kontext

[ADR 0008](0008-hosting-coolify-kek-v-kms.md) posunul aplikaci na vlastní server s Coolify
a nechal kompletní terraform stack pro ECS Fargate, RDS, ALB a ECR zvalidovaný, ale vypnutý.
Plán říká, že **rozhodnutí, jestli se zapne, patří do F5** —
tedy sem, před zveřejnění repa a před migraci klientů.

Co dnes běží: jeden server, kontejnery `api` a `worker` ze stejného image, Postgres
v kontejneru, Traefik s Let's Encrypt, push do `epic/v2` znamená deploy. Zálohy jsou noční
`pg_dump` do object storage s **vyzkoušenou obnovou** ([ADR 0010](0010-zalohy-pg-dump.md)).
KEK zůstává v AWS KMS a **nestěhuje se** — to je to podstatné, protože právě proto přechod
kdykoli později nevyžaduje přešifrování jediného credentialu.

Čísla (`eu-central-1`, on-demand, ověřeno 2026-08-18):

| | Dnes | ECS/RDS produkce |
|---|---|---|
| Compute | firemní VPS | Fargate ARM ~$17 (dvě role ~$33) |
| Databáze | kontejner | RDS `t4g.small` Multi-AZ $54 |
| Vstupní bod | Traefik na témž stroji | ALB $20 + 2× IPv4 $7,3 |
| Odchozí provoz z privátní sítě | — | NAT gateway $38 |
| KMS, logy, ECR, CloudTrail | ~$1,5 | ~$4 |
| **Měsíčně** | **~$1,5** | **~$174** |

Rozdíl je zhruba **2 000 USD ročně**. Otázka tedy nezní „co je lepší", ale **co za ty peníze
dostaneme a jestli to zrovna teď potřebujeme**.

Co by ECS/RDS opravdu přinesl:

1. **PITR místo denního dumpu.** Dnešní RPO je až 24 hodin, RDS umí obnovu na sekundu.
2. **Multi-AZ.** Výpadek jednoho stroje dnes znamená výpadek služby.
3. **Nemuset látat operační systém.** Patchování hostitele je dnes na nás.
4. **WAF a autoscaling.** Ani jedno dnes nemáme.

## Rozhodnutí

**Produkce v1 zůstává na Coolify. Terraform stack zůstává zaparkovaný a zvalidovaný.**

Tři důvody, v tomhle pořadí:

1. **Co je vlastně nenahraditelné.** Recenze a hodnocení se dají znovu stáhnout ze storů
   (ingest čte historii, ratings snapshot se znovu vytvoří). Nenahraditelné jsou **credentials
   klientů, nastavení organizací a historie snapshotů** — dohromady jednotky megabajtů, které
   noční dump pokrývá. Dvacet čtyři hodin RPO na datech, jejichž větší část umíme dopočítat
   ze zdroje, není totéž jako 24 hodin RPO na účetnictví.
2. **Dostupnost, kterou stejně neumíme slíbit.** Multi-AZ chrání proti výpadku zóny, ale nic
   nechrání proti tomu, že tým je jeden člověk bez pohotovosti. Platit za devět devítek
   v infrastruktuře, když nad ní stojí jednomístné SLA, je nákup špatné vrstvy.
3. **Přechod se neplatí dvakrát.** KEK se nestěhuje, aplikace je jeden image konfigurovaný
   proměnnými prostředí a terraform modul je hotový a zvalidovaný. Přesun je den práce
   kdykoli později, ne přepis. Rozhodnutí je tedy **odložitelné, ne nevratné** — a odložitelná
   rozhodnutí se odkládají.

### Kdy se to překlopí

Nechceme, aby „zůstáváme" znamenalo „už se na to nikdy nepodíváme". Zapnout stack se má,
jakmile nastane **kterákoli** z těchhle věcí:

- **první klient, kterému slíbíme SLA** s dostupností nebo RPO pod 24 hodin;
- **potřeba víc než jedné instance API** — ta zároveň vynutí sdílený stav pro limity požadavků
  a ochranu proti přehrání ([threat model](../threat-model.md) §5, bod 3);
- **obnova ze zálohy nad produkčními daty trvá víc než 30 minut** (dnes jednotky minut);
- **hostitel potřebuje upgrade OS**, který neumíme naplánovat bez výpadku;
- **měsíční účet za VPS a provoz kolem něj přesáhne polovinu** ceny ECS/RDS varianty — pak už
  se platí za horší věc.

Kontrola těchhle podmínek patří do čtvrtletního ohlédnutí, ne do nikdy.

## Důsledky

- **Do runbooku a do smlouvy patří napsané RPO 24 hodin.** Zamlčené je horší než malé.
- Coolify se musí dodělat do stavu, kterému se dá říkat produkce. Konkrétně: zálohy
  ukládat **mimo tentýž stroj**, retence 30 dní u klientských dat, externí kontrola
  `/health/ready`, alarm na stáří poslední zálohy, automatické bezpečnostní aktualizace
  hostitele a správně nastavené `TRUSTED_PROXY_HOPS` (jinak limity požadavků vidí všechny
  klienty jako jednu adresu).
- Terraform modul se **nesmí nechat shnít**: `terraform validate` patří do CI, jinak z něj
  za rok bude kód, který se nedá spustit, a odložitelnost rozhodnutí zmizí.
- Zůstáváme na jedné instanci API. Je to podmínka platnosti limitů požadavků a ochrany proti
  přehrání — kdyby někdo přidal repliku, tiše se obojí zeslabí. Patří to do runbooku.
