# 0002 — Modulární monolit v Kotlin/Ktor

- **Stav:** Přijato
- **Datum:** 2026-08-18
- **Kontext fáze:** F0

## Kontext

Systém stahuje recenze ze dvou store API, doručuje je do Slacku/Teams, publikuje odpovědi a
počítá denní ratingy. Provozní realita: jeden vývojář, cloudová multi-tenant instance a
požadavek, aby self-hoster rozjel celý produkt jedním `docker compose up`.

## Rozhodnutí

Jeden Gradle multi-module build, jeden deployovatelný artefakt. Moduly (`core`, `persistence`,
`crypto`, `connectors/*`, `channels/*`, `ai`, `jobs`, `app`) drží hranice na úrovni build
grafu — `core` neví nic o Ktoru ani o Postgresu, konektory nevědí o kanálech.

Runtime: Kotlin 2.4 na JVM 21, Ktor 3 pro server i HTTP klienta, coroutines pro I/O.

## Důsledky

- Self-host má právě dvě povinné komponenty: aplikaci a Postgres.
- Hranice modulů hlídá kompilátor, ne dohoda. Rozpad na služby později je mechanický, protože
  `core` nemá závislost na infrastruktuře.
- Škálování je horizontální přes více instancí role `worker` (viz [0004](0004-db-scheduler.md)),
  ne přes rozřezání na služby.
- Celá aplikace se nasazuje najednou — chyba v jednom konektoru může shodit proces. Mitigace:
  izolace chyb v job vrstvě, health per konektor.

## Zvažované alternativy

- **Mikroslužby.** Pro tuhle velikost domény jen daň navíc: síť, deployment, korelace logů.
- **Spring Boot.** Bohatší ekosystém, ale těžší start, větší image a víc magie než tenhle
  rozsah potřebuje. Ktor je blíž Kotlinu a coroutines.
- **Node/TypeScript.** Sdílený jazyk s konzolí, ale horší podpora pro dlouho běžící joby,
  krypto knihovny a typovou doménu.
