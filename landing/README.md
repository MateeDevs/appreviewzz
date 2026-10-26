# appreviewzz · landing page

Samostatný anglický marketingový web v Astro + TypeScript. Příběh **App reviews. Answered. Understood.** spojuje recenze z App Store/Google Play, odpovědi schvalované týmem ve Slacku/Teams a rozbory zpětné vazby. Chatové integrace jsou nyní označené „Coming soon“; kód dokládá schopnosti, ne připravenost veřejného provozu. Celý obsah vzniká při statickém buildu. Nepotřebuje Kotlin, DB, účet, API ani store klíče.

## Spuštění

Node **≥ 22.23.4**, npm. Ověřeno s Node 25.2.1 a npm 11.6.2.

```sh
cd landing
npm ci
npm run dev
```

Lokální URL: **http://127.0.0.1:4327/**. Port je oddělený od konzole (5173). Pokud už na 4327 běží preview, nejdřív jej ukončete; Astro může při kolizi zvolit jiný port a vypíše ho do terminálu.

```sh
npm run check          # Astro + TypeScript, včetně typů browser testů
npm run lint           # kontrola formátu zdrojů (Prettier + Astro plugin)
npm run build          # bezpečný noindex build do dist/
npm run preview        # skutečný statický produkční výstup na :4327
```

V běžném terminálu se server ukončí Ctrl+C. Pro explicitní běh na pozadí Astro 7 nabízí `npm run preview -- --background`; zastavení `npx astro preview stop`, stav `npx astro preview status`. V agentovém prostředí může Astro pozadí zvolit samo. Browser test server používá `--ignore-lock`, aby zůstal v popředí a šel řídit Playwrightem.

## Co a kde upravit

- `src/pages/index.astro` — kompletní obsah, metadata, navigace a footer.
- `src/components/` — značka, ikony, reply demo, rozbor témat a FAQ.
- `src/styles/global.css` — tokeny, typografie a responzivní styly.
- `src/data/site.ts` — **jediné místo pro veřejné cílové URL**: kanonická doména, registrace, login, kontakt a autor. Žádná tajemství.
- `src/data/integrations.ts` — služby, veřejná dostupnost, podpora přímých odpovědí a volitelné screenshoty.
- `src/data/demo.ts` — konzistentní syntetické recenze fiktivní aplikace Atlas, období 12.–18. října 2026. Žádná produkční data.
- `public/fonts/` — Geist variable a licence OFL, samostatná kopie z konzole.
- `public/og.png` — hotový sdílecí obrázek 1200 × 630; `public/favicon.svg` — značka.

Ukázky jsou lokální. Schválení zobrazí simulovanou frontu; návštěvník může následně simulovat publikaci nebo chybu storu. Nic se neodesílá a data se neukládají. Při vypnutém JS zůstane HTML obsah, review/draft, zdrojové recenze, nativní FAQ a mobilní menu. Přepínače analýzy jsou v tomto režimu neaktivní a fallback vysvětluje omezení.

## Ověření

Po sestavení (v `landing/`):

```sh
npx playwright install chromium
npm run test:browser
```

Playwright spustí preview, pokud není dostupné. Ověřuje pět šířek 1440/1280/768/390/360, axe, CTA, metadata, assety, simulované stavy odpovědi, témata, klávesnici, menu, FAQ, reduced motion, dlouhou odpověď a obsah bez JS. Testy neodesílají registraci ani nevolají produkční API.

Další kontroly vyžadují běžící `npm run preview` na portu 4327:

```sh
npm run test:links     # skutečný browser GET externích odkazů; bez formulářových submitů
npm run test:zoom      # skutečný Chromium zoom 200 %, izolované dočasné rozšíření
npm run capture        # pět šířek, hero + full page, dvě varianty hero
npm run share-image    # znovu vyrobí public/og.png; potom je nutný nový build
npm run measure        # 3 mobilní Lighthouse běhy, viz níže
```

