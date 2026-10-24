# Prompt pro novou implementační session

Následující zadání vlož do nové session otevřené v repozitáři appreviewzz. Detailní plán je součástí repa; session ho musí skutečně přečíst.

---

Převezmi redesign appreviewzz jako seniorní produktový designér, art director a frontend engineer. Tvým úkolem je provést zásadní proměnu celého produktu a doručit ji funkční a ověřenou na staging. Nechci pouze návrh, doporučení ani kosmetické úpravy. Výsledek má působit jako svébytný profesionální SaaS, který se zpracováním může postavit vedle moderních konkurentů.

Nejdřív přečti `docs/design/aaa-redesign-plan.md`, případné `docs/design/aaa/progress.md`, relevantní `AGENTS.md`, `README.md`, `CONTRIBUTING.md`, `console/README.md`, aktuální deploy workflow a související runbooky. Plán obsahuje výchozí audit, hranice rozsahu, konkrétní návrhy obrazovek, implementační mapu, testy a definici dokončení. Pokud se něco od jeho vytvoření změnilo, ověř skutečnost a plán aktualizuj.

## Požadovaný výsledek

Kompletně změň vizuální identitu konzole, kompozici hlavních obrazovek, navigační hierarchii, typografii, grafy a interakce při práci s recenzemi. Zachovej všechny existující produktové schopnosti. Dotažení se týká i formulářů, dialogů, načítání, chyb, prázdných stavů, mobilu, tmavého režimu a veřejného reportu.

Samotná výměna barev, radiusů, ikon a fontu zadání nesplňuje. Stejně tak nestačí jedna reprezentativní stránka a zbytek původní aplikace. Změna musí být zásadně viditelná na Přehledu, v inboxu i v Rozborech a konzistentně pokračovat všemi dalšími plochami.

Hlavní pracovní osa produktu je: **signál → konkrétní problém → zdrojové recenze → odpověď / další rozhodnutí**. Uživatel odpovídající na recenze, produktový manažer i agentura musí rychle rozumět kontextu a dalšímu kroku. Vizuální osobitost nesmí zhoršit čitelnost ani pracovní rychlost.

## Jak máš pracovat

1. Zjisti stav repa, dostupné nástroje, přístup ke stagingu a aktuální deployment cestu. Nepřepisuj nesouvisející uživatelské změny. Pracuj v samostatné větvi; staging větev si ověř z aktuálního workflow.
2. Projdi skutečný produkt v prohlížeči, nejlépe přihlášený staging. Zachyť výchozí hlavní stránky, stavy a cesty. Přípravná session ověřila anonymní login a kód vnitřních stránek, nikoli přihlášené UI. Nemáš-li účet, podívej se nejprve po existujícím autorizovaném přístupu a lokálním setupu; potom polož jednu konkrétní otázku a mezitím pokračuj v nezávislé práci. Přístup neobcházej a neoznačuj simulaci za staging E2E.
3. Doplň stručný průzkum oficiálních screenshotů/dem AppFollow, Appbot a Appfigures. Pro pracovní interakce použij jako reference Linear a Intercom Inbox. Odděl ověřené vlastnosti od vlastních návrhových závěrů. Nekopíruj jejich branding ani layout do posledního detailu.
4. Připrav tři omezené vizuální varianty stejného Přehledu a inboxu se stejnými daty. Doporučeným výchozím směrem je Signál z plánu: hluboký inkoust, jemně teplá pracovní plocha, petrolejový akcent, precizní typografie a motiv signálu v SVG. Varianty se musí lišit kompozicí a charakterem. Vyber a zdůvodni jednu, nečekej automaticky na další schválení běžných designových voleb.
5. Vybraný směr rozpracuj do funkčního UI. Postupuj přes tokeny a shell, tři hlavní pracovní plochy, ostatní obrazovky, dotažení, testy a staging deploy. Neskonči po prototypu.
6. Po každé zásadní změně renderuj a prohlédni skutečnou stránku. Nestačí číst JSX nebo zkontrolovat build. Srovnávej před/po při stejném viewportu, datech, filtrech a tématu. Opravuj konkrétní slabiny kompozice, rytmu, hustoty a interakce.
7. Udržuj `docs/design/aaa/progress.md`: hotové části, rozhodnutí, aktuální branch/commit, příkazy a výsledky, otevřené chyby a přesný další krok. Při vyčerpání kontextu pokračuj z tohoto stavu. Průběžně stručně informuj o skutečných zjištěních.

