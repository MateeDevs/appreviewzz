# Runbook — nasazení do produkce

Rozhodnutí a proč to tak je: [ADR 0017](../adr/0017-staging-a-produkce-oddelena-prostredi.md).
Tenhle dokument je návod, který se čte odshora dolů a nic se v něm nedomýšlí.

## Jak to běží

| | |
|---|---|
| Staging | push do `epic/v2` → CI postaví image, publikuje `:latest` a `:<sha>`, nasadí staging |
| Produkce | ruční spuštění workflow **Deploy** → tag `:prod` se přesměruje na zvolený sha a nasadí se |
| Rollback | totéž spuštění se starším sha |

Produkce **nikdy nestaví image znovu**. Nasazuje se přesně ten, který už prošel CI a běžel na
stagingu — jinak by se do produkce mohlo dostat něco, co nikdo neviděl.

## Nasazení

1. **Ověř, že to na stagingu žije.** Alespoň přihlášení do konzole a jedna stránka s daty.
   Změny v ingestu nebo doručování si zaslouží i pohled do logu workeru.
2. **Vezmi si sha.** Plný commit hash toho, co je na stagingu — najdeš ho v běhu workflow,
   který staging nasadil.
3. **Actions → Deploy → Run workflow**, vlož sha, spusť.
4. Workflow ověří, že image pro ten commit existuje, přesměruje tag `prod` a spustí deploy
   webhook. Selhání v kroku *Ověřit, že image existuje* znamená překlep v sha, ne rozbitou
   produkci — nic se ještě nezměnilo.

## Po nasazení

Tři věci, každá do třiceti vteřin:

```bash
curl -fsS https://console.appreviewzz.com/health/ready
```

1. `/health/ready` vrací 200.
2. Konzole se načte a jde se přihlásit.
3. V logu API není při startu `ERROR` a chybí i varování o nenastavených proměnných
   (`VAULT_KEK_URI`, `TEAMS_BOT_APP_ID`, `BACKUP_TARGET`).

Když cokoli z toho neplatí, jdi rovnou na rollback — diagnostikovat se dá i potom.

## Rollback

Spusť **Deploy** znovu s předchozím sha. Tag `prod` se přesměruje zpátky a Coolify natáhne
starší image (`pull_policy: always`, takže se nepoužije lokální kopie).

**Rollback aplikace nevrací migrace databáze.** Flyway migrace jsou dopředné; když se
nasazení rozbilo o migraci, je to obnova ze zálohy podle
[runbooku o zálohách](zalohy-a-obnova.md), ne návrat tagu. Proto se migrace, které mažou nebo
přejmenovávají sloupce, dělají ve dvou krocích a mezi nimi je aspoň jedno nasazení.

## Na co si dát pozor

- **Jedna instance API.** Limity požadavků a ochrana proti přehrání drží stav v paměti
  procesu ([ADR 0016](../adr/0016-produkce-zustava-na-coolify.md)). Přidání repliky obojí
  tiše zeslabí — pokud někdy bude potřeba škálovat, musí se nejdřív přesunout stav ven.
- **`APP_VERSION` v Coolify je `prod` a nemění se.** Kdo tam napíše `latest`, obejde tím celé
  schvalování: produkce pak při nejbližším restartu natáhne poslední staging build.
- **`TRUSTED_PROXY_HOPS=1` platí pro dnešní cestu** (Traefik a nic před ním). Každá další
  proxy — Cloudflare v proxy režimu, druhý reverzní proxy — tu hodnotu mění a špatná hodnota
  znamená, že si adresu klienta určí kdokoli jednou hlavičkou. Ověřuje se to tak, že pošleš
  přes dvacet přihlášení, každé s jiným podvrženým `X-Forwarded-For` a jiným e-mailem; když
  limit funguje, přijde `429` s `Retry-After`.
- **RPO je 24 hodin.** Denní dump; při obnově se ztratí, co přibylo od půlnoci.
