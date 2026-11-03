# Moderní přihlášení a kompletní onboarding pro appreviewzz

Implementuj moderní, profesionální vstup do appreviewzz: sjednoť přihlašování s novým designem produktu a vytvoř kompletní onboarding od registrace po první práci s vlastní aplikací. Výsledkem má být funkční a vizuálně dotažený produkt, který působí na úrovni AAA SaaS. Pouhá úprava barev ani další seznam odkazů do nastavení zadání nesplňuje.

Uživatel bude mimo počítač. Běžná produktová, designová a implementační rozhodnutí dělej samostatně. Pokračuj přes návrh, implementaci a ověření; nezastavuj se u analýzy nebo prototypu. Tento soubor je zadání pro novou session, nikoli záznam hotové implementace.

## Výchozí stav a podklady

Nejdřív ověř aktuální checkout, pracovní změny a pravidla repozitáře. Při přípravě 25. 10. 2026 byla aktivní větev `epic/v2` a pracovní strom čistý. Cizí rozpracovanou práci zachovej.

Navazuj na existující vizuální směr Signál a hotové hlavní plochy. Přečti `docs/design/aaa/direction.md`, `docs/design/aaa/progress.md` a relevantní části `docs/design/aaa-redesign-plan.md`. Původní redesign neopakuj v celé aplikaci; tato práce rozvíjí vstup do produktu a aktivaci nového uživatele.

Výchozí místa v kódu:

- `console/src/pages/Auth.tsx`: login, registrace, ověření e-mailu, reset hesla, pozvánka a MFA.
- `console/src/components/ui.tsx`: sdílený `AuthShell`, již s dvousloupcovou kompozicí, značkou a motivem signálu; samotné auth formuláře stále používají jednoduché karty.
- `console/src/pages/Organizations.tsx` a `console/src/App.tsx`: výběr a založení organizace, první přesměrování a návrat přes `next`.
- `console/src/pages/Onboarding.tsx`: současný šestibodový seznam odvozený z aplikací, klíčů a health dat. Nejde o souvislý průchod jednotlivými kroky.
- `console/src/components/ConnectStoreWizard.tsx` a `connectStoreCopy.ts`: existující funkční větve Google Play a App Store Connect.
- `console/src/pages/Apps.tsx`, `OrgLayout.tsx`, `console/src/api/` a `console/src/styles/`: napojení nastavení, rolí, API a designových tokenů.
- `console/e2e/auth.spec.ts`, `setup.spec.ts` a `fixtures.ts`: výchozí browser pokrytí.

Současný onboarding má kandidáty na věcné chyby: dokončení aplikace počítá i konkurenci, některé stavy vyvozuje z klíčů celé organizace, nastavení považuje za hotové už existencí aplikace a copy pro Slack popisuje ruční bot token. Ověř skutečné kontrakty a oprav tyto nesoulady v novém průchodu.

## Přihlášení a související obrazovky

Navrhni výraznější a modernější kompozici v rámci Signálu: kvalitní typografie, inkoustová a teplá neutrální paleta, petrolejový akcent, precizní mezery a jasná hierarchie. Značka, formulář i ukázka hodnoty produktu mají tvořit jeden celek. Na desktopu můžeš použít promyšlený produktový motiv nebo ukázku práce s recenzí; případná ukázková data musí být rozpoznatelná jako ukázka. Nepřidávej vymyšlené reference zákazníků ani tvrzení o výsledcích.

Na mobilu dej přednost okamžitě použitelnému formuláři a stručnému vysvětlení hodnoty. Dotažení musí být vidět také v dark mode, v chybách, načítání a úspěšném dokončení. Sladit je třeba login, registraci, ověření e-mailu a jeho opětovné odeslání, zapomenuté a obnovené heslo, pozvánku, MFA i výběr organizace.

Formuláře musí podporovat správce hesel, autocomplete, zobrazení hesla přístupným tlačítkem, odeslání klávesnicí, srozumitelnou validaci a ochranu před opakovaným odesláním. Zpracuj neplatný či expirovaný odkaz a obnovitelnou chybu API. Zachovej bezpečné přesměrování `next`, včetně pomalého načtení profilu a MFA. Nepřidávej nefunkční přihlášení přes Google nebo Apple.

## Kompletní průchod nového uživatele

Nejprve si zmapuj stavy a přechody. Navrhni přiměřený počet kroků s jedním hlavním rozhodnutím a jasnou hlavní akcí. Uživatel vždy rozumí tomu, kde je, proč je krok potřeba, co bude následovat a co lze dokončit později.