## Povinné změny hlavních obrazovek

**Přehled:** vytvoř výrazný pracovní souhrn čekajících a naléhavých recenzí, prioritní signály a přehledné portfolio aplikací s dostupnými trendy. Problémy klíčů, kanálů a úloh mají být viditelné tam, kde něco vyžaduje řešení. Obrazovka musí dobře fungovat s jednou aplikací i s rozsáhlejším portfoliem. Nesestavuj ji jen ze stejně důležitých karet a nepočítej vymyšlená celková skóre.

**Recenze:** na širokém desktopu skutečný dělený inbox, seznam a detail vedle sebe, s editorem v přímé návaznosti na recenzi. Na mobilu přehledný seznam a samostatný detail se zachováním návratu. Jasně rozliš filtr, vybranou recenzi, stav a primární akci. Zachovej AI návrh, překlad konceptu, šablony, historii, hromadné odložení a klávesy `j/k/r/i/Escape`.

Filtry a vybraný detail musí mít sdílitelný URL stav, refresh i Back. Oprav existující problém, kdy Přehled posílá `urgency=HIGH`, ale inbox naléhavost z URL nečte. Rozpracovaný koncept se při přepnutí recenze nesmí potichu ztratit. Fronta odesílání není publikovaná odpověď; ukaž `PENDING`, `PUBLISHED` a `FAILED` pravdivě. Zohledni přepracované a smazané recenze i konkurenci pouze pro čtení.

**Rozbory:** navrhni přehlednou analytickou kompozici s trendem sentimentu, prioritizovanými tématy, dopadem verzí, trhy a odpovídáním. Každé číslo má kontext období, vzorku a úplnosti. Z analytického problému musí jít přejít na odpovídající recenze. Odděluj store rating, průměr hvězd recenzí, sentiment textových recenzí a náladu všech hodnocení. Neprůměruj průměry bez správných vah.

**Zbytek aplikace:** dokonči Aplikace, detail aplikace, onboarding a store wizard, login/registraci/reset/pozvánku/MFA, výběr organizace, členy a role, audit, zabezpečení, platformní správu a veřejný report. Veřejný report zůstává serverově renderovaným HTML bez JS, se zmrazeným snapshotem, odvolatelným sdílením a kvalitním tiskem do PDF. Současné URL a role musí dál fungovat.

## Vizuální a technické nároky

- Výrazný vlastní charakter, konzistentní typografická hierarchie, tabulární číslice, dobrá hustota, omezená barevnost a grafy s jasnými osami, legendou a dostupným popisem. Každá plocha nemusí být karta.
- Vlastní SVG značka a favicon v rámci názvu appreviewzz. Pro motiv signálu používej SVG/CSS; nesouvisející obrázky neobjednávej. Případný bitmapový asset vytvářej jen tehdy, když má konkrétní roli v návrhu.
- Vyber kvalitní self-hostované písmo s českou diakritikou, licencí a rozumnou velikostí. Podpora WOFF2 MIME v serveru již existuje. Neoslabuj CSP kvůli CDN fontům ani inicializaci tématu.
- Implementuj Světlý / Tmavý / Systém bez probliknutí a ověř obě témata v celém UI.
- Zachovej React, TypeScript, TanStack Query, Ktor a společný Docker image. Nedělej migraci frameworku. Komponenty a CSS uspořádej pro další údržbu, nehromaď protichůdné override styly.
- Vlastní komponenty jsou vhodný výchozí bod. Novou závislost přidej jen s konkrétním přínosem pro chování, přístupnost nebo údržbu. Nová knihovna nesmí určovat generický vzhled výsledku.
- Vycházej ze skutečných API typů a hooků. Nepřidávej tlačítka bez funkce, falešné globální hledání, nepodporované filtry ani umělé metriky. Malá API změna kvůli doloženému UX problému je v rozsahu; větší nový produktový modul dej do backlogu.
- Tenant cache, session cookie, CSRF, role a MFA zůstávají zachované. Přepnutí organizace nesmí ukázat předchozí data. Nepřenášej produkční data do stagingu a neukládej auth tokeny do localStorage.
- Staging může odesílat do skutečných storů. Test odpovídání proto nepouštěj na skutečných zákaznických recenzích. Samotný redesign není pokyn posílat zprávy do Slacku/Teams nebo publikovat libovolnou store odpověď; použij lokální/kontraktní ověření, pokud nemáš výslovně určený testovací cíl a obsah.
- Zachovej české UI a kvalitní českou copy. Nevymýšlej anglické módní názvy pro běžné činnosti. Stávající locale reportů a zpráv zachovej.

