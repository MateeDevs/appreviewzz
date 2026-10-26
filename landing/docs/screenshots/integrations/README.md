# Screenshoty rozšíření integrací

Vyrenderované lokálně z produkčního statického preview. Produkční přístupy ani kanály se nepoužívají.

- `coming-soon-{1440,1280,768,390,360}.png`: finální výchozí Slack. Teams i Discord mají stejný veřejný stav „Coming soon“.
- `teams-*.png`, `discord-*.png`: přepnuté služby; Discord neslibuje přímé odpovědi.
- `hero-*.png`, `full-*.png`: nový text a kontext celé stránky.
- `no-js-390.png`: nativní rozbalování služeb bez JavaScriptu.
- `browser-200-percent.png`: skutečný 200% Chromium tab zoom (CDP snímek viewportu; Playwright běžný screenshot při tomto zoomu nedává spolehlivý výřez).
- `initial-*.png`: první iterace, ponechaná pro srovnání. Mohou obsahovat artefakt původního skrytí skip linku při dlouhém výřezu; finální CSS používá clip-path.
- `test-fixture/`: **výhradně testovací stavy** dostupné/chybějící/rozbité médium. Bitmapa je syntetická grafika s nápisem „TEST ONLY“, vytvořená v Playwright beforeAll v `tests/integrations.spec.ts`. **Není to screenshot Slacku, Teams ani jiného produktu.** Slouží ke kontrole galerie, velikosti, dialogu, fallbacku a dostupnosti. Nezveřejňovat jako produktové ukázky.

Reprodukce výchozích snímků: `npm run capture:integrations` s běžícím preview na 4327. Zoom: `npm run test:zoom`. V testovacích datech není žádný soubor nastaven do veřejné produkční konfigurace; `public/integrations/*/` zatím obsahuje jen `.gitkeep`. Produkční screenshoty dodá provozovatel po zprovoznění integrací.
