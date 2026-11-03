# Zadání pro autonomní session: profesionální přidání aplikace a připojení storů

Jsi seniorní produktový designér, UX writer a full-stack engineer. Pracuješ na appreviewzz, produktu pro práci s recenzemi aplikací. Přidání aplikace a připojení storů je kritická aktivační cesta. Tvým úkolem je současný průchod analyzovat, navrhnout výrazně lepší řešení, implementovat ho a ověřit jako hotovou produktovou funkci na úrovni AAA SaaS.

Uživatel je mimo počítač. Běžná rozhodnutí o UX, textech, komponentách, architektuře a testech dělej autonomně. Nečekej na schválení návrhu, neodevzdávej jen audit nebo prototyp. Toto zadání autorizuje implementaci a potřebné lokální ověřování. Dokonči funkční výsledek a připrav ho k review. Merge a deploy nejsou součástí zadání. Případnou nedostupnost externích účtů odděl od práce, kterou můžeš dokončit lokálně.

## 1. Co má uživatel zažít

Po kliknutí na „Přidat aplikaci“ se otevře souvislý, přehledný průvodce. Člověk bez znalosti naší architektury pozná:

- Kde právě je, co už dokončil a co ho ještě čeká.
- Co má udělat tady a co v externí konzoli, kam přesně přejít a co tam vyplnit.
- Jaký účet a oprávnění potřebuje, proč je žádáme a jaký dopad má jeho volba.
- Co jsme skutečně uložili a ověřili, co čeká na store a co může dokončit později.
- Jak pokračovat po chybě, návratu z cizí záložky, zavření nebo obnovení stránky.

Vizuální úroveň musí být viditelná v kompozici, typografii, detailech interakcí i kvalitě obsahu. Každý krok má jeden jasný účel a hlavní akci. Návod nesmí předpokládat, že člověk ví, co je Issuer ID, service account nebo reporting bucket.

## 2. Podklady a zjištěný výchozí stav

Nejprve ověř aktuální větev, pracovní změny a pravidla projektu včetně případných `AGENTS.md`. Při přípravě 26. 10. 2026 byla větev `epic/v2`; nesledované `docs/design/auth-onboarding-prompt.md` a `landing/INTEGRATIONS_SESSION_PROMPT.md` patřily k jiné práci. Tento soubor a složka `add-app-wizard/` jsou nové podklady. Cizí práci zachovej.

Přečti `CONTRIBUTING.md`, `console/README.md`, `docs/design/aaa/direction.md` a aktuální dokumentaci v `docs/design/auth-onboarding/`. Starší zadání onboardingu není popisem dnešního stavu: onboarding už má pět kroků, backendovou persistenci a skutečné stavy první synchronizace. Navazuj na existující implementaci.

Mapa relevantního kódu:

| Oblast | Výchozí soubory |
| --- | --- |
| Přidání aplikace, seznam a detail, pokročilé klíče | `console/src/pages/Apps.tsx` — zejména `AddAppDialog`, `AppFormFrame`, `AppStatus`, `SetupTodos` |
| Google Play a App Store průvodce | `console/src/components/ConnectStoreWizard.tsx` |
| Texty a externí odkazy | `console/src/components/connectStoreCopy.ts` |
| Existující aktivace a opětovné připojení | `console/src/pages/Onboarding.tsx` — `StoreConnection`, použití `AddAppDialog` a `ConnectStoreWizard` |
| API a cache | `console/src/api/hooks.ts`, `types.ts`, `onboarding.ts`, `client.ts` |
| Dialog a vzhled | `console/src/components/ui.tsx`, `console/src/styles/` |
| Serverové kontrakty | `server/app/src/main/kotlin/cz/matee/appreviewzz/app/{AppRoutes,CredentialRoutes,OnboardingRoutes}.kt` |
| Skutečné možnosti storů | `server/connectors/appstore/`, `server/connectors/googleplay/`, související use cases a modely v `server/core/` |
| Regrese | `console/e2e/setup.spec.ts`, `onboarding.spec.ts`, `onboarding-fixtures.ts`, `onboarding-visual.spec.ts`, `fixtures.ts`; relevantní backendové testy |

Konkrétní pozorování z přípravy, která musíš při auditu ověřit:

