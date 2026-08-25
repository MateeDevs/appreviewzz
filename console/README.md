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

## Co tu není a proč

- **Žádná komponentová knihovna.** Console má pár desítek obrazovek a jedno téma; vlastní
  CSS (`src/styles.css`) je kratší než konfigurace design systému a nemá co zastarat.
  Barvy, rádiusy a stíny jsou nahoře v souboru jako proměnné ve dvou sadách (světlá a tmavá),
  takže se vzhled ladí na jednom místě.
- **Žádné externí písmo.** CSP pouští `font-src` jen z vlastního originu a self-hostovaný
  webfont by za pár set kilobajtů koupil rozdíl, kterého si nikdo nevšimne — proto systémové.
- **Žádný stav mimo TanStack Query.** Pravda je na serveru, klient je jen pohled — proto
  tu není redux ani globální store.
- **Žádný token v `localStorage`.** Session je `httpOnly` cookie, kterou JavaScript nevidí;
  proti CSRF se posílá hodnota z cookie `arz_csrf` v hlavičce `X-CSRF-Token`.
