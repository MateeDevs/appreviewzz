# Přihlášení a aktivace — průběh

## Výchozí stav 25. 10. 2026
- Větev `feat/auth-onboarding` z `611a12b`; jediný původní nesledovaný soubor `docs/design/auth-onboarding-prompt.md` zachován.
- Přečteno zadání, Signál direction/progress/plán, CONTRIBUTING a aktuální CI/deploy workflow. AGENTS.md nenalezen.
- Staging se nasazuje z epic/v2 nezávisle na CI. Tato práce připraví PR; integrace jen po zelených kontrolách, produkce mimo rozsah.

## Návrh stavů
Registrace → ověření e-mailu (nebo přijetí pozvánky) → výběr/založení prostoru → vlastní aplikace → jednotlivé store platformy → potvrzení preferencí → volitelné integrace/kolegové → skutečná dostupnost dat.
- Přímý odkaz a pozvánka mají přednost před výchozím směrováním.
- Vlastní aplikace a její vazby ke klíčům určují připravenost; kanál je volitelný pro konzoli.
- Backend drží malý stav průchodu odděleně pro uživatele a organizaci. Tajemství zůstávají v existujícím vault API.
- Existující uživatelé nedostanou nucený nový onboarding. Rozpracovaný průchod je dostupný přes Průvodce.
- Import bez telemetrie nesmí předstírat procenta ani úspěšně prázdný store. Doplnit skutečný stav běhu, pokud jej současný backend neposkytuje.

## Další krok
Zachytit původní obrazovky, implementovat auth a stav průchodu, sdílené nastavení a skutečný stav synchronizace; browser/backend regrese a vizuální review.

## Implementace a ověření
- Nové auth formuláře, zobrazení hesla, autocomplete, návrat přes společnou bezpečnou `next`, návaznost pozvánky přes registraci, resend s cooldownem/Retry-After a limitem API.
- Pět skutečných kroků průvodce s backend persistencí po uživateli/organizaci. Obnovení stránky a nový login navazují; přímé URL mají přednost. Členům se nenabízí správa.
- Sdílené přidání vlastní aplikace, Google Play provisioning, existující klíče, ASC upload a validace konkrétní aplikace; obě platformy samostatně.
- Zkušební sync běží přes skutečný ingest bez odesílání zpráv/odpovědí. Stav je v PostgreSQL, souběh chrání atomický lease, timeout a ochrana před přepsáním nového běhu starým.
- Zjištěna další podstatná chyba: výsledek ověření sdíleného klíče byl globální. Doplněno ověření vazby app–klíč; migrace zachovává stav existujících vazeb, nové začínají UNKNOWN. Ingest i revalidace zaznamenávají konkrétní appku. Regrese ověří rozdílná oprávnění dvou aplikací.
- Browser: původních 28 regresí prošlo. Nových 9 stavových scénářů prošlo, navíc 2 vizuální/axe matice a klávesnice + CSS zoom 200 % (12/12). Žádný horizontální overflow na 1440/1024/768/390/360, obě témata.
- Test dvojkliku nejprve selhal (2 POST). Opraven synchronním single-flight guardem v přidání appky; test následně prošel pro všechny platformy.
- Vizuálně prohlédnut desktop login, dark připojení a mobil přidání. Mobilní první krok zkrácen: historie a doručování nejsou před prvním připojením nutné.
- Backend 4/4 nových integračních testů prošly; přidána pátá regrese sdíleného klíče. První úplný build měl pouze formátovací chybu kvůli souběhu format/build; následující kontroly poběží po samostatném formátování.

Další krok: finální úplný Gradle build, nové browser rendery po zkrácení prvního kroku, izolovaný skutečný Ktor/Postgres smoke; docs, snímky a PR/CI.

## Finální ověření a vizuální review
- Celý Gradle build a installDist prošel. XML reporty: 750 testů / 0 chyb / 0 přeskočených. Včetně skutečného PostgreSQL backup/restore drillu přes PG17 klienta v Dockeru.
- Skutečný izolovaný Ktor/Postgres browser smoke prošel (11,2 s). Po loginu se správně vrací audit deep link; následný vstup přes `/` naváže na uložený krok Tým. Není to staging ani skutečné připojení storu.
- První lokální pokus narazil na kolizi optimalizační cache dvou Vite instancí. Přidána možnost VITE_CACHE_DIR a lokální instance izolována. Pozdější chyby smoke byly nejednoznačný selector Zavřít a očekávání průvodce místo správně zachovaného deep linku; testy zpřesněny.
- Finální vizuální review: login desktop/mobil/dark; vlastní aplikace na mobilu; rozdílný stav dvou platforem; zero/import/error/ready. Mobilní navigace kroků nyní posune aktuální krok do viditelné oblasti. Opraveno skloňování počtu recenzí.
- Rozšířená vizuální/axe sada 5/5 prošla: všechny rozměry v obou tématech, navíc 5 stavů připojení/importu na všech rozměrech, keyboard/focus/reduced motion/CSS 200% zoom. Občasný nález inherited černé barvy při axe byl odstraněn explicitní barvou lokálních nadpisů a summary; kontrola fontů před analýzou.
- Počet snímků v after zahrnuje všechny základní kroky na pěti šířkách, obě témata; doplňující stavy na desktopu a mobilu. Vše jsou rozpoznatelně syntetická data. `comparison.html` nabízí přímé před/po.
- Issue #6: https://github.com/MateeDevs/appreviewzz/issues/6. Vybrané snímky nahrány dle skillu pr-shot; URL půjdou do PR.
- Živé staging SHA přímo přečteno jako `611a12bbb424d2e345c409890c6847bfb636ab94`, health UP. V rámci této práce nebyl nasazen ani integrován nový image.

Další krok: dokončit poslední úplnou browser sadu, commit implementace a důkazů, PR do epic/v2, sledovat celé CI. Žádný production release.

## Předání implementace
- Produktový commit `000c945`: kompletní auth/průchod/API/migrace/regrese.
- Finální úplná browser sada: 50 passed / 3 opt-in skipped. Po posledním mobilním focus/scroll fixu dalších 17/17 aktivace, včetně všech vizuálních a stavových testů.
- Zesílen test souběhu lease skutečným Dispatchers.IO; všech pět onboarding backend regresí znovu prošlo.
- Finální lint/typecheck/build prošly. JS gzip 151,00 kB, CSS 10,52 kB; Vite pouze upozorňuje na počáteční minifikovaný chunk >500 kB.
- Mobilní aktivní krok nyní přímo vizuálně ověřen na screenshotu pátého kroku. Jeho nová URL v screenshots.md a PR odpovídá finálnímu renderu.
- `validation.md`, `validation-results.json` a `comparison.html` oddělují mocks, skutečný lokální backend a nezměněný staging. Původní nesledované zadání se necommituje.

Další krok: push + vytvořit PR, sledovat CI. Po jeho dokončení přidat odkazy a stav. Integrace ani deploy nejsou součástí automatického předání tohoto PR.
