# Výsledný tok a stav

Jeden široký nativní dialog (1060 px), levý přehled fází a kontext uložené aplikace. Hlavní scrollovací plocha, vždy dostupné záhlaví a akce; mobil bez bočního panelu s kompaktním popisem aktuální fáze. Portfolio i oprava používají stejnou orchestraci; onboarding sdílí formulář identity a store kroky.

Identita → přehled platforem → Google / Apple v libovolném pořadí → souhrn všech platforem. Oprava vstupuje rovnou do vybrané platformy. ASC bez konkrétní aplikace zachovává hromadný výběr. Konkurence → stručný souhrn veřejných dat.

| Krok | Účel a obsah | Hlavní akce | Validace | Chyba / náprava |
| --- | --- | --- | --- | --- |
| Aplikace | Vlastní / konkurence, odkazy, rozpoznané listingy, upravitelný název, ruční ID | Přidat aplikaci | Aspoň jeden platný store, oba vyplněné musí být rozpoznány; odlišné názvy potvrdit | Neplatný odkaz / nedostupný lookup / kolize → opravit, ruční ID, otevřít existující |
| Přehled | Samostatné stavy a předpoklady storů | Připojit vybranou platformu | Pouze vlastní aplikace | Nedostatečná role → správce prostoru |
| Google rozsah | Recenze / recenze a oficiální hodnocení, reuse / spravovaný / vlastní JSON | Vybrat rozsah či použít uložený účet | Klíče pouze aktuální organizace | Provisioning retry nebo vlastní klíč |
| Google pozvání | E-mail, kopírování, očíslované App/Account permissions | Ověřit přístup | Uložení vazby před app-specific validací | Clipboard selže → označení a ruční kopie; oprávnění → konkrétní návod |
| Google kontrola | Pravdivý výsledek a sériové polling | Pokračovat / souhrn | VALID pouze konkrétní vazby | AUTH, NOT_FOUND, INVALID_REQUEST, RATE_LIMITED, TRANSIENT odlišeny, retry |
| Reporting | URI z Download reports, globální čtecí oprávnění | Ověřit a uložit | Probe konkrétní aplikace | NO_EXPORT / DENIED / MISSING / UNAVAILABLE, uložit i tak jen s důsledkem; odložení |
| Apple návod | Team Key, role, jednorázové stažení | Klíč už mám / nahrát | Vytvoření klíče není povinný checkpoint | Nevidím Team Keys → Account Holder/Admin |
| Apple upload | p8 drop/upload, Key ID, Issuer ID | Nahrát klíč | Omezení souboru, PEM, 10 alfanumerických znaků, UUID | Čitelná chyba; výměna zneplatní odvozený Key ID |
| Apple výběr | Dostupné aplikace, multi-select, app-specific ověření | Sledovat vybrané | Jen skutečně dostupné aplikace; oprava fixuje cíl | Prázdný seznam / jiný tým / API; retry jen neúspěšných položek |
| Souhrn | Uložená identita, každý store, reporting odděleně | Druhý store / recenze / nastavení / zpět do onboardingu | Stav ze serverových aplikací a vazeb | Neověřené platformy se neoznačí jako hotové |

## Vedlejší efekty a návrat
- Zpět uchovává lokální pole a již uložené prostředky. Uložená identita je neměnná; nenabízí se její tiché přepsání.
- Vytvoření aplikace pouze explicitním submit; synchronní single-flight; po konfliktu/ztracené odpovědi reload apps a bezpečné pokračování bez automatického sloučení.
- Provisioning explicitně po volbě cesty, server serializuje souběh pro organizaci a čte persistentní credential. Polling validace vždy až po skončení předchozí žádosti; zastaví se při návratu/zavření.
- Úspěšný upload je okamžitě dostupný v seznamu klíčů. Refresh jej znovu nevyžaduje, použije se uložený klíč. Soubor před uložením žije jen v paměti a po refreshi jej nutno vybrat znovu.
- Resume z existujících aplikací, jejich vazeb a credentials. Portfolio uchová identifikátor rozpracované uložené aplikace v URL, žádné tajemství. Osobní onboarding stav se nemění přidáním z portfolia.
- Zavření křížkem/Escape se sjednotí přes potvrzení jen při neuložené práci. Backdrop nezavírá. Uložená appka/klíč zůstávají. Při odchodu/refreshi s neuloženou prací ochrana beforeunload.
- Při změně organizace se subtree znovu vytvoří; při odhlášení zmizí. Cache je po organizaci, citlivá pole pouze v paměti.
