# Zadání pro novou session: AAA landing page appreviewzz

Toto je implementační zadání. Převezmi roli seniorního produktového marketéra, art directora a frontend engineera a **samostatně navrhni, implementuj a ověř profesionální landing page pro appreviewzz v samostatné složce `landing/` tohoto repozitáře**. Doruč hotovou stránku, ne pouze plán, wireframe nebo návrh textů. Uživatel odchází od počítače; běžná rozhodnutí dělej sám a pokračuj až do dokončení. Chybějící nepovinný podklad nahraď rozumnou, zdokumentovanou volbou.

## Cíl a rozsah

Landing page má návštěvníkovi během několika sekund vysvětlit, co appreviewzz dělá, pro koho je a proč ho používat. Má přesvědčivě převést zájem k registraci nebo kontaktu. Ambicí je svébytný, důvěryhodný SaaS web s úrovní zpracování odpovídající špičkovému produktu.

Vše nové ukládej do `landing/`, včetně balíčků, lockfilu, assetů, dokumentace a ověření. Landing musí jít samostatně spustit a sestavit bez Kotlin serveru, databáze, přihlášení a store klíčů. Existující konzole je produktový a vizuální podklad; její redesign není součást tohoto úkolu. Případná starší zadání v `docs/design/` čti jako kontext, ne jako pokyn provádět jejich implementaci či deployment.

Primární marketingový jazyk zvol **angličtinu**, protože produkt míří i mimo ČR a současný web je anglicky. Dokumentaci a závěrečné předání piš česky. Plnohodnotná druhá lokalizace není podmínkou; upřednostni dokonale dotaženou anglickou stránku.

## Nejdřív zjisti skutečný stav

1. Přečti případné platné `AGENTS.md`, `README.md`, `CONTRIBUTING.md`, `console/README.md`, `docs/design/aaa/direction.md` a relevantní části skutečného kódu.
2. Zkontroluj git status a obsah `landing/`. Při přípravě zadání byla aktivní větev `epic/v2` a existovaly rozpracované změny v konzoli a designových dokumentech. Zachovej je a znovu ověř aktuální stav. Neswitchuj bez rozmyslu větev se změnami a nepřidávej cizí změny do svého commitu. Pokud je nutná izolace, použij samostatný worktree. Tento soubor zadání zachovej.
3. Projdi https://appreviewzz.com/ a https://appreviewzz.com/get-started/ jako inspiraci a historické podklady. Nová stránka může mít zcela jinou kompozici, texty i příběh. Současný web není autorita pro aktuální funkce ani důkaz marketingových čísel.
4. Prohlédni aktuální značku, font a produktové obrazovky. Podklady jsou zejména `console/src/components/ui.tsx`, `console/public/favicon.svg`, `console/public/fonts/`, `console/src/styles/tokens.css`, `console/src/pages/Dashboard.tsx`, `Inbox.tsx` a `Analysis.tsx` a API typy. Když není dostupný přihlášený produkt, pokračuj podle kódu a označených ukázkových dat.
5. Doplň omezený průzkum 3–5 kvalitních aktuálních SaaS landing pages a oficiálních prezentací obdobných produktů. Zapiš konkrétní návrhové závěry a zdroje; nekopíruj cizí branding, obrázky ani texty. Průzkum časově omez, aby zbyl prostor na implementaci a vizuální iterace.

## Co produkt skutečně nabízí

Při přípravě zadání 25. 10. 2026 README a kód dokládaly tyto schopnosti. Před zveřejněním konkrétního tvrzení ověř aktuální implementaci a případné omezení:

