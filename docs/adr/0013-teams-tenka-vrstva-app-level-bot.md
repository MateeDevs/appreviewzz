# 0013 — Teams: vlastní tenká vrstva nad Bot Frameworkem, bot app-level

- **Stav:** Přijato
- **Datum:** 2026-08-22
- **Kontext fáze:** F4

## Kontext

Microsoft Teams je druhý kanál, který musíme umět od prvního dne — dva z dnešních klientů
mají recenze právě tam. Dnešní n8n řešení posílá zprávy holými HTTP uzly a má v nich
**`client_secret` v plaintextu ve čtyřech workflow**, tenant natvrdo v URL a regionální
endpoint zadrátovaný na `smba.trafficmanager.net/emea`. Přidání klienta znamená editaci
workflow.

Pro Kotlin existuje Bot Framework Java SDK, jenže je fakticky opuštěné (poslední smysluplné
vydání je roky staré, závisí na starých verzích Jacksonu a nutí do vlastního hostingového
modelu). Alternativa je vlastní tenká vrstva nad třemi REST voláními, což je přesně to,
co dnes zvládá i n8n code node.

Druhá otázka je, **kolik botů**. Bot Framework umožňuje single-tenant i multi-tenant
registraci; per klient by to znamenalo tolik registrací, kolik je klientů.

## Rozhodnutí

**Vlastní tenká vrstva nad Bot Connector REST API a jeden Azure Bot na celý deployment.**

- Odchozí volání jsou tři: `POST /v3/conversations` (nové vlákno s kartou),
  `PUT …/activities/{id}` (přepsání karty po odeslání odpovědi) a `POST …/activities`
  (chyba do vlákna). Nic z toho nepotřebuje SDK.
- **Token pro Bot Connector se cachuje do vypršení** — n8n si o něj říká při každém requestu
  ve všech čtyřech workflow zvlášť.
- **`serviceUrl` je vlastnost instalace**, ne konstanta v kódu. Bere se z příchozí aktivity
  a ukládá se ve vaultu; zadrátovaný evropský host by mimo Evropu tiše nedoručoval.
- Registrace bota (`client_id`, `client_secret`) je **konfigurace deploymentu**, stejně jako
  slackový signing secret. Per klient se ve vaultu drží jen `TeamsInstall`: tenant, regionální
  endpoint a nepovinné ID týmu.
- Příchozí aktivity se ověřují proti Bot Connectoru: JWKS z jeho OpenID metadat (cache 24 h),
  `iss`, `aud` proti konfigurovanému app ID, `serviceUrl` z tokenu proti tělu aktivity,
  `nbf`/`exp` s tolerancí 5 minut a endorsements proti `channelId`.
- Karta používá `Action.Submit`, ne `Action.Execute`: parita s dneškem a hlavně žádná
  povinnost odpovědět `invokeResponse` do několika sekund. Publikace jde přes frontu.
- V `data` tlačítka je **jen `reviewId`**. Dnešní karta veze celý obsah recenze i `clientId`
  a reply listener tomu věří — kdokoli s přístupem ke kartě umí podstrčit odpověď jinam.
  Routing jede přes vlastní záznam v `review_message`.

## Důsledky

- Žádná závislost na neudržovaném SDK; celá vrstva je pět souborů s kontraktními testy nad
  fixtures a testem podpisu proti vygenerovanému RSA klíči.
- Onboarding klienta je „přidej aplikaci do týmu" + `teams connect --tenant …`, ne editace
  workflow. Rotace secretu je jedna proměnná prostředí, ne čtyři uzly.
- Self-host si musí založit vlastního Azure Bota; je to zdokumentované
  ([docs/teams-bot.md](../teams-bot.md)) a je to tatáž práce, jakou dnes odvádíme my.
- Cenou je, že **nepodporujeme víc botů v jednom deploymentu**. Kdyby to někdy bylo potřeba
  (agentura hostující víc značek), rozšíří se `TeamsInstall` o vlastní registraci a ověření
  se bude vybírat podle `recipient.id` z aktivity. Dokud to nikdo nechce, není důvod.
- Recenze zakládá **vlastní vlákno**, kdežto ve Slacku je zpráva v kanálu. Je to rozdíl proti
  Slacku, ale odpovídá tomu, jak se Teams používá — a chybová hlášení tak nezaplevelí kanál.
