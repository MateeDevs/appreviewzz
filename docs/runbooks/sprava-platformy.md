# Runbook — správa platformy

Rozhodnutí a proč to tak je: [ADR 0018](../adr/0018-platformni-sprava-a-superadmin.md).

Sekce `/platforma` v consoli je jediné místo, kde jedna změna dopadne na **všechny klienty**.
Tenhle dokument říká, jak se do ní někdo dostane, co v ní jde nastavit a co udělat, když
se něco pokazí.

## Co sekce spravuje

| Klíč | Co dělá | Kdy se do toho sahá |
|---|---|---|
| `ingest.default_interval_minutes` | jak často se stahují recenze aplikacím **bez výjimky** | zátěž workeru, kvóty store API, náklady na AI |
| `ingest.min_interval_minutes` | podlaha i pro výjimky | aby se pod ni nedostala ani ručně udělená výjimka |
| `ai.provider`, `ai.model` | který provider generuje návrhy odpovědí | přepnutí providera, zkoušení modelu |
| `ai.api_key` | klíč k AI (tajemství) | rotace klíče, výměna účtu u providera |
| `limits.max_apps_per_org` | strop počtu aplikací na organizaci; `0` = bez omezení | zneužití self-serve onboardingu |

Co v sekci **není a nebude**: `DATABASE_*`, `VAULT_KEK_URI`, `SERVER_*`. Bez nich se aplikace
nespustí, takže je nemá kde přečíst — zůstávají v prostředí.

## 1. Udělení role

Jen ze stroje, kde běží aplikace (Coolify → terminál kontejneru, nebo lokálně proti téže
databázi). **Přes API to nejde a nikdy nepůjde.**

```bash
appreviewzz user platform-role --email tadeas@matee.cz --role superadmin
```

Bez `--email` vypíše, kdo roli má. Odebrání:

```bash
appreviewzz user platform-role --email tadeas@matee.cz --role none
```

Po udělení si člověk **musí zapnout druhý faktor** (Zabezpečení účtu → TOTP), jinak ho sekce
nepustí dovnitř a vrátí `403 platform_mfa_required`. Příkaz na to sám upozorní.

## 2. Změna nastavení

Console → **Správa platformy** → Nastavení. U každé položky je odznak, odkud hodnota je:

- **výchozí** — hodnota z kódu, nikdo ji nikdy nenastavil
- **z prostředí (`AI_PROVIDER`)** — bere se z proměnné; uložením v consoli se přebije
- **uloženo** — je v databázi a platí přednostně

Prázdné pole = zruš uložené a vrať se o patro níž. Formulář posílá **jen změněné položky**,
takže co se nesáhlo, si zdroj podrží.

**Změna se projeví do půl minuty** — obě role (`api` i `worker`) čtou konfiguraci přes cache
s TTL 30 s. Restart není potřeba a nepomůže dřív než ta půlminuta.

U intervalu je ještě jeden krok: novou periodu propíše `ingest-sweep`, který běží každých
60 s (`INGEST_SWEEP_SECONDS`). Celkem tedy **do zhruba minuty a půl** od uložení.

## 3. Platformní klíč (tajemství)

Console → Správa platformy → Klíče. Vloží se hodnota a uloží; **zpátky ji nedostane nikdo**,
ani provozovatel. Ven jde jen otisk (`sha256:…`) a nápověda (délka a poslední čtyři znaky),
aby šlo poznat, který klíč je uložený.

Rotace klíče k AI = prostě vložit nový; starý se přepíše a v auditu zůstane změna otisku.
Tlačítko **Zrušit** uložený klíč smaže a hodnota spadne zpět na proměnnou prostředí.

Bez `VAULT_KEK_URI` sekce klíčů uložit nic nedovolí a řekne to větou — otevřený klíč
v databázi je horší než žádný.

## 4. Výjimka intervalu pro jednu aplikaci

Console → Správa platformy → Výjimky intervalu. Vypisují se **jen** aplikace, které nějakou
mají; udělení jde přes API:

```bash
curl -X PATCH https://console.appreviewzz.com/api/platform/apps/<ID aplikace> \
  -H 'Content-Type: application/json' -d '{"minutes":60}'
```

`{"minutes":null}` výjimku ruší. Pod `ingest.min_interval_minutes` to nepustí ani tady.

## 5. Když něco nesedí

| Příznak | První hypotéza |
|---|---|
| `403 platform_mfa_required` | účet nemá zapnutý druhý faktor — Zabezpečení účtu |
| `404` na `/api/platform/*` u člověka, který roli má | role se udělila jinému účtu (jiný e-mail); ověř `user platform-role` bez argumentů |
| Uložená hodnota „nic nedělá" | proběhla už půlminuta? U intervalu ještě minuta na sweep. Pak zkontroluj `appreviewzz platform config` — ukáže hodnotu i zdroj z pohledu serveru |
| Klíč k AI uložený, ale návrhy nechodí | `ai.provider` je pořád `none`; provider a klíč jsou dvě nezávislé položky |
| Po rotaci KEK nejde klíč rozbalit | `vault rotate` bez `--org` musí projít i tajemství mimo organizace; zkontroluj, že v jeho výstupu je řádek „tajemství mimo organizace" |

Rychlý pohled na stav mimo console (nepotřebuje přihlášení, jen přístup ke stroji):

```bash
appreviewzz platform config
```

## 6. Co po sobě zůstane

Každá změna je v `platform_audit_log` a v consoli pod **Historie změn** — kdo, kdy, z čeho
na co. U tajemství jen otisk před a po; hodnota se do auditu nezapisuje nikdy.

Audit organizací (`audit_log`) je od tohohle oddělený schválně: platformní záznam se nesmí
objevit v auditu klienta.
