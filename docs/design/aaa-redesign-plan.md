# appreviewzz: plán zásadního redesignu produktu

Datum: 24. 10. 2026. Výchozí revize: `59ac0175be621b9f636581d8a74dae1760c1fa13`.
Implementační zadání pro novou session: [aaa-redesign-prompt.md](aaa-redesign-prompt.md).

## Stav implementace 24. 10. 2026

Implementace Signál je integrovaná přes PR #3, návazná oprava přes PR #4. Pracovní větve
`feat/aaa-signal-redesign` a `fix/aaa-login-return`; aktuální stav, kontroly a staging
jsou v [aaa/progress.md](aaa/progress.md). Doplněný [audit](aaa/audit.md),
[výběr směru a reference](aaa/direction.md), [návrat](aaa/rollback.md).

Ověřené změny proti přípravě: přímý headless browser staging login funguje; uživatel dodal
autorizovaný účet přes 1Password a skutečné heslo/MFA i přihlášené hlavní plochy byly
ověřeny. Docker a lokální Ktor/Postgres fungují, backendové i browser testy prošly.
Nasazení a výsledky posledního SHA jsou v průběžném protokolu.
Při implementaci byly navíc potvrzeny chyby health (strop 200 čekajících a klíče celé
organizace u každé appky), analytického rozsahu inboxu a dvojího spotřebování ověřovacího
tokenu ve StrictMode, ztraceného návratu po přihlášení při pomalém profilu a závodu
rychlých kláves j/j. Jde o úzké opravy nutné pro pravdivé pracovní UI, nikoli nové moduly.

## 1. Výsledek, který chceme

Přestavět konzoli appreviewzz na svébytné, precizně zpracované prostředí pro práci s reputací mobilních aplikací. Změna musí být na první pohled zásadní a při každodenní práci užitečná. Úroveň „AAA“ zde znamená spojení výrazné vizuální identity, promyšlených pracovních postupů, důvěryhodných dat a dokončení všech obrazovek i jejich okrajových stavů.

Uživatel má okamžitě poznat:

1. Co se děje s jeho aplikacemi a čemu se má věnovat.
2. Jak rychle vyřešit konkrétní recenzi s pomocí AI.
3. Který problém se opakuje, po které verzi vznikl a jaké recenze to dokládají.
4. Zda data, propojení se story a doručování fungují.

Redesign není splněný změnou palety, rádiusů a stínů. Musí změnit kompozici hlavních obrazovek, navigační hierarchii, typografii, práci s daty a způsob odpovídání. Technická funkčnost sama o sobě není důkazem designové kvality; hezký statický návrh zase není dokončeným produktem.

