# 0006 — Jeden image, role `api` a `worker`

- **Stav:** Přijato
- **Datum:** 2026-08-18
- **Kontext fáze:** F0

## Kontext

HTTP vrstva (REST pro konzoli, webhooky Slacku a Teams) a jobová vrstva (ingest, ratingy,
publikace odpovědí) mají různé profily zátěže. Webhook Slacku musí odpovědět do 3 sekund;
ingest recenzí může běžet minuty. Zároveň nechceme dva buildy, dva Dockerfily a dvě verze.

## Rozhodnutí

Jeden image, jeden `main()`, chování řídí `APPREVIEWZZ_ROLE=api|worker`. Obě role vystavují
`/health/live`, `/health/ready` a `/metrics`, aby je orchestrátor probovat uměl stejně.

Migrace pouští jen role `api` (`DATABASE_MIGRATE_ON_START`), aby se instance při startu
nepřetahovaly o Flyway zámek.

## Důsledky

- Jeden artefakt = jedna verze v provozu, žádný skew mezi API a workerem.
- Škáluje se nezávisle: `api` podle provozu z webhooků, `worker` podle počtu klientů.
- Image nese kód, který v dané roli neběží. Za to se platí velikostí, ne složitostí.
- Role je runtime konfigurace — špatně nastavená proměnná znamená špatnou roli. Proto `Role.parse`
  padá hned při startu s vypsáním povolených hodnot, ne tichým fallbackem.