`measure` nejdřív vytvoří indexovatelný produkční build, změří jej přes lokální preview a v `finally` obnoví běžný noindex build. Veřejný deployment nedělá. Lighthouse má při neindexovatelném preview úmyslně nižší SEO skóre; produkční měření proto hodnotí výstup určený pro veřejnou doménu. Výsledky a přesná konfigurace jsou v `docs/reports/`, lidské vyhodnocení v `docs/validation.md`. Jde o laboratorní měření, nikoli reálná uživatelská data.

## Statické nasazení

```sh
npm ci
npm run check
npm run lint
npm run build:production
```

Publikujte **pouze obsah `landing/dist/`** na statický HTTPS hosting pro `https://appreviewzz.com/`. Nepublikujte zdroje, docs, testy nebo node_modules. Není potřeba Node runtime ani SPA fallback na každou URL. `index.html`, `_astro/`, fonty, favicon, OG obrázek, robots.txt a sitemap.xml musí být dostupné ze stejného originu; neznámé cesty mají vracet 404.

`npm run build:production` je explicitní opt-in `INDEXABLE=true`: `index, follow`, robots Allow a odkaz na sitemapu. **Všechny preview/staging buildy používejte přes `npm run build`**, které mají `noindex, nofollow` a robots Disallow. Produkční artefakt nekopírujte na veřejný preview hostname. Hosting může preview doplnit `X-Robots-Tag: noindex`; robots sám není ochrana přístupu. Canonical i OG zůstávají na zamýšlené produkční doméně. Změní-li se doména, upravte společně `site.origin` a `astro.config.mjs`.

Doporučené cache: hashované `_astro/*` dlouhodobě immutable; HTML/robots/sitemap krátce s revalidací. Font a `og.png` mají pevnou URL, proto je necacheujte navždy bez invalidace. Zapněte kompresi HTML/CSS/JS a HTTPS. Web nepřidává trackery, cookies ani externí fontové požadavky.

### Staging

Landing staging běží na **https://staging.appreviewzz.com/** jako samostatný Cloudflare Pages projekt `appreviewzz-staging`. Sleduje větev `epic/v2`, sestavuje adresář `landing/` příkazem `npm run build` a publikuje pouze `dist/`. Používá Node 24 a `INDEXABLE=false`. Merge PR do epicu spouští nový build automaticky; nasazený commit lze ověřit v detailu deploymentu Cloudflare Pages. GitHub Actions workflow `Deploy` nasazuje konzoli a backend přes Coolify, nikoli landing page.

## Podklady a předání

- `docs/direction.md` — produktový audit, čtyři referenční SaaS weby a výběr ze dvou vyrenderovaných hero variant.
- `docs/claims.md` — doložení veřejných tvrzení, omezení a důvody vynechání původních statistik.
- `docs/validation.md` — skutečné výsledky, vizuální iterace a rozsah ověření.
- `docs/screenshots/` — finální desktop/notebook/tablet/mobil, celý web, interaktivní stavy a 200% zoom.
- `docs/variants/` — obě hero kompozice a alternativní CSS.
- `docs/progress.md` — konečný stav session.

Registrace a login vedou na skutečnou konzoli (ověřeno GET 200 a vykreslení formulářů). Konzole je nyní česky; přihlášené end-to-end onboarding flow a odeslání emailů nebyly testovány. Právní dokumenty nebyly vymýšleny: v ověřených podkladech není relevantní veřejná URL pro aktuální produkt, proto web nenabízí falešný odkaz. Potřebné právní dokumenty je nutné zajistit v rámci veřejného provozu produktu.

## Integrace: dostupnost a skutečné screenshoty

Jediná konfigurace je **`src/data/integrations.ts`**. Výchozí Slack, Microsoft Teams a Discord mají `status: 'coming-soon'`, protože připravenost veřejného provozu není doložena. Kód Slack/Teams podporuje přímé odpovědi (`directReply: true`); Discord má pouze plánované doručování (`false`). Dostupnost služby je nezávislá na počtu obrázků. Změna jedné položky se propíše do sekce, hero, pruhu, dema, FAQ i metadat.

