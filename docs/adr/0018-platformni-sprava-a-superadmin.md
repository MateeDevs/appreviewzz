# 0018 — Platformní správa: role SUPERADMIN a konfigurace v databázi

- **Stav:** Přijato
- **Datum:** 2026-08-31
- **Kontext fáze:** F7

## Kontext

Dvě věci se sešly do jednoho problému.

**První:** v nastavení aplikace svítí pole „Jak často stahovat recenze (minuty)". To není
zákaznická preference — je to knob, kterým si klient sáhne na náš provoz: kvóty store API,
zátěž workeru, počet volání do AI. Dnes ho může kdokoli s rolí ADMIN stáhnout na pět minut
pro všechny své appky. Pole, které nemá být volitelné pro koncového uživatele, ale někdo
ho nastavit musí.

**Druhá:** platformní tajemství a přepínače dnes žijí v proměnných prostředí (`AI_PROVIDER`,
`AI_API_KEY`, `AI_MODEL`). Změna znamená sáhnout do Coolify, uložit a **restartovat oba
kontejnery**. U klíče na AI překlady, který přibude, je to totéž znovu. Prostředí je správné
místo pro to, co aplikace potřebuje, **aby nastartovala**; není to místo pro to, co
potřebuje, **aby fungovala** — u toho chceme historii, audit a změnu bez výpadku.

Obojí míří na tutéž chybějící věc: **není kdo je za platformu zodpovědný.** Role
[`OrgRole`](../../server/core/src/main/kotlin/cz/matee/appreviewzz/core/model/Enums.kt)
jsou tři a všechny končí na hranici organizace. Nad ní nestojí nikdo.

Co přicházelo v úvahu:

| | Proč ne |
|---|---|
| **Nechat vše v prostředí, interval napevno v kódu** | nejlevnější dnes; ale každá změna je deploy a klíč na AI překlady stejně bude potřebovat rotaci bez výpadku |
| **Čtvrtá hodnota v `OrgRole`** | `atLeast` je uspořádání — cokoli nad OWNER by tiše prošlo **všemi** dnešními `requireRole` kontrolami a dalo platformnímu adminovi credentials všech klientů |
| **Vyhrazená „platformní organizace"** | členství by se muselo všude vyjímat z výpisů, cross-tenant testy by přestaly dávat smysl a jedna zapomenutá podmínka znamená přístup k cizím datům |
| **Samostatná admin aplikace** | druhý deploy, druhé přihlášení, druhá session vrstva — na jeden formulář a šest hodnot |

## Rozhodnutí

**Kolmá role `platform_role` na uživateli, oddělený strom cest `/api/platform`, a konfigurace
v databázi s prostředím jako výchozí hodnotou.**

### Role

- `app_user.platform_role` (`NULL` | `'SUPERADMIN'`), v doméně `PlatformRole?` na `User`.
  Kolmá osa k `OrgRole` schválně: superadmin **není** členem žádné organizace a nestane se
  jím implicitně.
- **Superadmin nevidí data klientů.** Recenze, credentials, členy ani audit organizace ne —
  `orgContext` zůstává postavený na členství. Není to formalita: platformní účet drží klíč
  k AI a stropy pro všechny, přidat mu k tomu ještě čtení cizích schránek znamená, že jeden
  ukradený účet je celý produkt. Podpůrný přístup k datům klienta je vlastní rozhodnutí na
  jindy — časově omezené, na žádost, v auditu.
- **Sekce vyžaduje zapnutý druhý faktor.** Bez něj vrátí `403` s větou a odkazem na
  zabezpečení. Účet, který drží platformní tajemství, je poslední, který má stát na samotném
  heslu ([ADR 0015](0015-druhy-faktor-totp.md)).
- **Roli uděluje jen seed CLI** (`user platform-role --email … --role superadmin`), ne API.
  Takových účtů budou jednotky a povýšení přes HTTP je jediná operace, po které by z jednoho
  kompromitovaného admina byli dva.
- Ochrana visí na **stromě cest** (`requirePlatformAdmin`), stejně jako `requireSession` —
  nová sekce se nemůže zapomenout zeptat.

### Konfigurace

- **Typovaný katalog, ne volné key/value.** Každý klíč je deklarovaný v jádře: typ, výchozí
  hodnota, kontrola, věta pro člověka. Console z katalogu vykresluje formulář, neznámý klíč
  je `400`. Volná konfigurační tabulka je místo, kde překlep přežije roky.
- **Pořadí přebíjení: databáze > prostředí > výchozí hodnota v kódu**, a API u každé položky
  říká, odkud hodnota je (`DEFAULT` / `ENV` / `DB`). Obráceně to nejde: kdyby vyhrávalo
  prostředí, admin by v consoli ukládal hodnotu, která nic nedělá.
- **Čára mezi prostředím a databází:** co aplikace potřebuje, aby nastartovala (`DATABASE_*`,
  `VAULT_KEK_URI`, `SERVER_*`, `APPREVIEWZZ_ROLE`), zůstává **jen** v prostředí a do katalogu
  nepatří. Konfigurace uložená v databázi, bez které se k databázi nedostaneme, je kruh.