- Recenze vlastních aplikací z Google Play a App Store na jednom místě, doručování do Slacku a Microsoft Teams.
- Návrh odpovědi od AI, možnost úpravy a schválení člověkem, odeslání zpět do storu. Rozlišuj zařazení k odeslání, publikaci a případnou chybu.
- Webový inbox s filtry, AI návrhem na vyžádání, šablonami a překladem konceptu do jazyka recenzenta.
- Denní přehled store hodnocení a jejich změn; rating storu není totéž jako průměr hvězd vybraných recenzí.
- Rozbory témat, sentimentu, naléhavosti, trendů a dopadu vydaných verzí; cesta z rozboru na zdrojové recenze.
- Upozornění na neobvyklý nárůst negativních recenzí nebo konkrétního tématu.
- Více aplikací a organizací, týmové role, self-serve onboarding, zabezpečení účtu včetně TOTP.
- Sdílitelný měsíční report pro klienta s tiskem do PDF; dostupnost může záviset na tarifu.
- Sledování konkurence má omezení podle storu: veřejné recenze App Store, u Google Play pouze dostupná veřejná hodnocení. Na recenze konkurence se neodpovídá.
- Volitelné automatické poděkování jen za vyhovující pozitivní recenze, až po zapnutí. Hlavní příběh stav na odpovědích pod kontrolou týmu; neslibuj bezvýhradné automatické odpovídání na všechno.

Současný web zmiňuje n8n, ale aktuální produkt je vlastní aplikace. Nepřebírej tvrzení „powered by n8n“. Discord není doložená dostupná integrace a na nové landing page ho nenabízej jako hotovou funkci. Parametry AI providerů a interní infrastrukturu neprezentuj jako hlavní důvod nákupu.

Pozor: README může používat starší názvy tarifů Insights/Agency. Při přípravě zadání obsahoval `server/core/src/main/kotlin/cz/matee/appreviewzz/core/model/Enums.kt` hodnoty `STARTER`, `REGULAR`, `ENTERPRISE` a komentář, že billing a limity ještě nejsou zavedené. **Nevymýšlej veřejné ceny, trial, bezplatný tarif, limity ani slib „no credit card required“.** Bez ověřené nabídky cenové karty vynech a použij kontakt.

Produktový jazyk: recenze nazývej reviews / feedback, ne signals. Grafický motiv značky může zůstat. Uživatelsky schválený český směr říká „Každá recenze je zpětná vazba.“

## Positioning a obsah

Primární publikum: týmy mobilních aplikací, lidé zodpovědní za zákaznickou podporu a produkt. Důležitá druhá skupina: mobilní agentury spravující více aplikací pro klienty.

Hlavní příběh: **recenze se dostanou k týmu tam, kde pracuje → tým rychleji připraví a schválí odpověď → z opakující se zpětné vazby pozná, co opravit a jak dopadla nová verze.** Prodej konkrétní přínos a ukaž důkaz v podobě produktu. AI je prostředek k práci, ne obsah celé stránky.

Možný výchozí headline: „App reviews. Answered. Understood.“ Není povinný; navrhni lepší, pokud jasněji pojmenuje produkt a hodnotu. Podtitulek musí explicitně vysvětlit App Store + Google Play, Slack/Teams a práci s odpověďmi či rozbory. Vyhni se abstraktním sloganům a nepodloženým příslibům růstu.

Navrhni délku a pořadí podle dobrého příběhu. Očekávané obsahové role:

1. **Navigace a hero:** jasná kategorie produktu, přesvědčivý nadpis, konkrétní podtitulek, jedna hlavní CTA a vedlejší přechod na ukázku. Výrazná, dobře čitelná produktová vizualizace.
2. **Produkt v akci:** krátká cesta konkrétní recenze do Slacku/Teams, upravitelný AI koncept a schválení; druhá část ukáže téma nebo problém po vydání verze a zdrojové recenze. Výborně fungující malá interaktivní ukázka má přednost před velkým dashboardem s nečitelnými detaily.
3. **Proč se tím zabývat:** přehlédnuté stížnosti, přepínání konzolí, opakující se problémy ukryté v recenzích. Propoj každý problém s konkrétní schopností a přínosem produktu.
4. **Rozbory a portfolio:** viditelnost problémů, dopad verzí, přehled více aplikací a report pro klienta. Vyber několik silných schopností, nevypisuj celou administraci.
5. **Podložený důvod odpovídat:** použij jen ověřená a správně interpretovaná data nebo kvalitní vysvětlení bez procent. Nemusí z toho být povinná řada čtyř metrik.
6. **Jak začít:** přidat aplikaci a připojit potřebné oprávnění ke storu, zvolit kanál, začít zpracovávat recenze. Neslibuj nastavení za konkrétní počet minut bez důkazu.
7. **Důvěra a FAQ:** lidské schválení, potřebná oprávnění, podporované integrace, více aplikací, rozdíl mezi ratingem a textovými recenzemi, AI a ochrana přístupových údajů. Jen doložené vlastnosti; žádné vymyšlené certifikace.
8. **Závěrečná CTA a footer:** další krok, přihlášení, skutečný kontakt, autorství Matee, relevantní existující odkazy.

