# 0012 — Jedna Slack App s OAuth installem místo bota per klient

- **Stav:** Přijato
- **Datum:** 2026-08-19
- **Kontext fáze:** F2

## Kontext

Dnešní n8n řešení má pro každého klienta **vlastního Slack bota**: vlastní appku, vlastní bot
token v n8n credential store a vlastní signing secret napsaný natvrdo v uzlu. Nový klient tedy
znamená ruční založení appky, ruční vložení tokenu a ruční větev ve workflow — přesně ten
model, kvůli kterému se přepisuje celý systém. Navíc jsou v exportu workflows vidět
i konkrétní secrets (viz bezpečnostní nálezy §3 plánu).

K tomu se váže rozhodnutí z konce F1: appka **půjde do App Directory**. To znamená stavět ji
rovnou podle jejich požadavků — minimální scopes, OAuth install flow, žádné user tokeny.

## Rozhodnutí

**Jedna Slack App pro všechny klienty, instalace přes OAuth v2, token workspace ve vaultu.**

- Scopes: `chat:write`, `chat:write.public`, `channels:read`. Nic víc.
- **Žádný scope na historii kanálu.** Stav „✅ odpovězeno" se skládá z našich dat, ne ze
  stažené původní zprávy — dnešní řešení kvůli tomu volá `conversations.history`.
- Instalační odkaz nese **podepsaný `state`** (HMAC signing secretem, platnost 2 hodiny), který
  váže instalaci na organizaci. Odkaz se otevírá v prohlížeči, kde nikdo přihlášený není, takže
  je to jediné, co brání nainstalovat appku „za cizí organizaci".
- Token workspace se ukládá jako credential typu `SLACK_INSTALL` — zašifrovaný stejným vaultem
  jako klíče ke storům. Ven jde jen název workspace.
- Ověření příchozích požadavků: jeden app-level signing secret, kontrola stáří požadavku
  (±5 min) a časově konstantní porovnání podpisu.
- Zprávy nepoužívají custom emoji: `:empty_star:` z dnešní šablony existuje jen ve workspace
  klienta a v cizím workspace by se ukázal jako holý text.

## Důsledky

- Onboarding klienta je odkaz, ne naše práce v Slack API konzoli — což je předpoklad
  self-serve console (F3).
- Rotace tajemství se zjednodušila: jeden signing secret a jeden client secret místo jednoho
  páru na klienta.
- **Do schválení v App Directory jedeme v režimu jednoho workspace** — našeho. První klienti
  jsou partneři, kterým nevadí, že jim recenze chodí do našeho Slacku a našich kanálů; každý
  má vlastní organizaci a vlastní kanál, takže se datově nemíchají a přechod na vlastní
  workspace je pak jen nová instalace. Token se v tomhle režimu vkládá ručně
  (`slack connect`) a vzniká z něj stejný credential jako po OAuth instalaci — install flow
  se tím nezahazuje, jen se zatím nepoužívá. Submit do App Directory je proto až na konci
  (F5/launch), s finální doménou.
- Vyšší scope `chat:write.public` (psaní do veřejného kanálu bez pozvání bota) je vědomá
  výměna: bez něj je první krok onboardingu „napiš `/invite`" a klienti na něm zůstávají viset.
  U privátních kanálů pozvání bota potřeba je a runbook to říká.

## Doplnění — jedna appka i pro prostředí

Staging a produkce sdílejí Slack App `appreviewz`. Jednotlivá prostředí mají oddělená data
v databázi a vaultu, ale společné OAuth údaje a signing secret appky. OAuth návratové URL
jsou dvě, interactivity Request URL jedna: `https://console.appreviewzz.com/webhooks/slack/interactivity`.
Produkční API a worker slouží jako společný příjem už během stagingového pilotu.
Nové zprávy nesou podepsanou hodnotu tlačítka
s prostředím, organizací a recenzí; společný příjem z ní určí cíl a vzdálený callback
předá přes trvalou úlohu `slack-relay`. Relay endpoint ověřuje doménově oddělený HMAC,
čerstvý timestamp, cílové prostředí a vazbu zprávy v DB. Znovu nic nepřeposílá.

Stagingové zprávy včetně aktualizací a chyb končí oddělovačem a upozorněním ALPHA.
Text upozornění nemá vliv na routing. OAuth state je svázaný s prostředím, aby společný
signing secret neumožnil záměnu databází při instalaci. Podrobná konfigurace a pilotní
ověření jsou v [návodu Slack App](../slack-app.md).