## Ověření a definice hotovo

Před dokončením:

1. Projdou `npm run lint`, `npm run typecheck`, `npm run build` v `console` a repozitářem požadovaný `./gradlew build`. Relevantní backendové testy musí pokrýt případné změny reportu/API. Překážky prostředí uváděj přesně.
2. Ověř tři scénáře z plánu: práce s naléhavou recenzí a konceptem odpovědi; téma/verze → zdrojové recenze → Back; připojení aplikace a srozumitelný stav nastavení. Jasně odliš mock test, lokální backend a skutečný staging.
3. Přidej smysluplné browser regresní pokrytí pro URL/urgency, detail a návrat, koncept a stavy odpovědi. Automatické testy doplň ruční vizuální kontrolou a prací z klávesnice.
4. Zkontroluj plná i řídká data, nulové stavy, chyby, neúplnou AI, konkurenci, dlouhé texty, smazané/přepracované recenze, různé role a portfolio více aplikací.
5. Prohlédni skutečné rendery hlavních ploch při 1440, 1280, 1024, 768 a 390 px, nejhustší i při 360 px; obě témata, 200% zoom a tisk reportu. Žádný horizontální overflow celé stránky, nečitelné grafy nebo nedosažitelné akce.
6. Ověř kontrast textu a UI, fokus, dialogy, popisky, klávesové zkratky, reduced motion a nebarevné rozlišení stavů. Proveď automatickou kontrolu přístupnosti a ruční průchod.
7. Porovnej výkon a build assety s baseline; prověř počet dotazů pro více aplikací. Cíle a způsob měření jsou v plánu. Nevydávej laboratorní simulaci za skutečné INP od uživatelů.
8. Připrav přehledné před/po pro Přehled, inbox a Rozbory plus mobil, dark mode a report. Prověř, zda je změna opravdu zásadní i bez spoléhání na novou barvu. Pokud ještě působí jako obecná administrační šablona, uprav návrh a znovu ověř.

Po dokončení kontrol máš v rámci tohoto zadání doručit výsledek na staging podle aktuálního workflow: připrav commit/PR, integruj do staging větve bez force pushe, sleduj CI a deploy a ověř přímo nasazenou aplikaci. Výchozí stav při přípravě byl staging na `epic/v2`; workflow si znovu přečti. Před integrací musí být kontroly hotové, protože CI a deploy nejsou v současném nastavení navzájem blokované. Produkční větev ani tag `prod` neměň.

Zaznamenej předchozí staging SHA a možnost návratu. Po deployi ověř `/health/ready`, `/health/live`, očekávané SHA, přihlášení, přímý refresh podstránky a tři hlavní plochy. Pokud deploy nebo přístup blokuje skutečně chybějící oprávnění, dokonči vše nezávislé, uveď přesnou překážku a konkrétní další krok; neoznačuj úkol za nasazený.

Při přikládání screenshotů k PR použij dostupný skill `pr-shot`; soukromé repo nepovažuj za úložiště příloh přes lokální cesty. Pokud založíš PR, vyplň jeho skutečný výsledný rozsah a ověření podle šablony repozitáře.

Na konci předej staging URL, commit SHA, CI/deploy odkazy, stručný popis výsledku, vizuální srovnání, výsledky ověření, známé nedostatky a postup návratu. Nezastavuj se u formulace „můžu pokračovat“. Úkol je hotový až po doloženém výsledku, nebo po jasně popsané skutečné překážce, kterou bez dalšího vstupu nelze odstranit.