Místo falešných referencí použij kvalitní produktové ukázky a jasně doložené schopnosti. Loga Slack/Teams/App Store/Google Play označují integrace, nikoli zákazníky nebo partnerství. Žádná smyšlená hodnocení, počet uživatelů, zákaznická loga, jména, testimonials, ocenění ani „trusted by“.

## Statistiky a zdroje: připravené výchozí ověření

Veď `landing/docs/claims.md`: každé významné tvrzení, zdroj (soubor nebo přímá URL), datum ověření, omezení a výsledná formulace. U veřejně použitých čísel nabídni čitelný odkaz na zdroj a stručný kontext přímo na stránce.

- **+0,7 hvězdy — nalezen primární zdroj:** Google, 18. 5. 2016, https://android-developers.googleblog.com/2016/05/whats-new-in-google-play-at-io-2016.html . Pozorování se týká **uživatelů, kteří po odpovědi vývojáře změnili své hodnocení**, nikoli zvýšení celkového ratingu aplikace o 0,7. Pokud údaj použiješ, zachovej tento vzorek a historické datum. Jde o pozorování Googlu, ne výsledek appreviewzz a ne garanci. Další oficiální podklad: https://google.play/business/ratings/ . Zvol formulaci odpovídající ověřenému zdroji, i kdyby číslo mělo méně výraznou roli.
- **Apple — kvalitativní podklad:** https://developer.apple.com/app-store/ratings-and-reviews/ vysvětluje význam recenzí, odpovědí a možnost uživatele recenzi po oznámení odpovědi upravit. Použitelné pro vysvětlení přínosu, ne jako podklad pro libovolné procento.
- **+89 % konverze — zatím neověřeno:** současný web odkazuje na https://www.alchemer.com/resources/blog/why-app-ratings-are-critical-to-aso-marketing-strategy/ . Přípravná session nedoložila přesné tvrzení ani metodiku. Pokud jej chceš použít, najdi původní výzkum a ověř rok, vzorek, metriku a srovnání 3 vs. 4 hvězdy. Nesmí působit jako efekt samotného odpovídání nebo appreviewzz.
- **+30 % retence a 45 % důvěry — zatím neověřeno:** samotné uvedení na starém webu nestačí. Bez dohledatelného kvalitního výzkumu a přesného kontextu vynechat. Stejně postupuj u případných dalších čísel.

Nepotřebujeme zachránit všechna původní čísla. Jeden poctivě vysvětlený údaj a přesvědčivá ukázka produktu mají větší hodnotu než sada zavádějících procent.

## Vizuální zadání

Navazuj na existující značku, ale vytvoř marketingovou kompozici se silnější hierarchií a dostatkem prostoru. Aktuální produkt používá Geist, teplé světlé pozadí `#f5f4f0`, inkoust `#172724`, petrolejový akcent `#146c60` a vlastní SVG značku tří svislých odezev a bodu. Zachovej rozpoznatelnost; konkrétní marketingovou paletu a rytmus můžeš rozvinout.

- Připrav dvě rychlé vizuální kompozice hero se stejným obsahem, skutečně je vyrenderuj a sám vyber silnější. Není potřeba čekat na uživatele ani stavět dvě kompletní stránky.
- Velká, přesná typografie, promyšlená šířka textu, omezená paleta, jemná práce s linkami a plochami. Kompozice má mít rytmus; různé sekce nemusí mít stejný layout.
- Produktové ukázky a diagramy vytvářej jako HTML/CSS/SVG podle skutečného produktu. Pro návštěvníka musí být čitelné i na mobilu. V syntetických datech udržuj stejné názvy aplikací, období a navazující hodnoty.
- Ukázky označ jako „Interactive demo · sample data“ nebo odpovídající stručnou formulací. Pokud demo simuluje schválení, stav musí říkat, že jde o simulaci; nic skutečně nepublikuj. Případnou ilustraci neoznačuj jako screenshot skutečně naměřeného zákaznického výsledku.
- Vyhni se generické sestavě hero + tři stejné karty + gradientová koule, nadbytečným glass efektům, dlouhému scrollování bez obsahu a drobným nečitelným mockupům.
- Pohyb jen tam, kde vysvětluje průchod produktem nebo podporuje orientaci. Respektuj reduced motion, žádný scroll hijacking ani povinné dlouhé animované intro. Výchozí obsah musí být vidět i při selhání skriptu.
- Použij existující licencovaný font a značku; zkopíruj potřebné assety do `landing/` včetně licence, nepřipojuj je přes cestu závislou na běhu konzole. Obrázkové generování použij jen pro konkrétní smysluplný bitmapový motiv a podle dostupného skillu. Pro logo a produktové UI je vhodnější kód/SVG.