- `AddAppDialog` po vytvoření aplikace zavře jeden dialog a otevře `ConnectStoreWizard`. Při dvou platformách vybírá nejprve Google Play; celý postup nemá společný rámec ani závěrečný přehled obou platforem.
- Standardní `Modal` má šířku maximálně 560 px, ve variantě wide 760 px. Dlouhé návody tak sdílejí prostor s formulářem a akcemi v úzkém sloupci. Backdrop aktuálně dialog zavírá.
- Google Play má větve `app → scope → invite → check → reporting`, některé podmíněné; App Store `create → upload → pick`. Chybí společná navigace zpět. Komentář v Google větvi výslovně odmítá ukazatel kroků kvůli podmíněnému průchodu. Vyřeš to přehlednými fázemi a dynamickým postupem a komentář aktualizuj.
- Texty střídají tykání a vykání a technická vysvětlení někdy převažují nad konkrétním návodem.
- ASC návod má neuzavřenou poznámku k roli a doporučuje „Customer Support; pokud není, Admin“. Nedělej z toho neověřený požadavek nového UX.
- Google varianta pro hodnocení žádá i `Reply to reviews`, ale doprovodný text tvrdí, že práva zůstávají čtecí. Takový rozpor musí zmizet.
- Google provisioning je v UI spuštěný při vstupu do kroku. Návraty, retry, StrictMode a obnovení nesmí vytvořit další prostředky.
- Ověření klíče je v produktu vázané také na konkrétní aplikaci. Globální úspěch credentialu nedokazuje přístup ke všem aplikacím.
- ASC už podporuje výběr více aplikací a výsledky po položkách; nepřijít o tuto schopnost při zjednodušení UI.

Tato pozorování vycházejí ze čtení kódu. Skutečný baseline si vykresli a prohlédni v prohlížeči.

## 3. Referenční obrázky a vizuální směr

Uživatel dodal dvě ukázky jiného produktu; kopie jsou součástí repozitáře, takže nová session nepotřebuje původní přílohy:

- `docs/design/add-app-wizard/references/wizard-choices.png`
- `docs/design/add-app-wizard/references/wizard-permissions.png`

Prohlédni oba obrázky. Přenes principy: stabilní rámec, přehled fází v levém panelu, aktuální a dokončený krok, výrazný nadpis a krátké vysvětlení, srozumitelné volby s důsledky, trvale dostupná navigace a hlavní akce. Neopisuj Airtable/MCP/CLI obsah ani cizí vizuální styl. Doporučenou volbu označ jen tehdy, když pro ni existuje produktový důvod.

Navazuj na schválený směr produktu: Geist, inkoustové a teplé neutrální tóny, petrolejový akcent, současné tokeny, světlé i tmavé téma. Recenze nenazývej „signály“.

Na desktopu navrhni dostatečně široký dialog pro navigaci a čitelný obsah; výchozí úvaha je přibližně 960–1120 px, konečný rozměr podlož skutečným renderem. Obsah má omezenou délku řádku. Hlavní plocha může scrollovat, záhlaví a akce mají zůstat použitelné. Nevynucuj stejnou výšku všech kroků za cenu velkých prázdných ploch.

Na mobilu použij kompaktní ukazatel fáze a dialog přes dostupnou plochu, se zohledněním safe areas, nízkého viewportu a softwarové klávesnice. Patička nesmí překrývat pole ani chyby. Dlouhý e-mail, URI nebo název aplikace nesmí rozbít layout. Nepřidávej dekorativní ilustrace, které berou místo návodu.

## 4. Produktový rozsah a navržený průchod

Nejprve sepiš mapu stavů a výslednou strukturu, pak ji implementuj. Následující fáze jsou výchozí návrh, který můžeš zlepšit na základě auditu. Počet kroků není cíl; srozumitelnost a úplnost ano.

### A. Aplikace

