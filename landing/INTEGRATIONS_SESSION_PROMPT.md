# Rozšíření landing page: integrace, odpovědi přímo z chatu a skutečné ukázky

Toto je implementační zadání pro novou session. **Rozšiř existující novou landing page v `landing/` a dokonči návrh, implementaci i ověření.** Uživatel odchází a vrátí se ráno. Běžná obsahová, designová a technická rozhodnutí dělej samostatně; nečekej na screenshoty, zprovoznění služeb ani schválení kompozice. Chybějící podklady vyřeš hotovým stavem „Coming soon“ a připrav snadné pozdější doplnění.

## Co potřebujeme sdělit

appreviewzz přivádí recenze z App Store a Google Play do nástrojů, ve kterých tým pracuje. **Ze Slacku a Microsoft Teams lze také přímo upravit, schválit a odeslat odpověď zpět do storu.** Návštěvník musí pochopit obousměrnou práci s recenzemi; samotné doručování notifikací hodnotu produktu nevystihuje. AI připraví koncept a člověk má kontrolu nad výslednou odpovědí.

Vedle Slacku a Teams ukaž možnost dalších služeb, ovšem s pravdivě označenou dostupností. Připrav skutečné místo pro screenshoty z jednotlivých integrací, které uživatel dodá, až služby zprovozní. Nová sekce má být plnohodnotná a vizuálně dotažená i tehdy, když zatím nemá žádný screenshot.

Výstupem této session je rozšířená funkční landing page, ne další zadání nebo pouhý návrh.

## Výchozí stav a podklady

Při přípravě 25. 10. 2026 byla aktivní větev `feat/auth-onboarding`. Existovalo nesledované `docs/design/auth-onboarding-prompt.md`; jde o jinou práci. Před začátkem znovu ověř git status a platné instrukce. Zachovej všechny cizí změny.

Nová landing page už existuje, používá Astro + TypeScript, vlastní CSS a anglické texty. Rozvíjej její současný vzhled a příběh „App reviews. Answered. Understood.“; zachovej rozbory, portfolio, CTA a dosavadní funkční ukázky. Veřejný obsah piš anglicky, předání a dokumentaci česky.

Přečti zejména:

- `landing/README.md`, `landing/package.json`, `landing/docs/direction.md`, `landing/docs/claims.md` a `landing/docs/validation.md`.
- `landing/src/pages/index.astro`: hero, navigace, pruh s integracemi a workflow.
- `landing/src/components/ReviewDemo.astro`: existující interaktivní simulace. Přepnutí Slack/Teams nyní mění vybraný přepínač a text doručení; nejde o dvě autentické ukázky rozhraní jednotlivých služeb.
- `landing/src/components/Faq.astro`, `landing/src/components/Icon.astro`, `landing/src/data/site.ts`, `landing/src/styles/global.css` a `landing/tests/smoke.spec.ts`.
- `README.md` (Slack a Teams), `docs/slack-app.md`, `docs/teams-bot.md` a podle potřeby `server/channels/slack/` a `server/channels/teams/`. Zvlášť `SlackBlocks.kt`, `SlackInteraction.kt`, `TeamsCards.kt` a `TeamsActivity.kt` dokládají skutečné chování odpovědí.

Historické promo na https://appreviewzz.com/ při přípravě zdůrazňovalo recenze doručené do chatu, odpovědi přímo ze Slacku/Teams a Discord se stavem „Soon“. Podporuje zamýšlený marketingový příběh. Staré zmínky o n8n, statistiky a provozní dostupnost nejsou autoritou pro nový produkt. Pokud web znovu používáš jako podklad, otevři jej a ověř aktuální obsah.

`landing/SESSION_PROMPT.md` je původní zadání již postaveného webu. Je pouze historický kontext; neopakuj podle něj tvorbu celé landing page, průzkum trhu ani výběr nového stacku. Toto zadání určuje aktuální rozsah.

## Obsah a umístění

