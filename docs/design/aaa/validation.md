# Ověření redesignu Signál

Výsledky před integrací do stagingu, 24. 10. 2026. [Srovnání před/po](comparison.html),
[rozhodnutí](direction.md), [inventář](audit.md), [návrat](rollback.md).

## Automatické kontroly

| Kontrola | Výsledek |
| --- | --- |
| `npm ci` / npm lockfile | Instalace v console úspěšná; Playwright/axe/@types/node pouze dev dependencies. |
| `npm run lint` | Prošlo. |
| `npm run typecheck` | Prošlo pro aplikaci i browser testy. |
| `npm run build` | Prošlo; společný Ktor/Docker image zachován. |
| `./gradlew build` | Prošlo, 745 testů / 0 chyb. Obnovovací drill byl nejprve přeskočen bez pg_dump v PATH; po explicitním PG_DUMP_PATH/PG_RESTORE_PATH na lokální PostgreSQL 18.6 prošel samostatně i tento test. JDK 21, OrbStack. |
| `npm run test:browser` | 25 testů prošlo; 3 opt-in utility přeskočeny (capture, skutečný lokální backend, performance). Browser/a11y regrese přidány i do CI. |
| `LOCAL_API_SMOKE=1 … local-smoke` | Samostatně prošlo na skutečném lokálním Ktor/Postgres. |
| `MEASURE_PERF=1 … performance` | Samostatně prošlo, 3 studené běhy každé trasy a varianty. |
| `CAPTURE_MATRIX=1 … visual` | Rendery při 1440/1280/1024/768/390/360 px, oba režimy, bez horizontálního overflow celé stránky. |

Nové backendové regrese: filtr období v zóně aplikace včetně půlnoci a horní exkluzivní hranice,
trh/textOnly, shodný počet, neplatné/obrácené datum; přesný health nad 205 recenzemi;
report HTML bez JS, XSS escaping, locale, tabulková alternativa grafu a A4 CSS.
Stávající ReportRoutesTest ověřuje skutečné veřejné routy, snapshoty a odvolání sdílení.

## Tři pracovní scénáře a hranice důkazu

1. **Naléhavá recenze → koncept → odpověď:** Playwright mock transport ověřuje předání urgency,
   URL/refresh/Back, přepnutí detailu bez ztráty konceptu, AI a návrat původního textu po překladu,
   PENDING/PUBLISHED/FAILED a blokaci druhého odeslání. Klávesy a mobilní návrat jsou samostatné
   regrese. Žádná odpověď nebyla publikována do skutečného storu.
2. **Téma/verze → zdroje → Back:** browser regrese předává aplikaci, state=all, období, trh,
   platformu a textOnly; verze předává přesnou platformu a verzi. Back vrací období i rozbalení
   verze. Rozsah API je ověřen skutečnou databází v backend testu. Nepoužívá se falešný filtr.
3. **Připojení aplikace → stav nastavení:** skutečný lokální backend ověřil registraci, e-mail,
   novou organizaci a appku z odkazu, otevření store wizardu, čitelný chybějící klíč/kanál a nové
   přihlášení. Mock wizard test ověřuje provisionovaný GP účet a čekání na oprávnění, ASC test
   chybné soubory a popisky. Store oprávnění a Slack/Teams zprávy se ve skutečnosti neposílaly.

## Vizuální a přístupnostní review

- Prohlédnuty skutečné rendery hlavních ploch na požadovaných šířkách, v obou tématech,
  auth, aplikace/detail, onboarding a report. Opraveny příliš vysoké mobilní filtry, dlouhý
  formulář nastavení, kontrast hotových kroků a klávesová dostupnost tabulek.
- Axe WCAG 2 A/AA + 2.1 AA na hlavních plochách při 1440 a 390 px, oba režimy: bez nálezů.
  Navíc auth/reset/pozvánka, vedlejší plochy, security dialog a ASC upload. Psaní neaktivuje
  zkratky; dialog udrží Tab a zavře se Escape; r zaostří i teprve načítaný editor.
- Text/akce ≥4,5:1, důležité hranice formulářů ≥3:1. Výpočty v contrast.json. Stavy mají
  textové popisky, grafy osy/legendu, odlišné čáry a tabulku. Reduced motion respektuje CSS.
- Ekvivalent browser 200%: viewport 720 CSS px při DSF 2 pro fyzických 1440 px; rendery v after/zoom*.
  Jde o reprodukovatelnou emulaci layoutu a textu, nikoli manuální ovládání browser chrome.
- Ověřeny prázdné a velké portfolio, chyby, dlouhé texty, chybějící AI, konkurence, smazaná
  a upravená recenze, selhání části hromadné akce, OWNER/MEMBER a tenant switch.
- Report z reálného serverového rendereru nad izolovaným snapshotem: light/dark, tiskové
  rendery a A4 PDF v after/. Nejde o zákaznický report ani o přihlášený staging důkaz.

## Výkon

Stejný Chromium, 1440 × 1000, produkční Vite preview, studené kontexty, CPU ×4,
1,6 Mbps download / 750 Kbps upload / 150 ms. API jsou izolované fixtures; tato měření
neprokazují latenci staging DB ani reálný uživatelský INP. Raw výsledky: performance.json.

| Trasa | LCP před (medián) | LCP po (medián) | CLS po (medián) | API před → po |
| --- | ---: | ---: | ---: | ---: |
| Přehled / 1 aplikace | 1,428 s | 1,772 s | 0,049 | 6 → 6 |
| Přehled / 30 aplikací | 1,676 s | 1,808 s | 0,031 | 93 → 10 |
| Inbox | 1,468 s | 1,772 s | 0 | 8 → 8 |
| Rozbory | 1,492 s | 1,876 s | 0,001 | 8 → 8 |

Údaje o API jsou první viewport po ustálení, včetně auth/CSRF; scroll do dalších řádků
načte jejich trendy. Backend health nadále pracuje po aplikacích; dávkový portfolio endpoint
by byl další optimalizace, ne součást tohoto redesignu.

Počáteční gzip JS baseline 146,25 kB, po změně přibližně 144,1 kB; security a platform se
načítají až při otevření. CSS gzip přibližně 9,1 kB proti 6,73 kB. Geist variable 69 760 B,
žádné CDN. Výraznější UI tedy nezvětšilo počáteční JavaScript. LCP přidává zejména vlastní font,
ale v tomto měření zůstává pod cílem 2,5 s; CLS pod 0,1.

Čas kliknutí → dostupný editor je laboratorní end-to-editor čekání v performance.json,
nikoli INP. Skutečný uživatelský INP bez RUM dat neprohlašujeme za změřený.

## Staging

Předchozí health/live SHA i dostupnost image jsou v rollback.md. Aktuální doručení a CI/deploy
odkazy doplní progress.md po integraci. Přihlášený staging účet nebyl v době těchto kontrol
k dispozici; byl konkrétně vyžádán. Lokální fixtures ani lokální backend se za staging E2E nevydávají.
