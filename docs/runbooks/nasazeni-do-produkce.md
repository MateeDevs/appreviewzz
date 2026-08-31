# Runbook — nasazení do produkce

Rozhodnutí a proč to tak je: [ADR 0017](../adr/0017-staging-a-produkce-oddelena-prostredi.md).
Tenhle dokument je návod, který se čte odshora dolů a nic se v něm nedomýšlí.

## Jak to běží

| | |
|---|---|
| Staging | push do `epic/v2` → CI postaví image, publikuje `:latest` a `:<sha>`, nasadí staging |
| Produkce | push do `production` → tag `:prod` se přesměruje na ten sha a nasadí se |
| Rollback | přepsání `production` na starší sha |

**Větev `production` je to, co běží v produkci.** Nikde se nedrží druhý seznam nasazených
verzí; `git log production` je celá historie nasazení.

Produkce **nikdy nestaví image znovu**. Nasazuje se přesně ten, který už prošel CI a běžel na
stagingu — jinak by se do produkce mohlo dostat něco, co nikdo neviděl. Proto se `production`
povyšuje **fast-forwardem**: merge commit by vyrobil nový sha, pro který žádný image
neexistuje, a workflow ho odmítne.

## Nasazení

1. **Ověř, že to na stagingu žije.** Alespoň přihlášení do konzole a jedna stránka s daty.
   Změny v ingestu nebo doručování si zaslouží i pohled do logu workeru.
2. **Posuň `production` na revizi, která na stagingu běží:**

   ```bash
   git push origin epic/v2:production
   ```

   Nasazuje se špička `epic/v2`. Když je na stagingu něco staršího (nebo chceš nasadit jen
   část toho, co se mezitím nakupilo), pushni ten konkrétní commit:
   `git push origin <sha>:production`.
3. Workflow ověří, že image pro ten commit existuje, přesměruje tag `prod` a spustí deploy
   webhook. Selhání v kroku *Ověřit, že image existuje* znamená, že se pushnul commit, který
   CI nikdy nepostavilo — typicky merge commit. Produkce se ještě nezměnila.

Push, který by nebyl fast-forward, git odmítne sám. To je záměr: znamená to, že v produkci
běží něco, co ve zvolené revizi není.

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

Přepiš `production` na předchozí sha — návrat je jediný případ, kdy se na tuhle větev
pushuje silou:

```bash
git push --force-with-lease origin <předchozí-sha>:production
```

Tag `prod` se přesměruje zpátky a Coolify natáhne starší image (`pull_policy: always`, takže
se nepoužije lokální kopie). `--force-with-lease` místo `--force` proto, aby push selhal,
kdyby mezitím někdo `production` posunul jinam.

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
- **Auto Deploy je v Coolify vypnutý a musí zůstat.** Se zapnutým auto-deployem spustí
  produkční nasazení každý push do větve, kterou resource sleduje, ještě než je image v GHCR.
- **`TRUSTED_PROXY_HOPS=1` platí pro dnešní cestu** (Traefik a nic před ním). Každá další
  proxy — Cloudflare v proxy režimu, druhý reverzní proxy — tu hodnotu mění a špatná hodnota
  znamená, že si adresu klienta určí kdokoli jednou hlavičkou. Ověřuje se to tak, že pošleš
  přes dvacet přihlášení, každé s jiným podvrženým `X-Forwarded-For` a jiným e-mailem; když
  limit funguje, přijde `429` s `Retry-After`.
- **RPO je 24 hodin.** Denní dump; při obnově se ztratí, co přibylo od půlnoci.