## Implementace a skutečné chování

Vyber lehký stack pro staticky generovaný marketingový web. Doporučený výchozí bod je Astro + TypeScript + vlastní CSS, s malými interaktivními částmi podle potřeby. Pokud existující obsah `landing/` ukazuje vhodný jiný směr, respektuj jej. Důležitý obsah musí být v HTML z buildu; nevytvářej čistě klientsky renderovanou marketingovou SPA s prázdným prvním HTML. Ověř aktuální dokumentaci zvolených nástrojů a použij stabilní kompatibilní verze.

- Přehledné komponenty, designové tokeny a oddělená demo data. Zachovej přiměřený počet závislostí a minimum klientského JavaScriptu.
- Samostatné příkazy alespoň `npm ci`, `npm run dev`, `npm run build`, `npm run preview` a kontrola typů/lintu podle stacku. Ulož lockfile, lokální `.gitignore` a stručné README. Port zvol volný, aby nekolidoval s konzolí.
- Hlavní CTA musí vést na skutečný další krok. V kódu existuje registrační cesta `/registrace`, přihlášení `/login`; produkční základna podle deployment dokumentace je `https://console.appreviewzz.com`. Znovu ověř veřejnou dostupnost těchto cest. Pokud registrace není funkční, použij skutečný kontakt nebo existující setup guide, ne falešnou registraci. U cílových URL použij jedno snadno změnitelné nastavení bez tajemství.
- Sekundární CTA může scrollovat na demo. Navigace, mobilní menu, FAQ, přepínače ukázky a všechny odkazy musí fungovat. Případný formulář potřebuje skutečně dostupné zpracování; bez něj použij ověřený kontaktní odkaz. Repo uvádí `info@matee.cz`; další adresu si nevymýšlej. Žádná falešná hláška o odeslaném formuláři.
- Landing page nemá vyžadovat přístup k produkčnímu API. Demo je lokální, bez store credentials a bez skutečných odpovědí nebo zpráv do Slacku/Teams.
- Sémantické HTML, jedno H1, správná hierarchie nadpisů, landmarky, popisky ovladačů, viditelný focus, plná obsluha klávesnicí a přístupné mobilní menu/FAQ. Cíl WCAG 2.2 AA v rozsahu stránky; automatický test sám o sobě neznamená certifikaci.
- SEO: konkrétní title a description, správný `lang`, canonical pro zamýšlenou produkční doménu, favicon, Open Graph/Twitter metadata, hotový sdílecí obrázek s rozumnou velikostí, robots a sitemap. Structured data jen pro skutečně doložené údaje; bez fiktivních cen a aggregateRating. Preview prostředí nemá nechtěně indexovat kopii produkčního webu; způsob nastavení zdokumentuj.
- Optimalizuj fonty, obrázky, rozměry médií a načítání pod foldem. Žádný autoplay velkého videa, zbytečné tracker skripty nebo externí fontové CDN. Nepřidávej cookie banner bez reálného důvodu. Vzhled i hlavní obsah mají fungovat bez JavaScriptu; demo může mít srozumitelný statický fallback.
- Nevymýšlej právní texty. Právní odkazy používej jen na existující relevantní dokumenty; neodkazuj na `#` ani na neexistující stránky.

## Postup, který dokončíš bez čekání

