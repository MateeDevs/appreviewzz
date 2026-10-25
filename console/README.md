# Console

React SPA (Vite + TypeScript + TanStack Query) pro self-serve onboarding, správu appek,
kanálů a klíčů, review inbox, delivery health a audit log.

## Jak to běží

Console se buildí do statických souborů a **servíruje ji Ktor ze stejného image** jako API
([ADR 0008](../docs/adr/0008-hosting-coolify-kek-v-kms.md)) — odpadá tím CloudFront, S3
i certifikát v us-east-1. Node je potřeba jen při buildu, v runtime image žádný není.

```bash
npm install
npm run dev        # vývoj na :5173, /api si proxuje na běžící server :8080
npm run build      # produkční build do dist/
npm run lint       # eslint
npm run typecheck  # tsc bez emitu
```

Při vývoji je potřeba mít vedle toho spuštěné API (`docker compose up` v kořeni repa).
Proxy míří na stejný origin schválně: session drží `httpOnly` cookie, takže by přes
cizí origin nefungovala stejně jako v produkci.

## Vizuální systém Signál

Tokeny jsou v `src/styles/tokens.css`; sdílené ovladače v `components.css`, typografie
v `base.css`, navigace/auth v `shell.css` a kompozice produktu v `workspaces.css`.
Pořadí importů definuje vrstvy. Nové styly patří ke své komponentě, ne do vrstvy globálních výjimek.
Geist variable je self-hostovaný WOFF2 (70 kB), s OFL licencí v `public/fonts/`.
SVG značka a favicon používají motiv signálu. Pro Světlý/Tmavý/Systém se ukládá jen preference;
blokující `public/theme.js` nastaví téma před CSS bez inline skriptu a bez změny CSP.

Inbox ukládá filtry a otevřený detail do URL. Koncepty jsou jen v paměti konkrétní relace,
oddělené organizací/aplikací/recenzí; při přihlášení/odhlášení se mažou. Obnovení nebo zavření
stránky s konceptem chrání browser potvrzení. Žádný token není v `localStorage`.
Session zůstává httpOnly cookie, CSRF hodnota z `arz_csrf` jde v `X-CSRF-Token`.

## Schválený směr a produktový jazyk

Zpětná vazba uživatele z 25. 10. 2026: nový redesign se líbí a je schválený jako další
vizuální směr. Recenzím v uživatelských textech neříkáme „signály“; používáme „recenze“
nebo „zpětná vazba“, pro analytické výkyvy „upozornění“. Úvodní věta je
„Každá recenze je zpětná vazba.“ Název vizuálního směru a SVG motiv jsou interní designový kontext.

## Browser regrese

```bash
npm ci
npx playwright install chromium
npm run test:browser
npm run test:visual  # navíc snímky do docs/design/aaa/after
```

Testy používají výslovně syntetické fixtures v `e2e/fixtures.ts`, nedotýkají se storů ani
Slacku/Teams. Nejde o staging E2E. Playwright a axe jsou pouze vývojové závislosti.
Stav doručení a skutečné staging ověření: `../docs/design/aaa/progress.md`.
