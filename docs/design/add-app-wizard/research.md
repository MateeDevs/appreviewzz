# Ověření návodů · 26. 10. 2026

Veřejná oficiální dokumentace, nikoli přihlášené konzole. Žádný oprávněný vyhrazený store účet není v této session doložen; externí oprávnění se proto netvrdí jako živě ověřená.

| Zdroj | Závěr pro implementaci |
| --- | --- |
| [Apple: App Store Connect API](https://developer.apple.com/help/app-store-connect/get-started/app-store-connect-api/) | Přístup k API žádá Account Holder. Team Key vytváří Account Holder / Admin v Users and Access → Integrations → App Store Connect API → Team Keys. Role výsledného klíče je samostatná volba. |
| [Apple: Creating API keys](https://developer.apple.com/documentation/appstoreconnectapi/creating-api-keys-for-app-store-connect-api) | Team Keys mají přístup napříč aplikacemi týmu; soubor lze stáhnout pouze jednou. Individual Keys dědí oprávnění uživatele. Nejsou univerzálně neschopné výpisu aplikací; náš sdílený výběr zůstane cílen na týmový klíč s Issuer ID. Individuální klíče zůstávají v existujícím pokročilém nastavení. |
| [Apple: ratings and reviews](https://developer.apple.com/app-store/ratings-and-reviews/) | Customer Support umožňuje práci s recenzemi. Návod žádá tuto roli, žádný automatický fallback Admin. Pokud volba chybí, uživatel zkontroluje Team Keys a obrátí se na držitele účtu. |
| [Google: export reports](https://support.google.com/googleplay/android-developer/answer/6135870?hl=en) | Copy Cloud Storage URI je v Download reports. Exporty mají stejná přístupová omezení jako Play Console; bulk reports vyžadují globální View app information. Staré tvrzení o nutné dodatečné IAM roli Storage Object Viewer bylo nesprávné. Zachycená data mohou být zveřejněna s odstupem 3–7 dnů; neslibovat denní dostupnost. |
| [Google: users and permissions](https://support.google.com/googleplay/android-developer/answer/9844686?hl=en) | Pozvání spravuje account Admin. App permissions omezují přístup na aplikaci, Account permissions platí globálně. Pro reporting potřebujeme globální čtecí přístup, Reply to reviews lze omezit na cílovou aplikaci. |
| [Google: Reply to Reviews API](https://developers.google.com/android-publisher/reply-to-reviews) | Reply to reviews je požadováno i pro API přístup k recenzím; nejde o čistě čtecí připojení. API vrací komentované recenze vytvořené/upravené za poslední týden, starší historii poskytuje CSV export. |

## Ověřený server
- CredentialService používá vault a SecretPayload; klient dostává metadata a fingerprint, nikoli obsah klíče.
- Validace je na vazbě aplikace–klíč. Globální VALID credentialu není důkaz přístupu k cílové aplikaci.
- Reporting probe ověřuje existenci objektů s prefixem konkrétní aplikace, ne obsah CSV. Výsledek OK proto popisujeme jako „export nalezen“, nikoli úspěšně importovaná data.
- Reporting adresa se ukládá samostatným PATCH, který vyžaduje i název aplikace.
- Provisioner adoptuje účet označený org ID; při ztrátě odpovědi po uložení vrací existující credential. Souběh před uložení vyžaduje serverový zámek.

## Limity
Přesné popisky se mohou lišit jazykem a účtem externí konzole. Soukromé konzole, dostupnost Customer Support v konkrétním dropdownu a skutečná propagace nebyly ověřeny přihlášením. Instrukce zůstávají u doložených položek a nevyžadují vyšší roli klíče jako náhradní řešení.
