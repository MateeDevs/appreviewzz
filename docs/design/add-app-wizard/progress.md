# Přidání aplikace a připojení storů · průběh

## 26. 10. 2026 · audit
- Výchozí větev `epic/v2`. Původní nesledované podklady zachovány; žádné AGENTS.md pro tento projekt ani nadřazené složky.
- Přečteno CONTRIBUTING, console README, schválený design a dokumentace aktuálního onboardingu. Prohlédnuty obě vizuální reference.
- Přímý pokyn uživatele autorizuje i nasazení; přebíjí původní omezení zadání na přípravu PR.
- Potvrzeny oddělené dialogy, chybějící společný návrat, nepravdivý návod k Google bucket IAM, automatické intervalové překrývání kontrol a nedostatečná ochrana uploadu.
- Provisioning kontroluje existenci před vytvořením, ale nezamyká souběžné požadavky. Nutná serverová oprava a skutečný PostgreSQL integrační test.
- Backend pro reporting PATCH vyžaduje název aplikace; starý průvodce ho neposílá.

Další krok: skutečné baseline rendery, dokončit výzkum rolí a oprávnění, zaznamenat návrh toku a implementovat společný rámec.

## Implementace společného průchodu
- Nový 1060px rámec s fázemi, mobilním full-screen režimem, záchranou neuložené práce a sériovým ověřováním.
- Portfolio, detail a onboarding sdílejí store kroky i identitu. Přidání z portfolia nepřepisuje osobní onboarding stav.
- Rozpoznání odkazů chrání před starými odpověďmi; kolize i částečná shoda nabízí existující aplikaci. Ruční identifikátory zůstávají dostupné.
- Apple upload omezuje velikost/formát, resetuje Key ID po výměně souboru a rozlišuje chybu čtení. Výběr více aplikací zachován; úspěšné položky se při retry neopakují.
- Oficiální návody opraveny podle research.md. První typecheck odhalil zbylý import a nechtěný zásah stejnojmenného lokálního stavu jiné karty; opraveno před dalšími kontrolami.

Další krok: serverová idempotence a oprava reporting copy, první cílové browser regrese a render review; poté celá sada a nasazení.

## Ověření a review
- `./gradlew build :server:app:installDist` prošel: 752 testů, 0 chyb, 0 přeskočených, včetně PostgreSQL backup/restore drillu.
- Integrační regrese prokázaly jeden IAM účet a jeden klíč při šesti souběžných požadavcích; opt-in deduplikace uploadu porovnává skutečný SecretPayload uvnitř vault API, nejen zkrácený fingerprint.
- Lokální skutečný backend má vlastní PostgreSQL :55434 a API :8094, Vite :5178; žádný worker, SMTP ani přístup ke skutečným storům. Existující auth/onboarding smoke prošel. Nový smoke prokazuje opakovaný vault upload, vazbu, obnovu identity a izolaci organizací.
- Nové browser regrese: oba story, všechny reporting výsledky, částečný Apple batch/retry, chybný soubor, neuložený draft, zastaralý lookup, duplicitní submit i ztracená úspěšná odpověď.
- Review opravilo: dvojitý dialog při repair entry, ztrátu draftu při potvrzení zavření, ořezanou desktop patičku, zbytečnou první validaci ve StrictMode a nativní Tab přechod do browser chrome. Nové cílené testy těchto oprav prošly.
- Vizuálně prohlédnut desktop aplikace, tmavý Google, mobil Apple/Google a nízký 1024px viewport. Šířka 1060 px podložena skutečným renderem. Matice 1440/1024/768/390/360, obě témata, axe a CSS 200% zoom procházejí.
- Lint, typecheck a produkční build prošly; Vite upozorňuje na existující počáteční bundle >500 kB (gzip 158 kB).

Další krok: poslední celá browser sada, závěrečný audit návratů, PR a zelené CI; následně autorizované nasazení na staging standardním workflow epic/v2.

## Připraveno k integraci
- Poslední celá browser sada: 73 prošlo / 5 opt-in přeskočeno. Po doplnění 25s timeoutu dalších 19/19 cílových scénářů včetně timeout/retry. Dodatečná regrese neplatného odkazu a ruční obnovy je v závěrečné kontrole.
- Explicitní timeout se týká uploadu klíče, výpisu store aplikací a app-specific validace; dlouhý onboarding import zůstává na svém původním kontraktu. Citlivý payload nečeká v mutation cache po opuštění formuláře.
- Galerie obsahuje 6 baseline a 46 finálních syntetických renderů; 4 vybrané snímky nahrány přes pr-shot.
- Pracovní větev `feat/add-app-wizard`; původní nesledované auth/landing prompty zachovány mimo změnu.

Další krok: commit, PR do epic/v2, sledovat CI a provést uživatelem autorizovanou integraci a staging deploy.