- **Dvě tabulky, ne jedna s příznakem.** `platform_setting` (hodnota se čte a ukazuje)
  a `platform_secret` (write-only, ven jde jen fingerprint a nápověda — stejné pravidlo jako
  u credentials). Jedna tabulka s „tohle je tajné" sloupcem znamená, že jeden `SELECT *`
  v nesprávném handleru pošle klíč do JSONu.
- **Hodnota je `text`, ne `jsonb`.** Každý dnešní klíč je skalár, typ i rozsah hlídá katalog
  v jádře a `text` je to, co je v `psql` čitelné bez odvozování. Až bude potřeba strukturovaná
  hodnota, je to migrace jednoho sloupce — dnes by to byla jen složitost navíc.
- **Platformní tajemství se pečetí pod `app_data_key`**, tedy pod stejným DEK jako TOTP
  (AAD `platform:<klíč>`). Vlastní klíč per organizace tu nedává smysl — platforma organizace
  není. Bez `VAULT_KEK_URI` sekce klíčů nejde otevřít a řekne to větou.
- **Čtení přes cache s TTL 30 s.** API a worker jsou dva procesy, sdílená invalidace by
  znamenala LISTEN/NOTIFY nebo restart. Půl minuty zpoždění u změny konfigurace nikdo nepozná
  a console to u uložení napíše.
- **Každá změna do `platform_audit_log`.** `audit_log.org_id` je `NOT NULL` a nullovat ho
  by znamenalo, že jedna zapomenutá podmínka pustí platformní záznam do auditu klienta.
  U tajemství se loguje otisk před a po, nikdy hodnota.

### Interval stahování

- `app.ingest_interval_minutes` se stává **nullable**; `NULL` znamená „platí platformní
  výchozí hodnota". Migrace přepíše dnešní `30` (výchozí hodnotu, kterou nikdo vědomě
  nenastavil) na `NULL`, takže flotila začne poslouchat nastavení platformy. Teď je to
  jeden `UPDATE`; po F6 by to byl zásah do dat klientů.
- Platformní klíče: `ingest.default_interval_minutes` (30) a `ingest.min_interval_minutes`
  (15) jako podlaha, pod kterou nesmí ani výjimka pro konkrétní appku.
- **Z formuláře aplikace pole mizí** a zůstává po něm věta „Recenze se stahují každých 30 min".
  `PATCH` s `ingestIntervalMinutes` od nesuperadmina končí na `403` — tiché ignorování by
  z vlastního klienta nad API udělalo hádanku.
- Výjimku pro konkrétní appku nastavuje superadmin (`PATCH /api/platform/apps/{id}`). Sloupec
  na to je od začátku, i kdyby ji první měsíce nikdo nepoužil.

Ostatní pole v nastavení aplikace (`dailyDigestAt`, `locale`, `timezone`, instrukce pro AI)
zůstávají klientovi. Jsou to jeho preference, ne náš provoz — to je ta dělící čára.

## Důsledky

- **`Components.suggestions` přestane být `lazy` singleton.** Provider dnes vzniká jednou
  z konfigurace při startu; s klíčem v databázi se musí umět přestavět, když se klíč změní.
  Je to jediné netriviální místo celého záměru.
- Přibývá třetí místo, kde leží zapečetěné tajemství — `vault rotate` (F5.6) musí projít
  i `platform_secret`, jinak po rotaci klíčů zůstane nerozbalitelné.
- **Nová položka v threat modelu:** účet, který drží platformní klíč a stropy pro všechny
  klienty. Proti tomu stojí povinný druhý faktor, udělení role jen z CLI, write-only tajemství,
  audit a to, že k datům organizací se nedostane.
- Self-host dostane stejnou sekci. Dokud si nikdo roli neudělí, sekce neexistuje a platí
  hodnoty z prostředí — nic se nestává povinným.
- Console dostává první cestu mimo `OrgLayout` (`/platforma`). Odkaz se ukazuje podle
  `platformRole` z `/api/auth/me`, ale rozhoduje server.
- **Zdroj hodnoty se u tajemství musí dohledávat zvlášť.** Tajemství neleží v `platform_setting`,
  takže naivní „je v tabulce → je uložené" u nich vždycky odpoví „z prostředí". Stálo to jednu
  chybu při implementaci a jeden test, který ji hlídá.
- **Formulář posílá jen změněné klíče.** Kdyby posílal všechny, uložil by do databáze i hodnoty,
  které nikdo nezvolil — a tím by nenávratně zrušil možnost vrátit se k prostředí či k výchozí
  hodnotě. Vypadá to jako kosmetika formuláře; ve skutečnosti je to celé pořadí přebíjení.
- `UserSecretBox` se přejmenoval na `AppSecretBox` a obsluhuje obojí. Sdílí jeden aktivní DEK,
  takže rotace přešifruje uživatelská i platformní tajemství naráz — dvě třídy by znamenaly
  dvě rotace a otázku, který klíč je ten aktivní.