- Srozumitelně rozliš vlastní aplikaci a sledování konkurence. Onboarding první vlastní aplikace nesmí nabízet konkurenci jako cestu k aktivaci.
- U vlastní aplikace podporuj Google Play, App Store i jejich kombinaci. Vložení store odkazu má být hlavní cesta; identifikátory a ruční nastavení patří do přiměřeně dostupného pokročilého režimu.
- Ověř odkazy, ukaž rozpoznaný název, platformu a identifikátor; název lze upravit. Vysvětli rozdílné názvy dvou listingů a nech člověka potvrdit, že patří k jedné aplikaci. Nevytvářej nové lookup funkce bez potřeby.
- Rozliš načítání, neplatný odkaz, nedostupný lookup a existující aplikaci. Pozdní odpověď nesmí přepsat novější vstup ani ručně upravený název.
- Existující aplikaci nabídni znovu otevřít nebo pokračovat v jejím připojení. Při shodě jen jedné platformy nevytvářej druhou kopii ani automaticky nespojuj nesouvisející aplikace. Zvládni i serverový konflikt při souběhu.
- Konkurence dostane stručnou větev bez vlastních klíčů a bez připojování storu, s pravdivým vysvětlením dostupných veřejných dat a omezení. Nedoplňuje aktivaci vlastní aplikace.

### B. Přehled připojení a předpoklady

- Ukaž, které platformy se budou připojovat a jejich samostatné stavy. Po dokončení Androidu nabídni iOS v témže průchodu a naopak.
- Před externími úkony stručně vysvětli, co bude potřeba: přístup do konkrétní konzole, odpovídající role, kdo může pomoci, pokud uživatel práva nemá. Nevymýšlej čas dokončení.
- Nabídni použitelný existující klíč či účet ve správné organizaci, nebo vytvoření nového připojení. Volba existujícího klíče stále vyžaduje ověření pro cílovou aplikaci.
- U detailu existující aplikace začni příslušnou platformou; nenuť člověka znovu zadávat identitu aplikace. Zachovej možnost opravy nefunkčního připojení.

### C. Google Play

- Zachovej náš spravovaný service account jako hlavní jednoduchou cestu, existující credential reuse a pokročilou cestu s vlastním účtem/klíčem v podporovaném rozsahu. Nedělej uživatele správcem GCP, pokud to daná cesta nevyžaduje.
- Vysvětli rozdíl „Recenze“ a „Recenze a oficiální hodnocení“: co získá, jaká práva jsou potřebná a jak se liší konfigurace. Odděl možnost odpovídat od tvrzení o čtecích právech. Žádej nejmenší oprávnění, která skutečně potřebujeme pro podporované funkce.
- E-mail k pozvání zobraz s bezpečně fungujícím kopírováním, potvrzením úspěchu i řešením nedostupné clipboard API. Nepředstírej zkopírování po chybě.
- Návod rozděl na konkrétní očíslované akce v Play Console; vždy jasně uveď stránku, záložku, pole, hodnotu a potvrzovací tlačítko. Odkaz otevři v nové záložce, zachovej práci v průvodci.
- Ověření rozlišuje úspěch, propagaci oprávnění, chybějící práva, chybnou aplikaci a dočasnou nedostupnost. Ukaž konkrétní nápravu a možnost zkusit znovu. Automatické ověřování nesmí spouštět překrývající se požadavky ani pokračovat po odchodu z kroku.
- Reporting pro oficiální hodnocení a dostupnou historii vysvětli samostatně: odkud URI získat, jak jej zkopírovat, kam patří další oprávnění, jak poznat úspěch a co znamená chybějící export. Technický pojem vysvětli u pole. Ověř skutečný mechanismus přístupu před psaním instrukcí.
- Reporting lze odložit. Rozliš uložení adresy, dostupný bucket bez dat a funkční export. Samotná uložená adresa není úspěšné připojení hodnocení. Zachovej věcně opodstatněné varianty „Uložit i tak“ s jasným důsledkem.

### D. App Store Connect

