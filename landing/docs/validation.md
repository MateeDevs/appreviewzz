# Ověření implementace

Ověřeno 25. 10. 2026 na macOS, Node 25.2.1, npm 11.6.2. Testován přímo statický výstup `dist/` přes Astro production preview na `http://127.0.0.1:4327/`. Nejde o veřejné nasazení ani o test Kotlin serveru.

## Instalace, typy a build

- Čisté `npm ci --no-fund --no-audit`: úspěšné, uložený lockfile. Npm pouze hlásí deprecaci tranzitivního OpenTelemetry balíčku Astro.
- `npm run check`: 0 errors, 0 warnings, 0 hints (Astro + TypeScript, včetně browser testů).
- `npm run lint`: úspěšná kontrola formátu přes Prettier a Astro plugin; nejde o vydávání formátovací kontroly za samostatný statický bezpečnostní audit.
- `npm run build` a `npm run build:production`: úspěšné, všechny důležité texty přímo v HTML. Žádný frameworkový klientský runtime.
- Reprodukční záznam: `docs/reports/build-checks.txt`.
- Backend testy se nespouštěly: změna je výhradně samostatná landing a zadání výslovně omezuje rozsah ověření. Žádné platné AGENTS.md nebylo nalezeno.

## Browser smoke a přístupnost

**12/12 Playwright testů prošlo** (`docs/reports/browser.json`). Testy samy úspěšně nastartovaly čerstvé produkční preview po zastavení původního serveru.

- Layout, celá stránka a axe při šířkách **1440, 1280, 768, 390 a 360 px**. V žádné šířce nevzniká horizontální overflow.
- Navigace a interní kotvy existují, žádné odkazy na samotné `#`, funkční registrační/login/mailto cíle.
- Review demo: editace, prázdný vstup, přepnutí Slack/Teams, schváleno → fronta, simulace publikace, store chyba → editace → nové schválení, reset. Vždy jasné, že jde o simulaci. Žádné síťové odesílání.
- Témata: vybrané téma má stav aria-pressed, plný popisek s obdobím a počtem, konzistentní zdrojové recenze a oznámení aktualizovaného nadpisu.
- Mobilní menu: Enter, tabulátor na první položku, Escape s návratem focusu, zavření po navigaci. FAQ nativním details/summary a klávesnicí.
- Všechny FAQ otevřené, mobilní menu otevřené a demo ve frontě: další axe kontroly bez nálezů.
- Bez JS: důležitý obsah, review, draft, tři zdrojové recenze, nativní mobilní menu a FAQ dostupné; reply akce skryté, témata neaktivní s vysvětleným fallbackem.
- Reduced motion vypíná smooth scrolling a přechody. Dlouhý schválený text nezpůsobí overflow; prázdný draft nelze schválit.
- Žádné pageerror/console error ani chybějící lokální assety v browser testu.
- Jedno H1, anglický lang, landmarks, skip link, označené formulářové pole, aria-live, tabulková sémantika portfolia a viditelný 3px focus.

Axe (`@axe-core/playwright` 4.14.x, pravidla WCAG 2 A/AA, 2.1 AA a 2.2 AA, navíc best practices) po opravách: **0 violations** ve všech pěti základních šířkách i testovaných rozbalených stavech. První průchod našel kontrast několika sekundárních textů; byly ztmaveny či zesvětleny a znovu změřeny. Automatický výsledek není certifikace WCAG.

Samostatný průchod klávesnicí od skip linku přes menu/CTA, Slack/Teams, textarea, schválení, témata a FAQ ověřil logické pořadí a viditelný focus. Skutečné tabulátorové kroky jsou v `docs/reports/keyboard-walkthrough.json`, ukázka focusu v `docs/screenshots/desktop-keyboard-focus.png`. Neproběhl audit s asistivní technologií typu VoiceOver/NVDA.

## 200% zoom

Vedle reflow testu 720 × 500 CSS px proběhl **skutečný browser tab zoom na 200 %** přes Chromium `tabs.setZoom(2)` v izolovaném dočasném rozšíření. Naměřeno zoom 2, viewport 720 CSS px, devicePixelRatio 2, scrollWidth 720 při původním viewportu 1440 px. Nejde o CSS `zoom` nebo pinch zoom. Rozšíření a profil se po kontrole smažou; na stránku se nic neinstaluje. Reprodukce: `npm run test:zoom`, výstup `docs/reports/browser-zoom.json`.

## Vizuální kontrola

Skutečně zobrazeny a prohlédnuty obě hero varianty, desktop/mobile celé stránky, tablet hero, podrobné výřezy analytické sekce a mobilního workflow/FAQ/CTA i sdílecí obrázek. Finální reprezentativní screenshoty:

