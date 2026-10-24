# Audit a zachované schopnosti

Výchozí checkout i staging: `59ac0175be621b9f636581d8a74dae1760c1fa13`.
Samostatná implementační větev `feat/aaa-signal-redesign`. Sledované soubory byly na začátku čisté;
existující nesledované návrhové dokumenty zůstaly zachované.

## Co bylo skutečně prohlédnuto

- Přečten celý plán, README, CONTRIBUTING, console README, CI/deploy workflow, Coolify README,
  runbook nasazení a rozborů. Relevantní AGENTS.md v projektu ani jeho nadřazených cestách nenalezen.
- Staging `/health/live` a `/health/ready` UP, SHA shodné s checkoutem. Přímý headless browser
  tentokrát úspěšně otevřel login. `before/staging-login-direct.png` je skutečný anonymní staging.
- Přihlášený staging účet/session nebyl dostupný v projektu/lokálním setupu. V úvodu položena
  jedna konkrétní otázka na testovací přístup. Přístup se neobchází.
- `before/overview`, `inbox`, `analysis` a `apps` jsou skutečné rendery původního React UI na
  izolovaných syntetických API fixtures. **Nejsou to přihlášené staging snímky.** Původní zdroj
  byl rozbalen přes git archive do /tmp/appreviewzz-baseline, bez zásahu do checkoutu.
- Stejné fixtures a viewporty slouží pro srovnání po změně. Referenční obrázky konkurence
  byly skutečně otevřeny; odkazy a oddělení pozorování od závěrů jsou v direction.md.

## Potvrzené problémy a řešení

| Problém | Výsledek |
| --- | --- |
| urgency z Přehledu se ignorovala, state/type/detail byly lokální | Filtry i detail se čtou z URL; sdílení, refresh a Back pokrývá browser regrese. |
| Editor v dlouhém seznamu, koncept se unmountem zahodil | Samostatný dokument vedle seznamu, koncept v paměti oddělený tenantem/appkou/recenzí; varování před reloadem. |
| Analytický odkaz nezachoval období ani stav všech recenzí | Stejný rozsah pro seznam/počet, datum v zóně aplikace, trh/platforma/téma, state=all. |
| Store rating, hvězdy a nálada byly vizuálně zaměnitelné | Samostatné pojmy, vzorky a popisky; graf procent nálady bez skryté druhé osy. |
| Delta pozitivní metriky vycházela z negativního podílu | Počítá se rozdíl skutečného pozitivního podílu. |
| Health ukazoval nanejvýš 200 čekajících a klíče celého tenanta u každé appky | Úplný countByApp, urgent count, metadata skutečně připojených klíčů. Test 205 recenzí. |
| N+1 frontend dotazy při otevření portfolia | Trendy/nálada se načítají po vstupu řádku do viewportu; cache mezi plochami zachována. |
| Potvrzení e-mailu v StrictMode dvakrát spotřebovalo token | Jedno uplatnění tokenu; ověřeno skutečným lokálním backendem. |
| Form labels nebyly propojené s poli, dialog neměl jméno | Sdílený Field propojuje label, control a hint; Select podporuje id; Modal aria-labelledby. |

## Inventář migrace

| Plocha | Zachované schopnosti / způsob ověření |
| --- | --- |
| Přehled | Čekající/naléhavé, vlastní/konkurenční, nálada, store trendy po platformách, poslední výkyvy, diagnostika klíčů/kanálů/DLQ. Fixtures a backend health test. |
| Inbox | Aplikace, stav, platforma, typ, téma, naléhavost, verze, řazení, stránka, hromadné odložení, j/k/r/i/Escape, původní text/překlad, citáty témat, historie odpovědí, AI návrh, překlad s návratem, šablony a limity. Browser + backend testy. |
| Stavy recenzí | Přepracovaná, smazaná, odpověď k předchozímu znění, fronta/publikováno/chyba, konkurence pouze ke čtení. Browser fixtures a původní backend testy. |
| Rozbory | Klouzavé/kalendářní/vlastní období, trh/platforma, obě nálady, témata, zlepšení, jazyky, trhy, odpovídání, verze, alerty, backfill, ruční rozbor a sdílení/odvolání reportu. Žádný rozbor nebyl odeslán do skutečného kanálu. |
| Aplikace/detail | Přidání odkazy, konkurence, přehled nastavení, pozastavení, ratingy, upload i provisionované klíče, validace, kanály, Slack/Teams, AI instrukce, automatické poděkování, šablony, vlastní témata, historie, čas/locale. Nová navigace zachovává původní tab/hash URL. |
| Onboarding/wizard | Oba story, rozsah oprávnění, provisionování GP a čekání, ASC upload/Issuer ID/výběr appky, reporting bucket. Lokální přidání aplikace bez klíče; wizard fixtures a stávající backend kontrakty. Skutečné oprávnění do storu neměněno. |
| Auth/organizace | Login, registrace, ověření, reset, pozvánka, MFA, výběr organizace; role, pozvánky a tarif. Nový společný auth shell, lokální skutečný průchod a browser fixtures. |
| Audit/security/platform | Tabulky, stránkování, TOTP/recovery, platform settings/secrets/interval/audit. Původní API a role beze změny, sdílené dialogy, lazy loading a a11y průchod. |
| Veřejný report | SSR HTML bez JS, český/anglický locale, zmrazený snapshot a odvolatelné tokeny. Nová typografie/graf/print; ReportRoutesTest a ReportPageTest. |

## Bezpečnostní hranice

Session cookie, CSRF, tenant-scoped repository, role a MFA zůstávají na serveru. Auth tokeny se
neukládají do localStorage. Query placeholder se nepřenese mezi organizacemi/aplikacemi;
route obsah se při změně organizace remountuje. Koncepty se čistí při přihlášení/odhlášení.
Žádná data z produkce nebyla kopírována. Nevytvořen žádný skutečný store reply, Slack/Teams
zpráva ani automatické poděkování. Neběží lokální worker připojený ke storům.