1. Vložte anonymizovaný snímek do `public/integrations/slack/`, `public/integrations/teams/` nebo `public/integrations/discord/`. Například `public/integrations/slack/review.webp` má veřejnou cestu `/integrations/slack/review.webp`. Názvy jsou volné, doporučené malé ASCII znaky a pomlčky; podporované přípony `.webp`, `.png`, `.jpg`, `.jpeg`. Složky jsou připravené pomocí `.gitkeep`.
2. V existující položce upravte `media`. Zadejte skutečnou šířku/výšku v pixelech; jejich poměr rezervuje prostor před načtením. Obrázky se nedeformují ani neořezávají. `alt` popisuje obsah pro čtečku, `caption` viditelně vysvětluje konkrétní situaci. Jeden snímek stačí:

```ts
{
  id: 'slack',
  name: 'Slack',
  icon: 'slack',
  status: 'coming-soon',
  description:
    'Bring new reviews into your channel. Edit the AI draft, approve your reply and send it back to the store, right from Slack.',
  directReply: true,
  media: [
    {
      src: '/integrations/slack/review.webp',
      width: 1440, // skutečné rozměry konkrétního souboru
      height: 1000,
      alt: 'An app review in Slack with an editable AI reply and a send button',
      caption: 'A new review and an AI draft, ready for your team to edit and approve.',
    },
  ],
}
```

3. Druhý snímek přidejte jako další objekt do `media`, například `/integrations/slack/reply.png` s vlastním `alt`, `caption` a rozměry. Galerie nabídne přepínání a zvětšení na původní velikost; Escape vrací fokus na tlačítko. Bez JS jsou snímky pod sebou a služby lze rozbalit. Další službu přidáte jako objekt do stejného pole s unikátním stabilním `id`, názvem, ikonou, stavem, popisem a schopností odpovídat. Případnou novou ikonu přidejte do `src/data/icons.ts` a `src/components/Icon.astro`; další seznam služeb není potřeba.
4. **Až po zprovoznění veřejné integrace** změňte u příslušné služby jediný řádek na `status: 'available'`. Snímek ji sám nezapíná. Dostupná služba bez médií ukáže „Available“ + „Preview coming soon“. Připravovaná služba i se snímkem zůstane „Coming soon“. Text popisu upravujte při změně schopností; `directReply` není přepínač provozu a nezapíná backend.
5. Ověřte a sestavte web:

```sh
npm run test:assets  # existence, dekódování, cesty, rozměry, alt a popisky
npm run check
npm run lint
npm run build        # automaticky spouští také kontrolu assetů
npm run test:browser
npm run preview
```

Změna souboru či konfigurace se na veřejném statickém webu projeví **až po novém buildu a nasazení**. Sdílecí obrázek je také statický: po změně dostupnosti spusťte s běžícím preview `npm run share-image`, potom znovu build. Soubor `docs/screenshots/` není veřejná galerie. Screenshoty před vložením anonymizujte: odstraňte tokeny, osobní údaje a názvy soukromých kanálů.

`test:browser` sestaví i oddělený lokální testovací web `tests/fixtures/dist/` na portu **4328**. Obsahuje záměrné 404 a označená syntetická média pro ověření fallbacku, nikoli produktové screenshoty. Nikdy jej nenasazujte. Produkční `dist/` tyto routy ani testovací média neobsahuje. Regresní test assetové kontroly vytváří PNG/WebP/JPEG pouze v dočasném adresáři a po ověření jej maže.

`npm run capture:integrations` s běžícím preview pořídí nové screenshoty a záznam rozměrů do `docs/screenshots/integrations/` a `docs/reports/integrations-visual.json`. Výsledky rozšíření a omezení jsou v `docs/validation.md`, průběh v `docs/integrations-progress.md`.
