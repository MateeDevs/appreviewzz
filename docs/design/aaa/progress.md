# Redesign AAA — průběžný stav

## Výchozí stav / 24. 10. 2026
- Větev: `feat/aaa-signal-redesign`, založena z `59ac0175be621b9f636581d8a74dae1760c1fa13`.
- Staging: https://console.staging.appreviewzz.com, `/health/live` SHA `59ac0175be621b9f636581d8a74dae1760c1fa13`, ready/live UP.
- Workflow znovu přečteno: staging `epic/v2`; produkce `production` a tag `prod` mimo rozsah. CI a deploy nejsou provázané. Integrace až po kontrolách.
- GitHub CLI přihlášen; origin fetch funguje. Žádné sledované uživatelské změny. Existující nesledované `docs/design/` zachovány.
- Přečten plán, README, CONTRIBUTING, console README, workflows, Coolify a produkční/analytický runbook. V relevantních cestách nenalezen AGENTS.md.
- Docker byl vypnutý; spuštěn OrbStack a lokální Postgres. JDK 21 dostupné. Běží výchozí `./gradlew build` (log `/tmp/appreviewzz-baseline-gradle.log`).
- Přihlášený staging přístup nenalezen v projektu. Položena jedna otázka na testovací účet/session. Není dovoleno zaměnit fixtures za staging E2E.

## Rozpracováno
- Přidán Playwright + axe (jen dev), typované izolované fixtures a zachycení baseline renderů.
- Vite http://127.0.0.1:5173, session 89815.
- Potvrzené problémy: urgency/type/state/detail mimo URL; editor v seznamu a ztráta konceptu po unmountu; placeholderData u inboxu/auditu může přenést starý kontext.
- Směr: Signál, pracovně teplá plocha + inkoustový rám, petrolej a vlastní SVG ozvěna. Připravit tři kompozičně odlišné varianty před implementací.

## Další krok
Dokončit baseline snímky a zaznamenat assety. Renderovat tři varianty Přehledu/inboxu na totožných fixtures, vybrat směr, implementovat tokeny/shell a hlavní pracovní plochy. Následně vedlejší UI/report, kompletní kontroly, PR a staging.

## Návrat
Předchozí staging SHA výše. Po integraci při kritické regresi revert redesign commitů na `epic/v2` a běžný deploy; nikdy force push. Žádné DB migrace nejsou plánované.

## Implementace a průběžné ověření (22:34)
- Hotovo: 3× Přehled + 3× inbox v `variants/`, vybrán Signál. Vyrenderováno a vizuálně prohlédnuto. Geist variable 69 760 B, OFL v public/fonts.
- Nové tokeny, SVG značka/favicon, inkoustový shell, hierarchie pracovních a správních sekcí, mobilní navigace, Světlý/Tmavý/Systém. Téma inicializuje blokující same-origin `theme.js` před CSS, CSP nezměněno.
- Přehled: dominantní pracovní souhrn, konkrétní naléhavé aplikace, diagnostika jen při problému, prioritní výkyvy, souvislé portfolio. Trendy/nálada se dotazují až při vstupu řádku do viewportu.
- Inbox: skutečný dělený desktop, mobilní detail, URL filtry/state/type/urgency/review/sort/page, návrat, per-tenant/app/review koncept v paměti, beforeunload ochrana, historie a blokace dalšího odeslání při PENDING, polling fronty. Hromadné chyby zachovají úspěchy. Konkurence/smazané jen ke čtení.
- Rozbory: procentní trend s osami, rozlišením čar i bez barev a datovou tabulkou. Metriky oddělené, opraveno znaménko delty kladné nálady. Témata podle záporných zmínek, scope u všech bloků, zdrojové odkazy zachovávají období/trh/platformu. Rozbalení verze v URL.
- API: úzké rozšíření inbox filtru o datum v zóně aplikace, trh a textOnly. Stejný filtr seznamu/počtu. Přidán test hranice lokálního dne a neplatných dat.
- Zjištěno: health počítal jen prvních 200 čekajících a ukazoval klíče celé organizace u každé aplikace. Opraveny skutečné počty a vazby klíčů, přidán regresní test nad 205 recenzemi.
- Aplikace: oddělené vlastní/konkurenční portfolio; detail s boční navigací; onboarding jako číslovaný postup se stavem nastavení, auth ve značkové kompozici; společné formuláře a dialogy v novém systému, popisky propojeny s ovladači.
- Veřejný report: přepracovaný SSR dokument, značka, typografie, legenda/osy a hodnoty grafu, tmavá preference, A4 print. Snapshot/token/revokace bez změny.
- 12 funkčních Playwright regresí prošlo (mock transport). 5 vizuálních/a11y testů prošlo: hlavní plochy 1440/1280/1024/768/390/360 v obou tématech, axe na desktopu/mobilu, vedlejší plochy a dialog. Opraven kontrast hotových kroků a fokus tabulek.
- Přímý headless staging login tentokrát funguje (bez relay), screenshot v before/. Přihlášený přístup stále čeká na odpověď uživatele.
- Původní build: JS 486,87 kB / gzip 146,25; CSS 29,23 / gzip 6,73. Výchozí lint/typecheck/build i Gradle prošly. Průběžné compile chyby v novém backendu opraveny, finální Gradle build nyní běží.