1. Zpřesni hero nebo jeho podpůrný text tak, aby bylo explicitní, že tým z chatu i odpovídá zpět do storu. Například „Bring app reviews into your team’s chat. Edit an AI draft, approve it, and send your reply back to the store.“ Výsledný text přizpůsob aktuální dostupnosti služeb; neslibuj aktivní veřejnou integraci jen proto, že pro ni existuje kód.
2. Doplň výraznou sekci **Integrations**, přirozeně navazující na současné demo a workflow. Přidej funkční odkaz do desktopové i mobilní navigace. Pruh log může sloužit jako vstup do této sekce.
3. Ukaž Slack, Microsoft Teams a Discord jako výchozí sadu. Discord je plánovaná služba se stavem „Coming soon“, dokud není skutečně doložena její dostupnost. Další konkrétní značky nepřidávej bez podkladů; rozšiřitelnost zajistí datový model a případný stručný text „More integrations coming soon“.
4. Pro jednotlivou službu ukaž název a značku, dostupnost, krátký konkrétní popis a plochu pro ukázku. Navrhni čitelný přepínač služeb s větším náhledem nebo jinou kompozici vhodnou pro skutečné screenshoty. Screenshot nesmí skončit jako nečitelná miniatura mezi logy.
5. Vysvětli krátkou cestu: nová recenze v kanálu → AI koncept → úprava a schválení přímo v podporovaném chatu → odeslání do storu → viditelný výsledek. Nezaměňuj zařazení do fronty za úspěšnou publikaci. Nepřisuzuj připravované službě hotovou podporu odpovědí.
6. Doplň FAQ o odpovídání přímo z chatu a připravovaných službách. Sjednoť tvrzení napříč hero, pruhem integrací, workflow, demem, novou sekcí, FAQ a případně metadata; nesmí se vzájemně rozcházet v dostupnosti.

## Dostupnost služby a dostupnost screenshotu jsou dva různé stavy

Zaveď jedno přehledné typované nastavení, například `landing/src/data/integrations.ts`. Název souboru můžeš přizpůsobit současné struktuře. Evidence každé integrace má obsahovat stabilní ID, název, ikonu, veřejný stav, stručný popis, podporu přímé odpovědi a volitelná média s cestou, alternativním textem a popiskem. Nezaváděj CMS, databázi ani závislost na produkčním API.

- **Služba připravená k veřejnému použití a screenshot existuje:** zobraz „Available“ a skutečný snímek s popiskem. Podporu odpovědí uváděj podle skutečné schopnosti služby.
- **Služba připravená, screenshot zatím není:** ponech „Available“ a použij hotovou plochu „Preview coming soon“. Chybějící obrázek nesmí označit funkční službu za nedostupnou.
- **Služba ještě není zprovozněná:** zobraz „Coming soon“, stručný popis připravované funkce a navržený prázdný náhled. Nenabízej aktivní připojení ani nefunkční tlačítko. Případný existující screenshot lze ukázat jako náhled připravované funkce; jeho přítomnost sama službu nezapíná.
- **Nesprávná cesta nebo nenačtený obrázek:** zobraz bezpečný fallback bez rozbitého obrázku a posunu layoutu; dostupnost služby zůstává podle konfigurace. Kontrola assetů má chybu také odhalit při ověření.

Uživatel výslovně počítá s tím, že služby teprve zprovozní. Kód Slacku/Teams dokládá schopnost implementace, nikoliv připravenost veřejného provozu. **Pokud při implementaci nemáš doloženou veřejnou dostupnost, nastav i Slack/Teams konzervativně na „Coming soon“.** Nevyžaduj od uživatele noční ověřování. Přechod na „Available“ musí později znamenat jednu jasnou změnu konfigurace. Nedoplňuj termíny vydání ani vymyšlenou roadmapu.

Stav přímo v UI určuj z této společné konfigurace. Neudržuj několik ručních seznamů dostupných služeb. Ukázkové demo může dál vysvětlovat zamýšlený workflow, ale jeho označení musí být srozumitelné i při službách ve stavu „Coming soon“.

## Kam uživatel později vloží screenshoty

Vytvoř a zachovej v Gitu připravené složky:

```text
landing/public/integrations/
  slack/
  teams/
  discord/
```

Prázdné složky zajisti pomocí `.gitkeep` nebo krátké dokumentace. Veřejné URL budou například `/integrations/slack/review.webp` a `/integrations/teams/reply.webp`. Nenuť uživatele na přesný formát; podporuj běžné WebP/PNG/JPEG. U každé služby umožni alespoň snímek doručené recenze a volitelný další snímek editace či výsledku odpovědi. Jediný screenshot musí stačit k plnohodnotné ukázce.

Do `landing/README.md` doplň přesný postup s příkladem konfigurace:

1. Kam vložit screenshot a jak jej pojmenovat.
2. Jak nastavit cestu, rozměry či poměr stran, alternativní text a popisek.
3. Jak přidat druhý obrázek nebo další službu.
4. Jak změnit dostupnost nezávisle na médiích.
5. Jak lokálně zkontrolovat a sestavit statický web; změna souboru či konfigurace se projeví po novém buildu a nasazení.

Doporuč anonymizované snímky bez tokenů, soukromých kanálů a osobních údajů; používej krátké praktické instrukce. Produkční screenshoty teď sám nevyráběj ani nečekej na přístupy do služeb. Existující `landing/docs/screenshots/` slouží k dokumentaci a ověřování webu, není veřejnou galerií integrací.