- Předveď přesnou cestu k podporovanému API klíči: která sekce, typ klíče, kdo ho smí vytvořit, název, role, stažení a umístění identifikátorů. Vysvětli rozdíl oprávnění člověka vytvořit klíč a oprávnění výsledného klíče.
- Ověř dnešní role a omezení Team Keys / Individual Keys. Stávající větu s fallbackem na Admin nepřebírej bez ověření. Individuální klíč nesmí být vydáván za ekvivalent týmového, pokud neumí potřebný výpis aplikací.
- Jednorázové stažení `.p8` vysvětli před akcí. Nabídni rovnou „Klíč už mám“, aby zkušený uživatel nemusel potvrzovat fiktivní vytvoření dalšího.
- Upload zvládne výběr souboru i drag and drop, poškozený nebo nesprávný soubor, čitelnou chybu načtení a nahrazení souboru. Omez nepřiměřenou velikost. Po změně souboru nesmí zůstat neplatné odvozené údaje z předchozího.
- Key ID předvyplň z názvu, pokud je rozpoznatelný; ponech možnost opravy a validuj deklarovaný formát. Issuer ID opatři konkrétním návodem, kde ho najít, a příkladem označeným jako příklad.
- Jasně vysvětli, co se s klíčem stane, ale jen podle ověřeného chování serveru. Obsah klíče nikdy nezobrazuj v souhrnu.
- Zachovej výpis a výběr více dostupných aplikací i připojení již existující aplikace. U opravy konkrétní aplikace drž správný kontext. Rozliš prázdný seznam, jiný tým, chybějící přístup, chybu API a částečný úspěch hromadného přidání.
- Opakování po částečném úspěchu musí bezpečně navázat na hotové položky; nevyžaduje nový klíč ani znovu nezakládá aplikace.

### E. Výsledek a další krok

- Závěrečný souhrn ukáže aplikaci a výsledek každé platformy, včetně čekajících nebo odložených částí. Úspěšný badge se opírá o skutečně ověřený přístup konkrétní aplikace.
- Nabídni jasnou další akci: pokračovat druhým storem, otevřít recenze nebo nastavení aplikace, případně navázat v existujícím onboardingu. Zavření jednoho store kroku nesmí ztratit zbytek průchodu.
- První import ukaž jen podle existujícího skutečného API. Rozliš čekání na přístup, probíhající synchronizaci, nulové recenze a chybu. Žádné simulované procento, falešná data nebo nepodložené sliby historie.
- AI preference, historie a Slack/Teams zůstanou dostupné v existujícím nastavení/onboardingu, ale nesmí zahltit připojení storu nebo se stát novou povinnou podmínkou. Při přesunu stávajícího nastavení zachovej jeho funkci a hodnoty.

## 5. Obsah návodů je součást implementace

Pracuj s aktuální oficiální dokumentací Apple a Google. Ověř názvy položek, podporované typy klíčů, role, API capabilities, reporting, dostupnost historie a případné prodlevy. Kde máš oprávněný testovací přístup, ověř i reálné konzole. Veřejný návod nepovažuj za důkaz ověření v přihlášeném účtu.

Do `docs/design/add-app-wizard/research.md` ulož odkazy, datum, ověřená fakta, rozdíly proti současnému copy a zbývající nejistoty. Pokud externí UI nelze ověřit, použij doložený obecnější návod a uveď limit v předání. Nepřenášej nejistotu na uživatele požadavkem na zbytečně vysoká oprávnění. Nevyráběj falešné screenshoty konzolí; případné schéma musí být zřetelně ilustrativní a věcně správné.

Texty piš profesionálně česky, jednotně ve vykání v tomto průchodu. Názvy položek externích konzolí uváděj tak, aby je člověk našel; anglický název může doplňovat české vysvětlení. Každý krok musí obsahovat:

1. Nadpis vyjadřující úkol.
2. Krátké vysvětlení účelu a potřebného výsledku.
3. Konkrétní návod nebo formulář se srozumitelnými popisky.
4. Jednoznačnou hlavní akci a dostupný návrat; odložení tam, kde je možné.
5. Relevantní nápovědu pro situaci „nemám přístup / nevidím danou položku / kontrola neprošla“.

Příklady tónu, nikoli neměnná specifikace: „Pozvěte účet do Google Play Console“, „Tento e-mail vložte do pole E-mailová adresa“, „Ověřit přístup“, „Přístup zatím není potvrzený“, „Dokončit později“. Technické odpovědi konektorů přelož do užitečné hlavní zprávy; bezpečný detail může být rozbalitelný. Nepouštěj do textu interní stack trace ani tajemství.

## 6. Stav, architektura a spolehlivost

