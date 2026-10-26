# Ověření průvodce · 26. 10. 2026

Výchozí revize `d5a12c8a08276bf29e675554cf771c4770dc880d`, větev `epic/v2`. Implementace ve `feat/add-app-wizard`, issue [#10](https://github.com/MateeDevs/appreviewzz/issues/10).

## Kontroly

| Vrstva | Výsledek a rozsah |
| --- | --- |
| Console lint, typecheck, build | Prošlo. Typecheck zahrnuje E2E zdroje. JS gzip přibližně 158 kB, CSS gzip 12 kB. Vite upozorňuje na existující počáteční minifikovaný chunk >500 kB. |
| `./gradlew build :server:app:installDist` | Prošlo; 752 testů, 0 failures/errors/skipped. Formát ktlint prošel. PostgreSQL backup/restore drill používá PG17 klienty v izolovaném Dockeru. |
| Backend souběh | Šest souběžných provisioning HTTP požadavků nad skutečným PostgreSQL vytvoří jeden IAM účet a klíč; další retry vrací stejný credential. Čtyři souběžné uploady s `deduplicate: true` vracejí jednu uloženou položku. IAM transport je mock. |
| Browser funkční regrese | Celá sada po review: 73 passed / 5 opt-in skipped. Po doplnění explicitního 25s timeoutu prošlo všech 19 cílových regresí; dodatečná regrese neplatného odkazu a ruční obnovy prošla samostatně. Původní historická capture utilita byla po pořízení baseline odstraněna; aktuální CI má 75 aktivních testů a 4 opt-in utility. |
| Vizuální matice | Skutečný Chromium render na 1440/1024/768/390/360, světlé i tmavé téma. Identita a oba store návody na všech rozměrech. Nízká výška 620 px pro 1024px viewport; další klávesnicový test mobilu 390 × 500. |
| Doplňující rendery | Obě platformy dokončené, reporting DENIED, chybný upload a částečný Apple batch, desktop a mobil v obou tématech. Všechny snímky mají syntetické identifikátory a žádný obsah klíče. |
| Přístupnost | axe WCAG2 A/AA v matici, bez horizontálního overflow. Tab/Shift+Tab, návrat fokusu po zavření, fokus nadpisu po kroku, aria-current u fází i store kroků, živé hlášení, reduced motion a CSS zoom 200 %. Nejde o měření skutečné mobilní softwarové klávesnice ani browser chrome zoomu. |
| Skutečný lokální Ktor/Postgres | Existující auth/onboarding smoke prošel. Nový wizard smoke prošel: registrace, ověření, izolovaná organizace, přidání přes ruční ID, dvojklik, refresh, tři souběžné ASC uploady, metadata bez payloadu, vazba čekající na ověření, zachování osobního onboardingu, cizí organizace bez přístupu. |

## Funkční pokrytí

`console/e2e/add-app-wizard.spec.ts`, `setup.spec.ts` a `onboarding.spec.ts` ověřují:

- Android → rozsah → pozvání → ověření → souhrn; druhá platforma pokračuje v témže dialogu.
- Všech pět reporting stavů, skutečný PATCH kontrakt včetně povinného názvu, bezpečné „Uložit i tak“, chybějící export bez falešného úspěchu.
- Apple návod, upload, chybné formáty/velikosti/Key ID/Issuer ID, výměnu souboru a zachování draftu při návratu. Uložený klíč lze po refreshi použít bez nového souboru.
- Multi-select, výsledky po aplikacích, částečný úspěch a retry pouze neúspěšných, bez nové appky či nového klíče.
- Globálně VALID klíč nestačí: test vyžaduje app-specific ověření a ponechá konkrétní aplikaci nepotvrzenou při AUTH.
- Zastaralé lookup odpovědi, ručně přepsaný název, rozdílné názvy listingů a explicitní potvrzení.
- Částečnou shodu store identity bez automatického slučování, existující aplikaci a ztracenou úspěšnou odpověď vytvoření bez dalšího POST.
- Konkurenci bez klíče a bez falešné aktivace, roli MEMBER, opravu z portfolia a návaznost existujícího onboardingu.
- Explicitní timeout requestu po 25 sekundách, opakování a ignorování pozdní odpovědi; citlivé upload proměnné se po opuštění hooku zahodí bez prodlevy mutation cache.
- Clipboard API failure s označením hodnoty; žádné falešné „Zkopírováno“.
- Sériové ověřování, zrušení polling při návratu, dvojklik a StrictMode bez duplicitního provisioningu.
- Backdrop, Escape, potvrzení ztráty práce a návrat ke stejným polím; interní odkaz se souborem v paměti vyžaduje stejné rozhodnutí jako zavření.

## Co je skutečné a co simulované

Browser transport je explicitně syntetický a uchovává stav mimo dokument, takže refresh dělá nové HTTP požadavky proti stejnému testovacímu serverovému stavu. Není to staging ani důkaz přístupu k Apple/Google účtu.

Backend integrační testy používají skutečný PostgreSQL, session, CSRF, vault, šifrování a tenant dotazy; store konektory a IAM mají izolované transporty. Nový lokální browser smoke používá skutečné API :8094 a vlastní PostgreSQL :55434 s odděleným klíčovým souborem, Vite :5178 s vlastní cache. ASC privátní klíč je jednorázově vygenerovaný pouze pro test. Nebyl poslán do skutečného storu. Žádný worker, SMTP, zákaznická odpověď ani týmová zpráva se nespouští.

## Limity a provoz

- Není k dispozici doložený oprávněný testovací Apple/Google účet. Přihlášené externí konzole a skutečná propagace oprávnění nejsou ověřeny. Návody jsou doloženy oficiálními zdroji v `research.md` a nepoužívají neověřený Admin fallback.
- Reporting probe dokazuje viditelnost názvu exportu pro konkrétní aplikaci, nikoli úspěšně načtený obsah CSV. UI přesně používá „export nalezen“; samotná uložená adresa se neoznačuje jako potvrzené hodnocení.
- Po refreshi se obnovují uložené aplikace, klíče a jejich validace. Neuložený soubor a formulář žijí pouze v paměti. Záměrně neukládáme tajemství ani koncepty klíčů do local/session storage nebo URL.
- Serverové zámky odpovídají existujícímu nasazení s jednou API instancí (ADR0016); při zavedení více API replik bude potřeba distribuované zamykání. Retry po restartu se opírá o uložené prostředky, ne o klientský useRef.
- Žádná nová DB migrace, žádné rušení či rotace existujících klíčů. Opt-in deduplikace nemění původní pokročilý upload bez tohoto parametru.

## Důkazy

- `before/`: šest historických renderů této změny.
- `after/`: 30 základních renderů a 16 stavových renderů.
- [Porovnání před/po](comparison.html), [sdílené snímky](screenshots.md), mapa toku `flow.md`, audit `audit.md` a zdroje `research.md`.

Nasazení a konečné CI budou doplněny po ověření běžícího SHA.
