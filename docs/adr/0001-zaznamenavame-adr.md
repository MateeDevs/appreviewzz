# 0001 — Zaznamenáváme architektonická rozhodnutí

- **Stav:** Přijato
- **Datum:** 2026-08-18
- **Kontext fáze:** F0

## Kontext

Projekt vzniká jako náhrada n8n workflows a míří na open source. Jednočlenný tým dnes,
přispěvatelé a self-hosteři zítra. Rozhodnutí typu „proč Postgres scheduler a ne SQS“ se
za půl roku nedají zrekonstruovat z kódu a v code review se opakují dokola.

## Rozhodnutí

Každé rozhodnutí, které je drahé vrátit (runtime, datová vrstva, krypto, licence, deployment
model), dostane ADR v `docs/adr/` — číslované, immutable. Když se rozhodnutí změní, vzniká nové
ADR a to staré se označí jako *Nahrazeno #NNNN*; nepřepisuje se.

Struktura: Kontext (co nás tlačilo), Rozhodnutí (co děláme), Důsledky (co nás to stojí),
Zvažované alternativy (a proč ne).

## Důsledky

- Onboarding přispěvatele = přečíst `docs/adr/`, ne vyzpovídat autora.
- Drobná režie u každého velkého PR.
- ADR nejsou dokumentace API ani návod; ty žijí v `docs/` a README.