- `docs/screenshots/desktop-hero.png`, `desktop-full.png` (1440 × 1000 viewport).
- `docs/screenshots/laptop-hero.png`, `laptop-full.png` (1280 × 900).
- `docs/screenshots/tablet-hero.png`, `tablet-full.png` (768 × 1024).
- `docs/screenshots/mobile-hero.png`, `mobile-full.png` (390 × 844).
- `docs/screenshots/mobile-small-hero.png`, `mobile-small-full.png` (360 × 800).
- `docs/screenshots/mobile-menu.png`, `mobile-demo-queued.png`, `desktop-200-percent.png` (reflow ekvivalent), `browser-200-percent.png` (skutečný zoom).

Vybrána varianta A: sdělení a celý editovatelný reply úkol jsou společně v prvním desktop viewportu. U B je schválení pod foldem. Dokumentováno v `docs/direction.md`; obě kompozice v `docs/variants/`.

Vědomá iterace: zvětšeny samotné zdrojové recenze a popisky analytického dema, zlepšeny kontrasty, doplněny explicitní přístupné názvy s významem čísel, u portfolia tabulková sémantika. Kontrola pokryla celé stránky včetně dlouhých textů, FAQ, footeru a závěrečného CTA; nic se nepřekrývá a CTA nejsou uříznutá.

## Lighthouse

Tři mobilní běhy Lighthouse **13.5.0**, Playwright Chromium **156.0.8078.4**. Produkční indexovatelný build přes lokální Astro preview; po měření skript obnoví bezpečný noindex build. Profil: mobile 390 × 844, deviceScaleFactor 1; simulované throttling Lighthouse mobile: RTT 150 ms, throughput 1638.4 kbps, CPU ×4. Každý běh resetuje storage. Detailní konfigurační údaje a časy jsou v `docs/reports/lighthouse-summary.json`; úplné HTML/JSON reporty jsou vedle něj.

| Běh | Performance | Accessibility | Best Practices | SEO | LCP | CLS | TBT |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 100 | 100 | 100 | 100 | 1.507 s | 0 | 0 ms |
| 2 | 100 | 100 | 100 | 100 | 1.504 s | 0 | 0 ms |
| 3 | 100 | 100 | 100 | 100 | 1.504 s | 0 | 0 ms |

Všechny cílové prahy splněny: P ≥90, A/BP/SEO ≥95, LCP ≤2,5 s a CLS ≤0,1. Laboratorní měření na localhostu se nerovná reálným uživatelským datům nebo výkonu budoucího CDN/hostingu.

## Externí cesty a obsahová důvěryhodnost

`npm run test:links`: všechny čtyři unikátní externí HTTPS cíle vracejí 200. Registrace a login navíc skutečně vykreslily formulář v Chromium, bez jeho odeslání. Záznam `docs/reports/external-links.json`. HEAD na těchto cestách vrací 404, proto konečné rozhodnutí vychází z reálného GET/renderu.

Hlavní CTA tedy vede na funkční veřejnou registraci, footer obsahuje login a ověřený kontakt `info@matee.cz`. Konzole je v češtině. Příslušná store oprávnění a případná dostupnost měsíčních reportů mají výslovné omezení. Všechny produktové údaje ve vzorech jsou označená syntetická data. Statistická marketingová tvrzení ze starého webu byla vynechána; Apple kvalitativní tvrzení má přímý čitelný zdroj. Kompletní doklady jsou v `docs/claims.md`.

## SEO a hranice ověření

Title/description, canonical, Open Graph/Twitter, 1200 × 630 PNG, favicon, robots a sitemap otestovány. Structured data obsahují jen SoftwareApplication a skutečného autora, bez vymyšlených cen/ratingu. Preview `noindex, nofollow` + robots Disallow; produkce explicitně `INDEXABLE=true`. Žádné trackery, cookie banner nebo externí fontové CDN. Font self-hostovaný a s OFL licencí.

Nebylo testováno vytvoření účtu, emaily, autorizovaný onboarding, živé storové odpovídání ani doručování do Slacku/Teams. Testovaná interakce je výslovně lokální demo. Bez screen-reader auditu a bez měření reálných uživatelů. Veřejný deployment, DNS, merge a externí zprávy nebyly provedeny.

Git: výchozí rozpracované změny mimo `landing/` byly porovnány binárním diffem se stavem před prací a jsou nezměněné. Aktivní větev zůstala `epic/v2`. `landing/SESSION_PROMPT.md` zachován. Žádný commit ani změna konzole nepatří k této implementaci.