- Zachovej React, TypeScript, TanStack Query, Ktor a existující tokeny. Vytvoř přiměřený sdílený rámec průvodce a znovupoužitelné platformní kroky; neduplikuj celý průchod pro přidání aplikace, onboarding a opravu v detailu.
- Odděl prezentační komponenty od orchestrace. Definuj typované stavy a přechody; počet booleans nesmí umožňovat současný úspěch a chybu nebo krok bez potřebného kontextu. Velký nový wizard framework není cílem.
- Backend je zdroj pravdy pro aplikace, vazby, validaci a synchronizaci. Zjisti, co lze obnovit z existujících dat. Chybějící persistenci postupu přidej jen v potřebném rozsahu. Stávající onboarding stav pro jednu aplikaci nesmí být omylem přepsán přidáním další aplikace.
- Zpět zachová rozpracovaná pole, výběry a hotové prostředky. Změna starší volby řízeně zneplatní jen závislé výsledky. Změna store odkazu po uložení nesmí potichu přesměrovat existující klíč k jiné aplikaci.
- Po refreshi nebo znovuotevření se naváže podle uložených prostředků. Neuložený privátní soubor je případně nutné vybrat znovu; výslovně to vysvětli. Po bezpečném uložení už nový upload nevyžaduj.
- Zrušení, Escape, křížek, backdrop a odchod ze stránky mají konzistentní význam. Zabraň náhodné ztrátě neuložené práce; uložené prostředky nemaž jako vedlejší účinek zavření. Vysvětli, co zůstalo uloženo. Nepřidávej potvrzení ke každému běžnému kroku.
- Požadavky odolají dvojkliku, pomalému serveru, retry i návratu do kroku. Při úspěchu serveru a ztracené odpovědi se prostředky znovu načtou; nevytvoří se duplicita. Ověř idempotenci provisioning endpointu, nespoléhej pouze na lokální `useRef`.
- Zabraň přepsání nového stavu starou odpovědí. Po dokončení či zavření nesmí pozdní callback přeskočit do jiného kroku. Správně invaliduj cache aplikací, klíčů, validace a relevantního health/onboarding stavu.
- Respektuj organizaci a role. Při změně organizace nebo odhlášení zahodíš citlivý lokální stav; credential jiné organizace nikdy nenabídneš.
- Klíče, JSON credentials a tokeny nepatří do URL, localStorage, sessionStorage, logů, telemetrie, screenshotů ani testovacích artefaktů. Použij existující bezpečné credential API. Zachovej CSRF, cookie session a serverovou kontrolu práv.
- Sdílený `Modal` měň opatrně: zvětšení či jiné zavírání tohoto průvodce nesmí rozbít potvrzovací dialogy a ostatní formuláře v produktu.

## 7. Pracovní postup a výstupy

1. **Audit:** projdi kód i skutečně vykreslené větve; zachyť baseline přidání aplikace, Google, Apple a mobilu. Sepiš nejzávažnější problémy, aktuální API a chybějící stavy do `docs/design/add-app-wizard/audit.md`.
2. **Návrh:** ulož mapu kroků, větvení, vedlejší efekty, návrat a pokračování do `flow.md`. Přidej tabulku krok → účel → obsah → hlavní akce → validace → chyba/náprava. Promysli celý tok před implementací. Vytvoř a prohlédni skutečný render desktopu i mobilu v cílovém designu; směr vyber samostatně.
3. **Implementace:** dotáhni společný průchod, obě platformy, existující klíče, opětovné připojení a návaznost onboardingu. Menší serverové změny nezbytné pro spolehlivost jsou součástí práce. Neprováděj plošný redesign auth, landing page nebo ostatních modulů.
4. **Review:** projdi průchod jako nový uživatel bez znalosti integrace a jako vracející se administrátor. Kriticky zhodnoť čitelnost a oprav konkrétní nedostatky. Nezaměňuj screenshot s důkazem funkčnosti.
5. **Ověření a předání:** dokonči relevantní kontroly, porovnej před/po a připrav dohledatelné změny k review. Conventional Commits česky podle pravidel projektu; cizí změny nezahrnuj. PR připrav podle aktuálního workflow a dostupného oprávnění. K obrázkům přikládaným do PR použij skill `pr-shot`.

Od začátku průběžně aktualizuj `docs/design/add-app-wizard/progress.md`: hotovo, rozhodnutí, výsledky kontrol, problémy a přesný další krok. Uživatel má dostávat stručné průběžné zprávy, ale jeho nepřítomnost neblokuje rutinní rozhodnutí. Po přerušení session navazuj podle tohoto souboru.

