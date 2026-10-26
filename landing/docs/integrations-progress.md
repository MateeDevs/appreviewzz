# Rozšíření integrací — průběh

## 25. 10. 2026 · zahájení

- Přečteno zadání, dokumentace landing, komponenty, testy a implementace Slack/Teams.
- Větev `feat/auth-onboarding`. Výchozí nesledované soubory `docs/design/auth-onboarding-prompt.md` a `landing/INTEGRATIONS_SESSION_PROMPT.md` zachováváme. Platné AGENTS.md nenalezeno.
- Veřejná dostupnost není doložena; Slack, Teams i Discord začínají jako `coming-soon`. Podpora přímé odpovědi je implementovaná u Slack/Teams, nikoli Discordu.
- Návrh: jediná typovaná konfigurace; přepínač služeb s velkým náhledem a nativním fallbackem bez JS; skutečná média mají galerii a zvětšení. Záměrný prázdný stav není screenshot produktu.
- Další krok: implementace sekce, sjednocení veřejných textů a testovací matice médií na izolovaném statickém buildu.

## Implementace a první render

- Přidaná konfigurace `src/data/integrations.ts`, sekce, desktop/mobil navigace a připravené veřejné adresáře.
- Dostupnost propisována do hero, metadat, pruhu, dema, workflow a FAQ. Demo popisuje sdílený workflow, ne autentické rozhraní aplikací.
- Skutečný první desktop/mobil render uložen jako `docs/screenshots/integrations/initial-*.png` a prohlédnut.
- Vizuální iterace: zvětšení stavu dostupnosti v hero a mobilních přepínačích; čitelnější samostatný kontakt pod přepínači; rezervovaný poměr stran médií a zvětšení v přístupném dialogu.
- První axe průchod odhalil nepovolenou roli na details a kontakt uvnitř tablistu. Opraveno progresivním převodem details na div tabpanel při JS a oddělením kontaktu. Bez JS zůstávají nativní rozbalovací služby.
- Izolovaný fixture build v `tests/fixtures/dist/` ověřuje kombinace médií. Do veřejných dat ani produkčního dist se testovací snímky nevkládají.
- Další krok: dokončit matici testů, screenshoty všech šířek, skutečný 200% zoom a dokumentaci.

## Ověření a dokončení

- Opakovaný kompletní průchod: `npm run check` (0 errors/warnings/hints), `npm run lint`, `npm run build`, `npm run test:browser` (21 testů); přístupnost nové sekce i všech mediálních stavů bez nálezů.
- Asset guard běží před oběma build variantami, kontroluje existenci i dekódování, skutečné rozměry, popisky a lokální cestu. Samostatný regresní test zahrnuje PNG/WebP/JPEG, chybějící a poškozený soubor i neplatnou cestu.
- Skutečný Chromium zoom 200 %: viewport 720 CSS px / scrollWidth 720 / DPR 2. Služby přepnuty, axe bez nálezů. Pro důkaz viewportu používáme CDP, protože běžný Playwright screenshot při tab zoomu poskytoval chybný výřez.
- Prohlédnuty hero i sekce na 1440/1280/768/390/360 px, varianta bez JS a testovací galerie/dialog/fallback. Po vizuálním review se více služeb na mobilu zalamuje po třech, snímky lze bez JS otevřít v původní velikosti. Skrytý skip link používá clip-path, aby dlouhé browser výřezy neobsahovaly artefakt offscreen prvku.
- Externí odkazy ověřeny skutečným GET: login, registrace, Apple a Matee = HTTP 200. Formuláře neodeslány.
- Výchozí veřejné složky obsahují pouze `.gitkeep`; syntetické testovací obrázky žijí pouze v ignorovaném `tests/fixtures/dist/`. Produkční build zkontrolován bez testovací routy/dat/médií.
- README obsahuje přesný postup médií i nezávislého zapnutí služby; claims a validation doplněny. Veřejné nasazení, skutečné zprávy ani store odpovědi se neprováděly. Cizí soubory zachovány; změny implementace jsou jen v `landing/`.
- Dočasný fixture build po ověření odstraněn; testy si jej příště sestaví znovu.
- Stav: hotovo. Později stačí vložit reálné anonymizované screenshoty a po potvrzení veřejného provozu změnit status příslušné položky na `available`, znovu sestavit a nasadit.