Další krok: dokončit backendové testy a lokální průchod se skutečným Ktor/Postgres; znovu vyrenderovat po mobilním zkrácení filtrů; ověřit auth/wizard/role/report print/200% zoom, výkon a síť 1/30. Dokončit docs, CI/PR a integraci stagingu jen po zelených kontrolách.

## Kontroly před PR (22:41)
- `./gradlew build` opakovaně zelený, naposledy 54 s; nové testy rozsahu, health >200, report rendereru a původní testy sdílení/revokace prošly. Log `/tmp/appreviewzz-redesign-gradle.log`.
- Browser sada: 26 prošlo / 3 opt-in přeskočeno. Samostatně prošel skutečný lokální API smoke a měření výkonu. Následné malé úpravy grafu ratingu a stavu fronty se právě znovu kontrolují.
- Při rychlém j→r test zachytil načítací závod fokusu. Editor nyní přijme požadavek fokusu i během načítání detailu; rychlé j/k používá aktuální navigační ref. 12 inbox regresí po opravě prošlo.
- Lokální API na :8080, Vite :5173. Nový oddělený testovací účet/org/app; žádný worker, klíče, store reply ani kanál. Registrace/ověření/přidání appky/nastavení/login skutečně prošly.
- Veřejný report vygenerován skutečným `ReportPage` rendererem v testu, HTML v `/tmp/appreviewzz-report.html`; light/dark/print rendery a A4 PDF v after/. Není to zákaznický sdílený report.
- Kontrast: text/akce ≥4,5:1, relevantní hranice polí light 3,48:1, dark 3,20:1. Kombinace v contrast.json. 200% ekvivalent: 720 CSS px s DSF 2, nikoli měření skutečného uživatelského INP ani ovládání browser chrome.
- Produkční preview, CPU ×4, 1,6 Mbps/150 ms, 3 studené běhy: LCP median po změně 1,79–1,96 s; CLS max median 0,049. Dashboard 30 app: 93→10 API requestů v prvním viewportu. Raw performance.json. Otevření editoru 208 ms median v laboratoři je end-to-editor čekání, ne INP.
- JS po lazy načtení security/platform: počáteční gzip 143,67 kB proti 146,25 kB baseline (−1,8 %); font 69,76 kB. Finální čísla ještě aktualizovat po build.
- Ověřena dostupnost předchozího multiarch image `59ac017…`: digest `sha256:9311d745df56ac49c4dbdac20199fa3dec4a36ec640e5bd66c1a0dcd1edce29b`. Předchozí deploy https://github.com/MateeDevs/appreviewzz/actions/runs/37684736265.
- Origin epic/v2 je stále výchozí SHA. GitHub admin/push přístup potvrzen. Staging login přístup stále chybí; neoznačovat přihlášené staging E2E jako ověřené.