## 8. Podmínky dokončení

Funkční browser regrese musí prokázat alespoň následující scénáře. Spojuj související kontroly do smysluplných testů, nevytvářej desítky testů pouze pro vykreslení stejného tlačítka.

| Scénář | Co musí být prokazatelně správně |
| --- | --- |
| Nová Android aplikace | Rozpoznání odkazu → volba rozsahu → pozvání → validace → pravdivý souhrn |
| Google s hodnocením | Správný návod a reporting; úspěch, bez dat, chybějící přístup a odložení |
| Nová iOS aplikace | Návod / existující klíč → upload → identifikátory → výběr → validace |
| iOS více aplikací | Výsledky po položkách, částečný úspěch a bezpečný retry |
| Obě platformy | Postupně dostupné obě větve, odlišné stavy, dokončení jedné neukončí druhou |
| Existující aplikace/klíč | Reuse, ověření konkrétní aplikace, oprava; žádná duplicitní aplikace ani provisioning |
| Přerušení | Zpět, zavření, refresh a návrat navážou na serverový stav; žádné ztracené uložené klíče |
| Chybové stavy | Špatný odkaz, soubor, Key/Issuer ID, oprávnění, čekání, timeout a obnovení po API chybě |
| Souběh | Dvojklik a pomalá/zastaralá odpověď nevytvoří duplicitu ani nepřepíše aktuální krok |
| Konkurence | Stručná správná větev bez klíčů a bez falešné aktivace vlastní aplikace |
| Integrace do produktu | Přidání z portfolia, oprava z detailu i onboarding fungují a sdílejí logiku |
| Role a organizace | Nepovolené mutace jsou zamítnuty, změna organizace neodhalí cizí data |

Vizuálně a interakčně ověř desktop 1440 a 1024 px, tablet 768 px, mobil 390 a 360 px, obě témata; pro dlouhý krok také nízkou výšku viewportu. Na všech šířkách ověř hlavní rámec a oba store návody, vybrané chybové a výsledkové stavy alespoň na desktopu a mobilu. Není nutné vyrábět kartézský součin všech možných stavů.

Ověř klávesnici, focus trap, návrat fokusu k vyvolávací akci, přechod fokusu při změně kroku, viditelný fokus, sémantiku seznamu kroků (`aria-current="step"`), popisky polí a přístupné hlášení změn. Dále 200% zoom, kontrast, reduced motion, dlouhé hodnoty a nulový horizontální overflow. Použij existující axe/Playwright nástroje.

Spusť v `console` `npm run lint`, `npm run typecheck`, `npm run build` a relevantní Playwright sady včetně upraveného setup/onboarding pokrytí. Dokonči požadované kontroly repozitáře dle aktuálního CI/CONTRIBUTING; serverové změny vyžadují odpovídající testy a `./gradlew build`. Nevytvářej testy, které jen kopírují implementaci. Existující regrese aktualizuj podle nového chování, nesnižuj jejich význam, aby prošly.

Snímek funkčního UI i mock test jasně odliš od skutečného backendového a externího ověření. Pro serverové změny ověř skutečné lokální API/Postgres v izolaci. Kde je dostupný oprávněný testovací store účet, proveď bezpečné ověření přístupu. Tato práce není pokyn publikovat odpovědi zákazníkům nebo posílat zprávy skutečným týmům. Chybějící externí účty popiš jako konkrétní limit; netvrď, že integrace prošla živým E2E testem.

Ulož výsledné snímky do `docs/design/add-app-wizard/before/` a `after/`, dolož porovnání a do `validation.md` napiš co prošlo, co nešlo ověřit a proč. Nepřepisuj historické důkazy jiných změn.

Dokončeno znamená funkční, konzistentní, přístupný a vizuálně prohlédnutý průchod s přesnými návody, bezpečným návratem a ověřenými regresemi. Samotné širší modální okno, přidaný stepper, zelený build nebo happy path nestačí. V závěru předej stručný popis výsledku, změněné oblasti, commity, cestu k vizuálním důkazům, výsledky kontrol a konkrétní zbývající limity.