Skutečné screenshoty zobraz s dostatečnou čitelností, bez deformace a ořezu důležitých ovladačů. Zvaž přístupné zvětšení, pokud zvolená kompozice potřebuje detail. U obrázků rezervuj místo a pod prvním viewportem použij lazy loading. Popisky mají říkat, co návštěvník vidí. Chybějící screenshot nahraď záměrně navrženým stavem, ne nepravým screenshotem vydávaným za skutečný produkt. Existující interaktivní demo zůstává jasně označenou simulací se vzorovými daty.

## Vizuální a technické provedení

Navazuj na Geist, teplé pozadí, inkoustovou typografii a petrolejový akcent. Nová sekce má mít stejnou úroveň zpracování jako současná stránka. Nezvětšuj web dlouhou řadou prázdných panelů; i varianta se všemi službami „Coming soon“ musí mít rozumnou výšku a jasnou informační hodnotu.

Zachovej statické HTML a použitelný obsah bez JavaScriptu. Přepínače, galerie a případné zvětšení musí fungovat klávesnicí a mít viditelný fokus, srozumitelné názvy a správné oznámení aktivního stavu. U dialogu zajisti Escape a návrat fokusu. Respektuj reduced motion a responzivitu. Loga označují integrace, ne zákazníky či oficiální partnerství.

Rozsah implementace drž v `landing/`. Neměň backend, konzoli, OAuth instalace ani infrastrukturu. Landing a její ověření nemají odesílat zprávy do skutečných kanálů ani odpovědi do storů. Nepřidávej nový formulář nebo waitlist bez existujícího funkčního zpracování; případný kontakt použij ze společného `site.ts`. Veřejný deployment a zapnutí služeb nejsou součástí tohoto zadání.

## Postup a podmínky dokončení

Průběžný stav, rozhodnutí a další krok zapisuj do `landing/docs/integrations-progress.md`, aby šlo po přerušení navázat. Proveď skutečné vykreslení v prohlížeči a alespoň jednu vizuální iteraci. Chybějící produkční screenshoty ani nepotvrzená dostupnost neblokují dokončení sekce.

Hotovo znamená:

- Z prvního zobrazení a workflow je jasná práce s odpověďmi přímo z podporovaného chatu; plánované služby jsou poctivě označené.
- Existuje nová přístupná sekce integrací, funkční navigace, připravené složky a společná konfigurace pro snadné budoucí doplnění screenshotů a dalších služeb.
- Ověříš všechny kombinace dostupnosti a médií popsané výše, včetně chybného obrázku. Dostupná integrace bez snímku má „Preview coming soon“; připravovaná integrace se snímkem zůstává „Coming soon“.
- Pro obrazovou větev můžeš použít dočasný lokální testovací obrázek. Označ jej jako testovací, po ověření jej z veřejných dat odstraň; nevydávej screenshot současné landing page za skutečnou ukázku Slacku/Teams.
- Rozšiř existující Playwright smoke testy o smysluplné chování nové sekce, přepínání, dostupnost a fallback médií. Zachovej testy editace, schválení, simulované publikace a chyby v dosavadním demu; selektory uprav, pokud se oprávněně změní přístupné názvy.
- V `landing/` projdou `npm run check`, `npm run lint`, `npm run build` a `npm run test:browser`. Závislosti instaluj přes `npm ci`, jen pokud je to potřeba. Ověř skutečné produkční preview, chyby konzole, odkazy a chybějící assety. Kvůli této změně nespouštěj Kotlin testy.
- Prohlédni výsledek na 1440, 1280, 768, 390 a 360 px, s klávesnicí, 200% zoomem a bez JavaScriptu; bez horizontálního přetékání a nečitelných ukázek. Pro novou sekci použij také existující automatickou kontrolu přístupnosti.
- Do `landing/docs/screenshots/integrations/` ulož reprezentativní desktop a mobil včetně výchozího stavu „Coming soon“ a prohlédni je. Pokud ověřuješ obrazovou větev testovacím médiem, výsledek odděl a jasně popiš jeho původ.
- Aktualizuj `landing/README.md`, `landing/docs/claims.md` a doplň skutečné výsledky ověření do `landing/docs/validation.md`. Zaznamenej, jaké výchozí stavy jsi zvolil a proč.

Na závěr česky předej, co je hotovo, přesnou cestu pro screenshoty, konkrétní konfigurační soubor a krátký příklad zapnutí jedné služby. Uveď výsledky kontrol a zbývající omezení. Nestačí úspěšný build bez prohlédnutí výsledku. Neočekávej uživatelovu reakci, abys mohl dokončit běžnou implementaci.