Další krok: poslední browser/asset render po grafu ratingů; skill pr-shot pro snímky, commit + PR do epic/v2, CI před integrací, pak staging deploy/health/SHA/refresh a pravdivý zápis zbývajícího přihlášeného ověření.

## Připraveno k PR
- Poslední lint/typecheck/build zelené; build JS gzip 144,09 kB, CSS gzip 9,13 kB.
- Finální browser sada 26 prošlo / 3 opt-in utility přeskočeny. Po poslední URL opravě navíc všech 12 inbox regresí zelených. Samostatná vizuální sada 7/7.
- Backend 745 testů bez chyb; původně přeskočený backup drill dodatečně prošel s PG klientem 18.6 (`/tmp/appreviewzz-backup-drill.log`).
- A4 PDF má dvě strany; obě renderované přes PDFKit a vizuálně prohlédnuté.
- Připraveno comparison.html a validation.md. Screenshoty nahrány přes pr-shot; žádné tokeny/session nejsou v artefaktech.
- Následuje commit/PR, CI; bez zeleného CI se do epic/v2 neintegruje.

## PR a CI
- PR: https://github.com/MateeDevs/appreviewzz/pull/3, base epic/v2; první implementační commit `8677ca6ed7ae2e9f2e85095721bbee947024fa70`.
- První CI https://github.com/MateeDevs/appreviewzz/actions/runs/40131252045 zachytilo React immutability lint v novém RatingsChart. Lokální dříve instalované node_modules jej nehlásily. Opraveno čistým výpočtem sousedních bodů bez mutace proměnné; `npm ci` nyní znovu synchronizuje přesně lockfile.
- Zároveň zpřesněn odkaz výkyvu z Přehledu na konkrétní den (stejný filtr jako z Rozborů) a přidána regrese skutečných odkazů z Přehledu.
- Staging dosud nezměněn. Následuje opravené PR CI a teprve pak integrace.

- Čisté `npm ci`, lint, typecheck aplikace/testů a build po CI opravě prošly. 13 inbox regresí včetně skutečných odkazů Přehledu prošlo.
- Závěrečné vizuální review zachytilo příliš malé popisky SVG os na úzkém telefonu. Grafy nyní používají souřadnice podle skutečné šířky sloupce (ResizeObserver); mobil tak nesmrští text osy na 5 px. Sentiment má jeden tab stop a šipky místo desítek zastávek po týdnech. Období/vzorky a datová alternativa zůstávají.
- Dotažena i veřejná 404 reportu ve stejném směru; status a nerozlišitelnost zrušeného/neexistujícího tokenu beze změny.

## Finální kandidát
- Poslední lint/typecheck/build prošly; počáteční JS gzip 144,42 kB (baseline 146,25), CSS 9,13 kB. Browser sada 26/26 + 3 opt-in skips, mobilní rating a report vyrenderované zvlášť.
- Poslední izolovaný `./gradlew build` prošel za 55 s včetně backup drillu s explicitním PG klientem.
- CI fd3315a je celé zelené: https://github.com/MateeDevs/appreviewzz/actions/runs/40131390458. Finální responzivní grafy a report vyžadují ještě nové CI po následujícím commitu.
- Uživatel dodal 1Password sdílení. Přihlášení heslem + TOTP na skutečném stagingu úspěšné, organizace matee. Původní hlavní plochy se zachycují před integrací. Žádná store odpověď ani zpráva se neposílá.
- Další krok: push finálního kandidáta, nové PR CI celé zelené, merge do epic/v2, sledování deploye a skutečný přihlášený staging smoke.
