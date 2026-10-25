# Ověření přihlášení a aktivace

Výchozí checkout `611a12bbb424d2e345c409890c6847bfb636ab94`, větev `feat/auth-onboarding`.
Syntetické snímky a stavové browser fixtures nejsou data zákazníků ani staging E2E.

PR: https://github.com/MateeDevs/appreviewzz/pull/7 · [aktuální CI](https://github.com/MateeDevs/appreviewzz/pull/7/checks).

## Výsledky

| Kontrola | Výsledek |
| --- | --- |
| Čisté `npm ci --no-audit --no-fund` | Prošlo. |
| Console lint, typecheck včetně testů, build | Prošlo po čistém npm ci. JS gzip přibližně 151 kB, CSS gzip 10,52 kB. Vite hlásí doporučení rozdělit počáteční 502 kB minifikovaný chunk; proti předchozímu redesignu je gzip JS vyšší přibližně o 4,5 %. |
| `./gradlew build :server:app:installDist`, JDK 21 | Prošlo; 750 testů, 0 chyb, 0 přeskočených. |
| Záloha a obnova PostgreSQL | Prošlo i s migracemi 26/27; skutečný klient PostgreSQL 17 v izolovaném Docker kontejneru přes lokální wrappery. |
| Onboarding backend | 5 integračních regresí nad PostgreSQL: nová session, dokončení bez kanálu, klíč konkrétní aplikace, role/tenant/CSRF/neplatná ID, souběh a expirace lease. |
| Browser regrese | 51 prošlo / 3 opt-in utility přeskočeny. Skutečný lokální smoke spuštěn samostatně a prošel; po poslední mobilní úpravě prošlo navazujících 17/17 regresí aktivace. |
| Skutečný lokální Ktor/Postgres | Prošlo: izolovaný účet, registrace, skutečný ověřovací odkaz, prostor, vlastní appka, refresh, nedostupný provisioning, preference, přeskočení integrací, nenakonfigurovaný import, odhlášení, nový login, návrat na deep link i rozpracovaný krok přes `/`. |
| Responzivita | 1440, 1024, 768, 390, 360 CSS px, obě témata; login/auth a všech pět kroků, navíc čekání/running/empty/failed/ready. Bez horizontálního overflow. |
| Přístupnost | axe WCAG 2 A/AA včetně kontrastu; klávesnice, heslo, modal Escape a návrat fokusu, fokus nadpisu po kroku, reduced motion. CSS zoom 200 % při 1440 px; nejde o měření browser chrome zoomu. |
| Staging | Beze změny, přímo přečtené živé SHA `611a12b…`, health UP. Žádný deploy této práce. |

## Co skutečně ověřují jednotlivé vrstvy

- Browser stavový mock zachovává prostředky a postup mimo dokument; refresh proto dělá nové HTTP requesty proti stejným testovacím datům. Pokrývá Android provisioning, iOS upload/přístup k jedné konkrétní appce a obě platformy společně; žádný skutečný store nebo kanál.
- Integrační API testy používají skutečný PostgreSQL, cookies, CSRF, vault a tenant dotazy, ale falešné konektory a mailer. Obsah testovacích klíčů je generovaný.
- Lokální smoke nepoužívá mock transportu. Běžel na samostatném API :8092, Vite :5176 s oddělenou cache a samostatném PostgreSQL :55433, bez workeru, SMTP, store účtů a týmových kanálů. Ověřovací odkaz načítal z výslovně povoleného lokálního log maileru, nikoli z produkčního logu.
- Odeslání zákaznické odpovědi, skutečné Slack/Teams zprávy a autorizace skutečného store účtu nebyly prováděny. Ověření těchto externích přístupů vyžaduje vyhrazené účty a klíče. OAuth odkaz a doručení jsou regresně pokryté izolovaným transportem.

Dodatečná regrese ověřila reset hesla z otevřené přihlášené konzole. Po úspěšném resetu se maže i klientský profil a koncepty; zastaralá cache tak nepřeskočí nové přihlášení. Test před opravou selhal a po opravě prošel v celé sadě.

## Stav a bezpečnost

Postup je po uživateli a organizaci v PostgreSQL. U aplikace zůstávají trvalé preference a vazby ke klíčům. Dokončení základu vyžaduje vlastní zapnutou aplikaci, alespoň jednu skutečně ověřenou platformu a potvrzené preference; kanál ani tým nejsou podmínkou. Druhá neověřená platforma zůstává rozpracovaná.

Při odchodu uprostřed ukládání se obnoví poslední potvrzený krok. Vytvořená aplikace se znovu použije; frontend single-flight a backend unikátní store identifikátory brání duplicitám. Žádná hesla, .p8, JSON klíče, invitation či OAuth tokeny nejdou do local/session storage.

První ruční sync je omezený na dvě minuty, serverový lease na pět minut. Refresh běh neopakuje. Po výpadku procesu je možné vypršelý běh spustit znovu. Je to skutečný běh ingestu, nikoli procentní animace historie. Výsledek popisuje dostupný rozsah konektoru; Google Play bez reportingového bucketu má omezenou historii. Plánovač a další běžné synchronizace se nemění.

## Migrace a návrat

V26 přidává osobní stav průchodu a výsledky prvního syncu. V27 přidává nullable stav validace na vazbu app–klíč; existující vazby převezmou dosavadní stav, nové mají UNKNOWN. Nové ověření, ingest i revalidace zapisují app-specific stav i kompatibilní metadata klíče. Připojení nového klíče nahradí starý klíč stejného typu/účelu pro danou aplikaci atomicky.

Změny schématu jsou aditivní; návrat aplikace na předchozí image nevyžaduje mazání tabulek/sloupců. Při budoucí integraci revertovat konkrétní commity standardním PR a deployem, bez force push. Předchozí živý staging je `611a12b…`. Produkce se neměnila.

## Review před integrací PR #7

- Dokončený průvodce při ztrátě přístupu ke storu nebo pozastavení aplikace posílal při navigaci stále `completed: true`. API správně odmítlo neplatné dokončení, ale tím znemožnilo návrat na připojení. Navigace nyní znovu otevře rozpracované nastavení; platné dokončení zachová.
- ASC dialog měl předvybranou aplikaci i tehdy, když ji klíč ve výpisu neviděl. Potvrzení pak ukázalo „Hotovo“ bez připojení. Nyní potvrzuje pouze skutečně dostupné vybrané aplikace a při chybějícím přístupu nabízí nové načtení po opravě oprávnění.
- Čtyři nové browser scénáře: zachování platného dokončení, návrat po ztrátě oprávnění, návrat po pozastavení aplikace a oprava ASC přístupu bez opětovného uploadu. Tři chybové scénáře před opravou selhaly, po opravě všechny čtyři prošly.
- Po opravách prošly lint, typecheck, build a celá browser sada: **55 passed / 3 opt-in skipped**. `./gradlew build` prošel; backendové reporty mají 750 testů bez chyby a přeskočení (nezměněné úlohy Gradle využily cache). Skutečné externí store účty a týmové zprávy toto review nezkoušelo.