1. **Registrace a ověření e-mailu.** Po registraci vysvětli další krok, ukaž cílový e-mail a umožni opětovné odeslání podle limitů API. Odkaz otevřený v jiné záložce nebo po opětovném přihlášení musí smysluplně navázat. Respektuj současný požadavek ověřeného e-mailu pro založení organizace.
2. **Pracovní prostor.** Nového zakladatele proveď založením organizace. Pozvaný člen přijme existující pozvánku a přejde do správného prostoru; nezakládá zbytečně další organizaci. Existující uživatel s více organizacemi dostane odpovídající výběr. Zohledni role a přístup k nastavení.
3. **První vlastní aplikace.** Umožni snadno vložit store odkaz, ověřit rozpoznané údaje a vybrat dostupné platformy. Podporuj Android, iOS i jejich kombinaci. Konkurenční aplikace nesmí plnit aktivaci vlastní aplikace. Duplicitní odeslání nesmí vytvářet další kopie.
4. **Připojení storu.** Začleň současné průvodce do souvislé zkušenosti. Zachovej provisionovaný Google Play účet, připojení existujících klíčů a App Store Connect v podporovaném rozsahu. Vysvětli potřebná oprávnění u konkrétního kroku. Rozliš neověřený klíč, čekání na propagaci oprávnění, chybné údaje a úspěšné ověření. U dvou platforem zobraz stav každé zvlášť. Pokročilé ruční zadání schovej tak, aby zůstalo dostupné.
5. **Základní preference a volitelné integrace.** Nabídni jen nastavení potřebné pro rozumný začátek, například jazyk a tón AI odpovědí. Slack či Teams a pozvání kolegů musí jít přeskočit a dokončit později. Postup napojení musí odpovídat aktuální implementaci. Samotná existence instalace nebo klíče nepotvrzuje funkční doručování do zvoleného kanálu.
6. **První synchronizace a první hodnota.** Ukaž skutečný stav připojení a dostupná data. Rozliš probíhající import, aplikaci bez recenzí a chybu. Nesmíš zobrazovat vymyšlený postup importu ani slibovat hotové recenze před jejich načtením. Po dokončení nabídni konkrétní cestu do recenzí nebo přehledu; když data ještě nejsou, vysvětli, co se bude dít dál, a umožni pokračovat.

Uživatel se může vrátit zpět, obnovit stránku, odhlásit se a pokračovat později. Hotové kroky a vytvořené prostředky se znovu nevytvářejí. Stav trvalého nastavení odvozuj z backendu; pro chybějící stav průchodu přidej malou, odůvodněnou persistenci. Neukládej hesla, privátní klíče ani tokeny do browser storage. Uložení citlivých údajů musí používat současné bezpečné API.

Odděl dokončený základ pro práci v konzoli od volitelných rozšíření. Přeskočený Slack nesmí blokovat aktivaci a čekající oprávnění se nesmí tvářit jako dokončené připojení. Rozpracované nastavení má mít dostupný návrat z aplikace. Stávající aktivní uživatele nenuť znovu procházet celým onboardingem a nepřepisuj jejich nastavení. Respektuj přímý odkaz i oprávnění pozvaného člena.

## Implementace a průběžná kontrola

Pracuj v současném Reactu, TypeScriptu, TanStack Query a Ktoru. Používej existující tokeny, komponenty a API; sdílej logiku s nastavením aplikace. Rozsah zahrnuje nezbytné úpravy routování a menší backendové změny pro spolehlivý stav a pokračování. Billing, nový auth provider a plošná přestavba zbytku produktu do tohoto zadání nepatří.

Návrh ověř na skutečně vykreslených obrazovkách a po implementaci znovu vizuálně zkontroluj. Založ `docs/design/auth-onboarding/progress.md` a zapisuj rozhodnutí, stav, kontroly a přesný další krok pro pokračování po přerušení session. Průběžně stručně informuj uživatele, ale nečekej na schválení běžných návrhových voleb.

## Ověření a podmínky dokončení

- Nový zakladatel projde registrací, ověřením, organizací, vlastní aplikací a připojením alespoň jedné platformy až ke srozumitelnému výsledku. Ověř obě platformní větve a jejich kombinaci.
- Pozvaný nový i existující uživatel skončí ve správné organizaci. Stávajícímu uživateli funguje běžný login, MFA, výběr organizace a návrat na původní URL.
- Smysluplné browser regrese pokryjí refresh a pokračování uprostřed nastavení, přeskočení volitelných kroků, chybu a opakování, čekající oprávnění, nulové recenze a ochranu před duplicitami. Relevantní backendové změny ověř backendovými testy. Zachovej dosavadní regresní pokrytí návratu po loginu.
- Spusť v `console` lint, typecheck, build a relevantní Playwright testy. Dokonči kontroly požadované repozitářem; při změnách serveru také `./gradlew build`. Odliš mock průchod od lokálního backendu a skutečného stagingu.
- Prohlédni login i hlavní stavy onboardingu na 1440, 1024, 768, 390 a 360 px, v obou tématech. Ověř 200% zoom, klávesnici, fokus, čtečkové popisky, kontrast a reduced motion. Stránka nesmí horizontálně přetékat. Doplň automatickou kontrolu přístupnosti.
- Připrav snímky před a po pro login a zásadní kroky onboardingu, včetně mobilu a dark mode. Při přikládání obrázků k PR použij skill `pr-shot`.

Testuj na izolovaných testovacích datech. Ověření UI samo o sobě není pokyn k publikování odpovědí na zákaznické recenze ani k posílání zpráv do skutečných Slack nebo Teams kanálů. Zachovej tenant izolaci, role, cookie session, CSRF a MFA.

Předej implementaci v dohledatelných commitech a připrav PR podle pravidel repozitáře. Řiď se aktuálním staging workflow a oprávněními platnými v implementační session; před integrací musí projít příslušné kontroly. Produkční release je samostatný krok. Pokud proběhne staging deploy, ověř přímo nasazené SHA, health, čerstvé přihlášení a průchod nového uživatele a zaznamenej možnost návratu.

V závěru předej výsledek, commit a případný PR či staging odkaz, stručný popis flow, vizuální srovnání, výsledky kontrol a konkrétní zbývající omezení. Za dokončení nepovažuj samotný návrh, statické obrazovky, úspěšný build nebo flow fungující pouze bez refreshnutí stránky.