**Cílový výstup:** funkční a ověřená aplikace na [stagingu](https://console.staging.appreviewzz.com), dohledatelný commit, srovnání před/po a stručný předávací protokol. Produkční release je samostatný krok mimo tento plán.

## 2. Co je ověřené a co teprve ověřit

### Ověřeno v této přípravě

- Repo obsahuje React + TypeScript + Vite + TanStack Query, vlastní CSS a SVG grafy. API a konzole se distribuují společným Docker image a konzoli servíruje Ktor.
- Staging při přípravě vracel na `GET /` HTTP 200 a `/health/live` hlásil výše uvedené SHA, shodné s checkoutem.
- Přihlašovací obrazovka byla vykreslena z nasazených HTML/CSS/JS a skutečné anonymní API odpovědi přes HTTP relay do Playwrightu. Přímá navigace headless prohlížeče timeoutovala; relay používal `curl` a zachoval stavové kódy. Nešlo o přihlášený průchod ani ověření síťového chování prohlížeče.
- [Desktopový login](baseline/staging-login-desktop.png) ukazuje současný základ: malá středová karta, systémové písmo, indigo tlačítko, obecný gradientový znak a velká nevyužitá plocha. Zachycen je i [mobilní login](baseline/staging-login-mobile.png).
- Ze zdrojového kódu jsou ověřené stránky Přehled, Recenze, Rozbory, Aplikace, detail aplikace, Organizace, Audit, Průvodce, auth a dialogy zabezpečení a správy platformy.
- Existují AI návrhy odpovědí, překlad konceptu, šablony, klávesy inboxu, hromadné odložení, témata, sentiment, alerty, dopad verzí, konkurenční aplikace a veřejný měsíční report.
- `epic/v2` spouští sestavení image a staging deploy; větev `production` spouští povýšení image do produkce. Viz aktuální [workflow](../../.github/workflows/deploy.yml).

### Omezení auditu

V této session nebyl k dispozici přihlášený účet. Hodnocení vnitřních obrazovek vychází ze zdrojového kódu, nikoli z jejich živého vizuálního a uživatelského testu. Nová session musí nejdříve získat skutečný přihlášený baseline. Zde uvedené UX problémy jsou tam, kde není přímý důkaz, hypotézy k ověření.

Před implementací znovu ověřit aktuální branch, SHA stagingu, API kontrakty, přístup a workflow. Dokumentace a některé komentáře mohou být starší než kód; například názvy plánů v README se liší od `OrgPlan` v aktuálních typech.

## 3. Produktová pozice a současná konkurence

Primární uživatelé: člověk odpovídající na recenze, produktový manažer sledující problémy po vydání a agentura spravující více aplikací/klientů. První optimalizujeme každodenní práci s recenzemi; produktová analytika je druhý hlavní pilíř. Správa integrací má tyto činnosti podporovat.

Průzkum veřejných oficiálních produktových stránek dne 24. 10. 2026:

| Reference | Doložený produktový důraz | Co z toho plyne pro appreviewzz |
| --- | --- | --- |
| [AppFollow](https://appfollow.io/) | Správa a automatizace odpovědí, analýza témat a sentimentu, konkurenční signály, integrace | AI odpověď sama není odlišení. Potřebujeme výbornou návaznost problém → důkaz → odpověď. |
| [Appbot](https://appbot.co/) | Monitoring, sentiment, témata, odpovídání, dashboardy a konkurence | Rozbory musí působit jako hlavní produktová schopnost a být propojené s konkrétními recenzemi. |
| [Appfigures](https://appfigures.com/) | Širší analytika aplikací, srovnávání trhu a práce s více aplikacemi | Jasný kontext aplikace, platformy, trhu a období; žádné zavádějící součty nesourodých metrik. |
| [Linear](https://linear.app/) | Pracovní prostředí pro produktový vývoj, triage a provázané úkoly | Referenční princip: přehledná hierarchie a rychlost obsluhy. Vlastní vizuální jazyk navrhnout samostatně. |
| [Intercom Inbox](https://www.intercom.com/helpdesk/inbox) | Sdílený inbox s kontextem a AI podporou | Referenční princip: seznam a detail vedle sebe, editor v přímé návaznosti na obsah. |

První tři jsou přímé či oborové reference, poslední dvě reference pracovního UX. Jde o průzkum veřejné prezentace, nikoli o ověření placených aplikací. Doporučení v posledním sloupci jsou naše návrhové závěry. Nová session doplní dostupné produktové screenshoty nebo oficiální dema a oddělí pozorování od domněnek.

**Navržené odlišení:** rychlý přechod od reputačního signálu k doloženému problému a odpovědi, přehled více klientů a precizní Slack/Teams návaznost. To je hypotéza hodnoty produktu, nikoli tvrzení, že tyto schopnosti konkurence nemá. Nezvětšovat rozsah o ASO suite, AI chat, billing nebo další nesouvisející moduly.

## 4. Doporučený vizuální směr

Pracovní název směru: **Signál**. Klidné, výrazně typografické prostředí s tmavou inkoustovou navigací, světlou pracovní plochou a omezeným barevným akcentem. Osobitost vzniká kompozicí, písmem, kresbou dat a konzistencí detailu.

### Charakter a rozpoznatelnost

- Výchozí světlá plocha v mírně teplém neutrálu, hluboký inkoust pro text/navigaci, petrolejový primární akcent. Pozitivní, varovné a chybové stavy mají oddělené sémantické barvy.
- Pracovní paleta pro prototyp: canvas `#F5F4F0`, povrch `#FFFFFF`, ink `#17221F`, navigace `#172724`, akcent `#146C60`. Jsou to kandidáti, nikoli schválené tokeny; textové kombinace a tmavý režim vyžadují měření kontrastu.
- Výrazná titulková sazba, čitelná pracovní sazba, tabulární číslice pro metriky. Vybrat self-hostované WOFF2 písmo s licencí a úplnou českou diakritikou; nejvýše dvě rodiny, ideálně jedna variabilní. V prototypu porovnat například Geist Sans a Manrope, ověřit jejich aktuální oficiální zdroje a podmínky. Systémový fallback zachovat.
- Motiv signálu/ozvěny navrhnout v SVG a promítnout do značky, favicony a několika prázdných stavů. Zachovat název `appreviewzz`. Návrh nemá stát na běžné hvězdičce v gradientovém čtverci.
- Výrazná analytická křivka a kvalitní přehled témat jsou reprezentativní vizuály produktu. Fotobanky a nesouvisející ilustrace nepřidávají hodnotu.
- Mírné zaoblení, minimum stínů, oddělení ploch linií a tónem. Každá sekce nemusí být karta. Dashboard má souhrn a portfolio, inbox dělenou pracovní plochu a rozbory analytickou kompozici.
- Součástí dodávky je dotažený tmavý režim. Ovladač Světlý / Tmavý / Systém musí být dostupný, bez počátečního probliknutí. Do lokálního úložiště smí preference tématu, nikoli auth token.

### Jak vybrat směr bez dlouhého bloudění

Na začátku připravit tři malé, skutečně rozdílné návrhy stejného Přehledu a stejného otevřeného inboxu: doporučený světlý Signál, chladnější analytickou variantu a variantu s dominantním tmavým prostředím. Stejná data a viewport zabrání tomu, aby výběr ovlivňoval atraktivnější obsah.

Návrhy mohou být lokální HTML/React prototypy; není nutný Figma soubor. Označit prototypová data. Vybrat jeden směr podle čitelnosti, osobitosti, hustoty a škálování do ostatních obrazovek, důvody zapsat a pokračovat. Varianty nesmí být pouhé přebarvení stejného layoutu. Z prototypování nedělat samostatnou dlouhou etapu a nevyžadovat automaticky další schválení.

### Základ systému

Definovat tokeny pro plochy, text, okraje, akce, stavové barvy, grafové série, velikosti písma, řádkování, mezery, rádiusy, vrstvy a pohyb. Pracovní obsah orientačně 14–16 px; titulek stránky 28–34 px; čísla 32–48 px podle kontextu. Rozměry upřesnit v prohlížeči, ne slepě převzít.

Sdílené komponenty: `AppShell`, `PageHeader`, přepínač organizace/aplikace, tlačítka, pole, select/combobox, badge, tabs, filtry, metrika s kontextem, tabulka, prázdný stav, skeleton, chyba, dialog, menu a toast. Komponenty musí mít skutečné pending/disabled/focus/error stavy. Nedoplňovat knihovnu jen kvůli vzhledu; cílená závislost je přijatelná, pokud doložitelně řeší přístupnost či složité chování.

## 5. Nová struktura a návrh obrazovek

### Navigace a kontext

Hlavní pracovní sekce: Přehled, Recenze, Rozbory a Aplikace. Organizace, Audit a nastavení účtu jsou podpůrná vrstva. Průvodce ukazovat kontextově podle stavu nastavení; přímá cesta musí zůstat dostupná. Správa platformy se řídí stávající rolí a MFA.

Organizace musí být vždy zřejmá. Přepnutí organizace vyčistí její výběr aplikace i detail recenze a nesmí na chvíli zobrazit data předchozího tenanta. Zachovat existující URL; nové analytické podsekce lze řešit tabs a query parametry. Není nutné vyrábět nové routy a endpointy pro každou sekci.

Nezavádět falešný globální výběr „všechny aplikace“, když API aktuálního pohledu vyžaduje jednu aplikaci. Přehled je portfolio, inbox a rozbory mají jasně vybranou aplikaci. Filtry mají být konzistentní mezi obrazovkami, nikoli předstírat možnosti serveru.

| Obrazovka | Nová kompozice a práce uživatele | Kdy je hotová |
| --- | --- | --- |
| Přehled | Výrazný pracovní souhrn, co čeká a co je naléhavé; prioritní signály; kompaktní portfolio aplikací s platformními ratingy a trendy. Diagnostika má vlastní kontextový blok. | Uživatel do 10 sekund najde čekající práci, nejvážnější dostupný signál a problém s propojením. Každá akce vede na správně filtrovaný cíl. |
| Recenze | Na desktopu seznam a detail vedle sebe; filtry nahoře; vybraná recenze má jasný stav. Detail obsahuje originál, témata, zdůvodnění, historii a editor. | Lze projít více recenzí bez návratů mezi stránkami, filtrovat, odpovědět, odložit a pracovat klávesnicí. Výběr i filtry přežijí refresh a Back. |
| Rozbory | Přehled nálady s jasným rozsahem, velký trend, témata jako prioritizovaná tabulka, trhy a odpovídání. Dopad verzí a reporty jsou přehledné podsekce. | Metrika vysvětluje zdroj, období a vzorek. Z tématu, alertu či verze jde přejít na odpovídající recenze. |
| Aplikace | Přehledné portfolio s rozlišením vlastních a konkurenčních aplikací, platformami a stavem nastavení; výrazná akce Přidat aplikaci. | Čitelně funguje pro 1, 12 i 30 aplikací a nespoléhá na neexistující ikony ze storu. |
| Detail aplikace | Souhrn a stav; navigace mezi propojením storů, kanály, AI/automatizací, rozbory a pokročilými volbami. | Zmizí dojem nekonečného formuláře, přitom zůstane dostupné každé současné nastavení a jeho validace. |
| Onboarding a připojení storu | Jedno rozhodnutí na krok, postup, průběžné ověření a jasná oprava problému. Pomoc u konkrétního pole; rozlišení čekání na oprávnění a chyby. | Ověřen App Store i Google Play, existující i provisionovaný klíč a návrat do rozpracovaného nastavení v rozsahu současných schopností. |
| Auth a organizace | Vlastní značková kompozice, kvalitní formulář, jasné chyby; na desktopu lze přidat grafický motiv skutečného produktu. | Login, registrace, ověření, reset, pozvánka, MFA i výběr organizace tvoří konzistentní celek. |
| Organizace, Audit, Security, Platform | Přehledné tabulky, role, formuláře a dialogy ve stejném systému. Bezpečnostní údaje jsou čitelné a akce jednoznačné. | Všechny stávající možnosti fungují podle role, dlouhé hodnoty a tabulky nerozbíjejí layout. |
| Veřejný report `/r/<token>` | Typograficky výrazný klientský dokument: souhrn, trend, problémy, dopad verzí a zdroj dat. | Zůstává serverové HTML bez JS, zmrazený snapshot, odvolatelné sdílení a kvalitní tisk na A4. |

### Inbox: povinné detaily

- Dělený pohled přibližně od 1180 px, s šířkou seznamu kolem 340–420 px a dostatkem prostoru pro editor. Při 1024 px zvážit detail přes seznam, na telefonu samostatný detail s tlačítkem Zpět a zachovaným stavem seznamu.
- K obsluze patří `j`, `k`, `r`, `i`, Escape a viditelná nápověda. Zkratky nezasahují do psaní v editoru, dialogu ani výběrovém poli.
- Query parametry musí zahrnovat aplikaci, stav, typ, naléhavost, platformu, téma, verzi, řazení a stránku v rozsahu API; detail může mít `review=<id>`. Neplatné hodnoty se bezpečně normalizují.
- Opravit konkrétní existující nesoulad: Přehled odkazuje s `urgency=HIGH`, inbox však naléhavost zatím drží pouze v lokálním stavu.
- Změna recenze nesmí bez upozornění smazat rozepsaný text. Zvolit paměťový draft pro konkrétní organizaci/aplikaci/recenzi nebo potvrzení opuštění; při odhlášení data konceptu odstranit.
- AI návrh je vědomá akce, rozpracovaný výsledek se dá upravit. Překlad nesmí potichu přepsat vlastní text. Po odeslání zobrazit nejprve frontu, publikaci až při `PUBLISHED`; `FAILED` musí mít opravitelný stav.
- U přepracované recenze zachovat informaci, že odpověď reagovala na starší znění. Smazaná recenze zůstane rozpoznatelná a bez možnosti odpovědi. Konkurence je pouze pro čtení.
- Hromadné odložení zachová úspěšné položky a srozumitelně ukáže případné částečné selhání. Výběr nezůstane omylem aktivní po změně kontextu.

### Analytika: pravdivost má přednost před atraktivitou

- Odlišovat store rating od průměru hvězd ve vybraných recenzích, celková hodnocení od textových recenzí a sentiment textu od nálady všech hodnocení.
- U procent ukázat vzorek, rozsah a případně neúplnost analýzy; u delty vysvětlit srovnávací období a jednotku. Nula, `null`, nedostatek historie a vypnuté AI jsou rozdílné stavy.
- Neprůměrovat průměry aplikací či platforem bez správných vah. Nepřidávat vymyšlené health score, SLA ani důvěru modelu.
- Grafy: čitelné osy, jednotky, legenda, tooltip přístupný i dotykem/klávesnicí a textový ekvivalent. Mezery v datech nesmí působit jako změřená nula.
- Přehled více aplikací musí omezit požadavky. Dnes jednotlivé karty samostatně načítají ratingy, analýzu a alerty; změřit síťové chování pro 30 aplikací. Preferovat odložené načtení a cache, případně úzký dávkový endpoint s doloženým přínosem.

## 6. Technická mapa a hranice změny

| Oblast | Výchozí soubory | Zamýšlený zásah |
| --- | --- | --- |
| Základy UI | `console/src/styles.css`, `components/ui.tsx`, `components/Select.tsx`, `components/icons.tsx` | Tokeny, komponenty, přístupnost, nová vizuální identita; rozumné rozdělení CSS místo další vrstvy override pravidel. |
| Shell a routy | `console/src/App.tsx`, `pages/OrgLayout.tsx` | Hierarchie, kontext, responzivní navigace, téma. |
| Hlavní obrazovky | `pages/Dashboard.tsx`, `Inbox.tsx`, `Analysis.tsx` | Zásadní změna kompozice a odpovídacích postupů. |
| Grafy | `components/RatingsChart.tsx`, `SentimentChart.tsx` | Jednotný jazyk dat, interakce a alternativní čtení. |
| Nastavení | `pages/Apps.tsx`, `Onboarding.tsx`, `Organization.tsx`, `Organizations.tsx`, `Security.tsx`, `Platform.tsx`, `components/ConnectStoreWizard.tsx` | Rozdělení obsahu, stavy a kompletní migrace na nový systém. |
| Auth | `pages/Auth.tsx`, `components/ui.tsx` | Značka, kompozice a všechny průchody přihlášením. |
| API | `console/src/api/types.ts`, `hooks.ts`, `client.ts` | Zachovat kontrakty a cache podle organizace. Přidávat pouze malé změny nezbytné pro doložený UX problém. |
| Webové assety | `console/public/`, `console/index.html` | SVG značka/favicon, lokální fonty s licencí, metadata tématu a inicializace bez CSP výjimky. |
| Veřejný report | `server/app/src/main/kotlin/cz/matee/appreviewzz/app/ReportPage.kt` | Design dokumentu, SVG a tiskové styly; žádný nový JS runtime. |
| Asset serving / CSP | `server/app/src/main/kotlin/cz/matee/appreviewzz/app/ConsoleStatic.kt`, `SecurityHeaders.kt` | Jen ověření funkčnosti nových assetů; WOFF2 už má MIME podporu. Bez oslabení CSP pro fonty či skripty. |

Zachovat React, TypeScript, TanStack Query, společný image a současnou architekturu. Starší komentáře odmítající vlastní font nebo komponentový systém aktualizovat, pokud je nové řešení překoná. Nejsou důvodem vracet se pro nové svolení k běžnému návrhovému rozhodnutí.

Rozsah zahrnuje kompletní vizuální migraci uživatelských ploch a několik nezbytných UX oprav. Nová funkce vyžadující větší doménový model jde do navazujícího backlogu. Stávající funkce nesmí zmizet jen proto, že komplikují screenshot. Nový marketingový web není součástí tohoto zadání.

## 7. Implementační etapy a kontrolní brány

| Etapa | Konkrétní práce | Artefakt a podmínka pokračování |
| --- | --- | --- |
| 0 — Baseline | Projít přihlášený staging, hlavní role, route mapu, UI stavy a současné API. Pořídit screenshoty a zaznamenat tři hlavní pracovní scénáře. | `docs/design/aaa/audit.md`, inventář funkcí, skutečné baseline snímky. Chybějící přístup výslovně označen. |
| 1 — Směr | Doplnit produktové reference, vytvořit tři malé varianty a vybrat jednu. Navrhnout finální Přehled, inbox a Rozbory ve společném systému. | `direction.md`, renderované návrhy, rozměry, typografická hierarchie a důvod výběru. Strukturně výrazná změna proti baseline. |
| 2 — Základy | Implementovat tokeny, značku, fonty, komponenty, shell, responzivní navigaci a obě témata. | Funkční základ s reálnými routami, správným focusem, kontrastem a licencemi assetů. |
| 3 — Hlavní produkt | Dokončit inbox včetně stavů a URL, Přehled s prioritami a Rozbory s průchody do recenzí. | Tři skutečné průchody na API a vizuální kontrola plných i řídkých dat. Nejprve tento krok, až pak vedlejší plochy. |
| 4 — Úplnost | Aplikace, detail, onboarding, auth, organizace, audit, security/platform a veřejný report. | Žádná obrazovka nezůstává ve starém stylu; matice funkcí je pokrytá. |
| 5 — Dotažení | Kontrola desktopu/mobilu/témat, přístupnosti, grafů, výkonu, copy a chyb. Opravit nalezené problémy. | `validation.md`, srovnání před/po, screenshoty a výsledky automatických i ručních kontrol. |
| 6 — Staging | Po kontrolách doručit do staging větve podle aktuálního workflow, sledovat CI/deploy a ověřit nasazené SHA a skutečné chování. | URL, SHA, CI odkazy, staging smoke a známé limity; připravený postup návratu. |

Etapy jsou pořadí a kontrolní brány, nikoli tvrzení, že vše je možné kvalitně dokončit za jednu krátkou session. Nepřeskakovat dokončení hlavních ploch kvůli množství vedlejší práce. Při přerušení zapsat přesný stav, příkazy, výsledky, rozhodnutí a nejbližší další krok do `docs/design/aaa/progress.md` a pokračovat z něj.

## 8. Testovací data a ověření

### Datová matice

Použít existující autorizovaná staging data a izolované lokální fixtures, případně vyhrazenou testovací organizaci. Do stagingu nekopírovat produkční databázi. Pro screenshoty nesmí aplikace potají zaměnit skutečná data za ukázková.

- Organizace bez aplikace, s 1 aplikací a s 12/30 aplikacemi; vlastní i konkurenční.
- Žádné recenze, jedna recenze, mnoho recenzí, dlouhé texty/jména, cizí jazyky, emoji, chybějící tělo, chybějící autor.
- Recenze nová, přepracovaná, odstraněná, odpovězená i odložená; odpověď čekající, publikovaná a selhaná.
- AI zapnutá, vypnutá, čekající a dočasně nefunkční; neúplné rozbory a malý vzorek.
- Klíč platný, neplatný a čekající na oprávnění; odpojený kanál; selhaná úloha.
- OWNER, ADMIN, MEMBER, superadmin s MFA; jedna i více organizací.

Fixture pro návrhové prototypy může být syntetická a musí být označená. Kontraktní backendové testy mají nadále používat zachycené reálné odpovědi podle pravidel repozitáře.

### Tři reprezentativní uživatelské scénáře

1. **Odpověď:** Přehled → naléhavé recenze správné aplikace → detail → AI koncept → úprava → překlad → zařazení k odeslání → ověření publikace nebo selhání. Odeslání do skutečného storu ověřovat jen na určené testovací aplikaci a s výslovně povoleným testovacím obsahem; ostatní mutace pokrýt lokálně/kontraktně.
2. **Produktový problém:** Rozbory → rostoucí téma → dopad verze → přesně filtrované zdrojové recenze → Back zachová analytický kontext. U nepodporovaného filtru otevřeně doplnit potřebný malý kontrakt nebo upravit návrh.
3. **Zprovoznění:** nová organizace → aplikace → store → ověření oprávnění → kanál → srozumitelný stav připravenosti. Skutečnou testovací zprávu do Slacku/Teams poslat pouze při výslovném zadání; automatický test použije zachycené odpovědi nebo testovací transport.

### Automatické a ruční kontroly

- V `console`: `npm ci`, `npm run lint`, `npm run typecheck`, `npm run build`.
- Repozitář vyžaduje `./gradlew build`; zvlášť při zásahu do reportu/API ověřit příslušné testy. Nepřeskočit chybu prostředí mlčky; Docker/JDK podmínky doložit.
- Přidat úzké smysluplné browser testy pro URL filtry/detail, přepínání kontextu, zachování rozepsaného textu a stavy odesílání. Není potřeba test pro každý rádius a ikonu.
- Browser smoke s reálným backendem doplnit deterministickými scénáři bez skutečné publikace do storu. Test s mocky nepopsat jako skutečný staging E2E.
- Automatická kontrola přístupnosti hlavních pohledů, ručně Tab/Shift+Tab/Escape, dialogové focus chování, popisky polí, zkratky a čtení grafu.
- Screenshoty při šířkách 1440, 1280, 1024, 768 a 390 px; 360 px pro nejhustší plochy. Hlavní pohledy desktop i mobil v obou tématech, auth a vedlejší pohledy alespoň reprezentativně. Zkontrolovat 200% zvětšení.
- Pro hlavní porovnání použít shodný viewport, dataset, filtry a téma. Ukázat i prázdná a řídká data. Screenshotově maskovat citlivé údaje a tokeny reportů.

## 9. Kritéria přijetí

### Vizuální a produktová kvalita

- Přehled, inbox a Rozbory mají proti baseline zřetelně jinou kompozici a hierarchii. Změna není závislá jen na barvě.
- Značka, písmo, metriky, grafy a všechny interakční prvky tvoří jeden systém. Žádná routa či dialog nezůstává ve starém stylu.
- Inbox využívá desktop pro paralelní seznam a detail; mobil umožňuje pohodlně filtrovat, číst a odpovědět bez sevřených sloupců.
- U každé metriky je jasné, co měří. Prázdno není prezentováno jako úspěch, chybějící hodnota jako nula a fronta jako dokončená publikace.
- Vizuální review musí pojmenovat a opravit konkrétní slabiny. Subjektivní vlastní známka „9/10“ není důkazem splnění; konečný vkus posoudí zadavatel podle dodaného výsledku.

### Funkčnost a použitelnost

- Všechny existující schopnosti jsou zachované a ověřené v matici funkcí. Filtry z analytiky a Přehledu skutečně mění inbox, včetně `urgency=HIGH`.
- URL stavy, refresh a Back fungují. Žádné ztrácení konceptu, dvojí odeslání ani falešná akce v menu.
- Žádný horizontální overflow celé stránky při 390 px. Široká tabulka může mít vlastní srozumitelný scroll. Primární akce na mobilu mají přibližně 44 × 44 px dotykovou plochu.
- Normální text dosahuje kontrastu alespoň 4,5:1, velký text a relevantní UI hranice alespoň 3:1. Informace není jen barevná. Fokus je vidět a `prefers-reduced-motion` se respektuje.
- Obě témata fungují včetně grafů, formulářů, menu a načítání. Dlouhé názvy, diakritika, 200% zoom a klávesnice nerozbijí použitelnost.

### Výkon a doručení

- Změřit baseline i výsledek na stejné trase, datech a omezení sítě/CPU. Cíle: LCP ≤ 2,5 s, CLS ≤ 0,1; u INP cílit ≤ 200 ms, ale rozlišovat skutečná uživatelská data od laboratorního měření interakcí.
- Zaznamenat velikost build assetů a počet požadavků pro 1 a 30 aplikací. Přírůstek počátečního gzip JS nad 20 % proti baseline musí mít konkrétní zdůvodnění a prověřené lazy loading možnosti. Fonty předběžně držet do 150 kB celkem v běžném načtení.
- Žádné nové neošetřené chyby v browser konzoli, chybějící assety či neočekávané CSP blokace. Backendové role, CSRF a tenant izolace zůstávají zachované.
- Po deployi ověřit `/health/ready`, `/health/live`, očekávané SHA, přihlášení, přímý refresh podstránky, všechny tři hlavní plochy a relevantní report. Zelené workflow samo není důkaz, že staging běží na novém image.

Číselné cíle jsou přijímací cíle návrhu. Pokud je nelze měřit kvůli prostředí či chybějící telemetrii, uvést přesně co a proč; nevymýšlet výsledek ani automaticky prohlásit splnění.

## 10. Staging, návrat a předání

Před prvním staging deployem zaznamenat současné SHA a ověřit dostupnost odpovídajícího image. Pracovat v samostatné větvi, uchovat cizí změny, potom doručit ověřený výsledek do aktuálně určené staging větve. Při zahájení nové session opět přečíst workflow; dokumentace ADR obsahuje i historické postupy.

Aktuální deploy workflow běží nezávisle na CI workflow. Proto před pushem do staging větve dokončit lokální kontroly a ověřit CI implementační větve/PR, pokud je k dispozici. Deploy může být zelený i při neúspěšné jiné kontrole.

Při kritické regresi preferovat revert konkrétních redesign commitů na staging větvi a standardní redeploy. Zaznamenat a ověřit SHA po návratu; nevracet větev přes force push. U případné DB změny musí být rollback kompatibilní, i když samotná migrace zůstane v databázi. Pro běžný redesign migrace nepředpokládáme.

Předání musí obsahovat:

1. Staging URL, nasazené SHA a odkazy na relevantní CI/deploy běhy.
2. Stručný popis nového směru a tří nejdůležitějších změn při práci s produktem.
3. Porovnání před/po Přehledu, inboxu a Rozborů; mobil, tmavý režim a ukázku reportu.
4. Výsledky kontrol, seznam zachovaných funkcí a přesné známé limity.
5. Postup návratu a případný samostatný backlog větších produktových změn.

Pokud se screenshoty přikládají k PR, použít dostupný skill `pr-shot` a jeho postup pro sdílené URL. Lokální obrázek není příloha soukromého GitHub PR. Samotná návrhová a implementační práce tím není blokovaná.

## 11. Nejčastější způsoby, jak toto zadání nesplnit

- Přebarvený současný layout a nová sada velkých kulatých karet.
- Efektní první dashboard a nedodělaný inbox, onboarding nebo bezpečnostní dialog.
- Design fungující pouze při ideálním objemu ukázkových dat nebo jen ve světlém tématu.
- Marketingové sliby, vymyšlené metriky, nefunkční hledání nebo tlačítka vedoucí na „coming soon“.
- Přepis frameworku a backendu místo produktového a vizuálního výsledku.
- Nasazení bez kontroly skutečného image a přihlášeného průchodu.
- Konec u návrhu nebo lokální implementace bez pravdivě popsaného stavu stagingu.
