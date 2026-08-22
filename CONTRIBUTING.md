# Jak přispět

Díky, že se do toho chceš pustit. Tenhle soubor je krátký schválně — pravidla, která si nikdo
nepřečte, nikomu nepomůžou.

## Než začneš psát kód

- **Chyba?** Založ issue. Bezpečnostní chybu ne — ta jde na security@matee.cz
  ([SECURITY.md](SECURITY.md)).
- **Větší změna?** Napiš nejdřív issue s tím, jaký problém řešíš. Ušetří to oběma stranám
  odpoledne nad PR, který jde jiným směrem, než kam projekt míří.
- **Malá oprava?** Rovnou PR.

## Rozjetí

```bash
cp .env.example .env      # vyplň POSTGRES_PASSWORD
docker compose up --build
```

Bez Dockeru: potřebuješ JDK 21 a běžící Postgres, zbytek je v [README](README.md#vývoj-bez-dockeru).
Konzole (React) se vyvíjí zvlášť: [console/README.md](console/README.md).

## Co musí projít

```bash
./gradlew build
```

Sestaví, spustí testy (Kotest, databázové přes Testcontainers — Docker musí běžet) a zkontroluje
formát (ktlint). Formátování opraví `./gradlew ktlintFormat`.

Konzole:

```bash
cd console && npm ci && npm run lint && npm run build
```

## Jak to tady vypadá

- **Modulární monolit** v Kotlinu na Ktoru ([ADR 0002](docs/adr/0002-modularni-monolit-kotlin-ktor.md)).
  `core` je doména a porty, `persistence`/`crypto`/`channels`/`connectors` jsou adaptéry,
  `app` je wiring a HTTP.
- **Konvence commitů:** Conventional Commits (`feat:`, `fix:`, `chore:`…), zprávy česky.
  Popiš v nich **proč**, ne co — co se změnilo, řekne diff.
- **Komentáře patří k tomu, co není z kódu vidět.** Proč zrovna takhle, co se stane, když ne,
  jaká past tam číhá. Ne popis toho, co řádek dělá.
- **Rozhodnutí, která se těžko vracejí, mají ADR** v [docs/adr](docs/adr/). Nové: zkopíruj
  strukturu z 0001, další číslo, přidej řádek do tabulky.

## Na co si dát pozor

Tyhle čtyři věci se v code review hlídají pokaždé:

1. **Multi-tenancy.** Každá metoda repozitáře, která čte data organizace, bere `orgId` jako
   první parametr a překlápí ho do `WHERE org_id = ?`. Výjimky (scheduler) se komentují
   ([ADR 0009](docs/adr/0009-domenove-schema-tenancy.md)).
2. **Tajemství.** Otevřený obsah credentialu chodí jako `SecretPayload`, nikdy jako `String`.
   Neukládej ho, neloguj, nevracej z API — ven jde jen fingerprint.
3. **Migrace musí být dopředu kompatibilní.** Nasazuje se tak, že chvíli běží stará i nová
   verze; migrace, po které stará verze spadne, znamená výpadek. Sloupec se přidává jako
   nullable, mění se ve dvou krocích, maže až po vydání, které ho nepoužívá.
4. **Nová cesta v API** patří pod `requireSession` a `requireCsrf` — obojí je nasazené na
   stromě cest, takže se to stane samo. Když ji tam dáváš jinam, napiš proč.

## Testy

Doména a krypto unit testy, konektory kontraktní testy nad **zachycenými reálnými odpověďmi**
(`src/test/resources`), API nad opravdovým Postgresem přes Testcontainers. Fixtures nejsou
vymyšlené — všechny pocházejí z reálné odpovědi storu, protože přesně tam bydlí ty chyby,
které vymyšlený vstup neodhalí.

## Licence

Přispěním souhlasíš s tím, že tvůj kód půjde pod [AGPL-3.0](LICENSE)
([ADR 0007](docs/adr/0007-agpl-3.md)).