1. Krátký produktový audit a obsahová strategie do `landing/docs/direction.md`; zapiš cílového návštěvníka, hlavní sdělení, CTA a zvolený vizuální směr.
2. Dvě rychlé hero varianty, render a vlastní výběr. Potom dokonči jeden celý web.
3. Implementace všech sekcí, responzivity, ukázek, metadat a finálních textů. Žádné TODO, lorem ipsum, mrtvá tlačítka nebo zjevné placeholdery v hotové stránce.
4. Skutečné renderování v prohlížeči, vizuální kontrola a alespoň jedna vědomá iterace podle zjištěných slabin. Nestačí úspěšný build ani přečtení zdrojového kódu.
5. Funkční, přístupnostní a výkonnostní ověření, oprava zjištěných problémů a finální dokumentace.

Udržuj stručný `landing/docs/progress.md` se stavem, rozhodnutími, výsledky a dalším krokem, aby šlo po zkrácení kontextu pokračovat. Dlouhý audit nemá přednost před dokončením stránky. Nemůžeš-li ověřit jeden volitelný claim, vynech jej a pokračuj. Nemůžeš-li otevřít vzdálený produkt, dokonči samostatný web s poctivě označenou ukázkou.

## Definice hotovo a předání

Hotovo je až tehdy, když:

- Stránka je kompletní od hero po footer, přesvědčivá pro cílového návštěvníka a vizuálně dotažená v celém rozsahu.
- Reprodukovatelně projde čistá instalace závislostí, kontrola typů/lint a produkční build uvnitř `landing/`. Ověř přímo produkční preview, nejen dev server.
- V prohlížeči prověříš desktop 1440 px, notebook 1280 px, tablet 768 px a mobil 390 a 360 px. Zkontroluj celý web, dlouhé texty, 200% zoom, mobilní navigaci, fokus a reduced motion. Žádný overflow celé stránky ani překryté CTA.
- Prověříš všechny CTA, navigaci, FAQ a případné demo; žádné chyby konzole, chybějící assety nebo neplatné interní odkazy. Přidej přiměřený browser smoke test těchto důležitých cest, ne testy pro každý odstavec.
- Provedeš automatickou kontrolu přístupnosti a ruční průchod klávesnicí. Výsledky a skutečný rozsah uvedeš v `landing/docs/validation.md`.
- Změříš Lighthouse na produkčním preview v mobilním profilu. Cíl: Performance ≥ 90, Accessibility ≥ 95, Best Practices a SEO ≥ 95; LCP ≤ 2,5 s a CLS ≤ 0,1 v uvedeném laboratorním nastavení. Uveď nástroj, konfiguraci a výsledek. Nevydávej laboratorní měření za reálná uživatelská data; pokud limit nesplníš, optimalizuj a popiš konkrétní zbývající problém.
- Uložíš reprezentativní screenshot desktopu a mobilu do `landing/docs/screenshots/`. Skutečně je prohlédni. Zachyť alespoň hero a celou stránku; jen dostupnost souboru není vizuální kontrola.
- `landing/docs/claims.md` obsahuje doložení veřejných tvrzení a zaznamenává, které původní statistiky byly vynechány. `landing/README.md` vysvětluje spuštění, build, preview, konfigurovatelné URL a nasazení statického výstupu, včetně rozdílu mezi preview a produkcí.
- Ověříš git diff, že změny jsou omezené na `landing/` a původní rozpracovaná práce zůstala zachovaná. Testy celého Kotlin backendu kvůli samostatnému marketingovému webu nespouštěj, pokud je nevyžaduje platná instrukce repozitáře; povinný check skutečně zkus a případnou překážku přesně zaznamenej.

Nasazení na veřejnou doménu, merge do sdílené větve ani změny DNS nejsou součástí tohoto zadání. Připrav kompletní samostatně spustitelný výsledek a přesný postup nasazení. Samotné lokální preview nenazývej veřejným deploymentem. Nevytvářej externí komunikaci nebo issue jen kvůli běžné přípravě této landing page; uživatel už zadal tento konkrétní rozsah práce.

Na konci předej česky: co vzniklo a jaké sdělení web prodává, přesný příkaz pro spuštění a lokální URL, cesty k výsledným souborům a screenshotům, výsledky kontrol a měření a případné konkrétní omezení. Neskonči u nabídky, že implementaci teprve uděláš. **Doruč hotovou, vizuálně zkontrolovanou landing page ve složce `landing/`.**
