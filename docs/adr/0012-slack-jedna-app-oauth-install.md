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
- Vzniká závislost na App Directory review, ale až s finální doménou (§13.5 plánu); do té doby
  se jede na vlastní dev appce a self-hosteři si zakládají vlastní podle
  [docs/slack-app.md](../slack-app.md).
- Vyšší scope `chat:write.public` (psaní do veřejného kanálu bez pozvání bota) je vědomá
  výměna: bez něj je první krok onboardingu „napiš `/invite`" a klienti na něm zůstávají viset.
  U privátních kanálů pozvání bota potřeba je a runbook to říká.
