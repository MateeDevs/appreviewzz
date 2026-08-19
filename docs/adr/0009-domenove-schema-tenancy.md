# 0009 — Doménové schéma: `org_id` všude, enumy jako text s CHECK

- **Stav:** Přijato
- **Datum:** 2026-08-19
- **Kontext fáze:** F1

## Kontext

Multi-tenancy je u tohohle produktu bezpečnostní vlastnost, ne jen datový model: jedna
organizace nesmí za žádných okolností vidět credentials ani recenze jiné. Zároveň potřebujeme
schéma, které se dá měnit migrací bez zámků nad celou databází — enumy a číselníky se během
vývoje mění nejčastěji.

## Rozhodnutí

**1. `org_id` je na každé tabulce s daty tenanta**, i tam, kde by šel odvodit joinem
(`review`, `reply`, `review_message`, `rating_snapshot`). Repozitáře berou `orgId` jako první
parametr každé metody a překlápějí ho do `WHERE org_id = ?`. Výjimky (scheduler, routing
příchozí zprávy od Slacku/Teams) jsou v portech označené komentářem.

**2. Enumy jsou `text` + `CHECK`**, ne nativní Postgres typy. Nová hodnota je pak obyčejná
migrace místo `ALTER TYPE`; Exposed je čte přes `enumerationByName`.

**3. Pravda o schématu je Flyway migrace**, Exposed definice jsou jen typovaný pohled.
DDL se z Exposedu negeneruje. Že se ty dva pohledy nerozešly, hlídá integrační test nad
Testcontainers (`SchemaConsistencyTest`).

**4. Dedup recenzí je unikátní klíč `(app_id, platform, store_review_id)`**, ne seznam
zpracovaných ID jako v dnešním n8n. Editaci recenze pozná otisk obsahu (`content_hash`);
každé pozorované znění se ukládá do `review_revision`. Editace **není** tichý zápis: recenze
přejde do stavu `UPDATED` (i z `REPLIED`) a je znovu doručitelná — z trojky se mohla stát
pětka a tým na ni může odpovědět znovu. Zprávy v kanálu jsou proto vedené per znění
(`UNIQUE (review_id, channel_id, content_hash)`), takže se každá verze notifikuje právě jednou.

**5. Unikátnost balíčku/App Store ID je per organizace**, ne globální — jinak by si jedna
organizace zabráním identifikátoru mohla zablokovat onboarding jiné. Přístup ke storu stejně
hlídají credentials.

## Důsledky

- Cross-tenant izolace je testovatelná na úrovni repozitářů a testy na ni existují
  (`TenantIsolationTest`) — nespoléhá se na to, že si na filtr vzpomene volající.
- Denormalizované `org_id` je nutné držet konzistentní při zápisu. Zápisy jdou přes repozitáře,
  které ho doplňují samy; vazby napříč organizacemi (credential na cizí appku) zápis odmítne.
- Textové enumy neumí databázová kontrola typů na úrovni Exposedu — hodnotu mimo výčet
  zachytí až `CHECK`. To je cena za bezbolestné migrace.
- Historie znění recenzí roste; u příliš upovídaných recenzentů to je pár řádků navíc,
  za to je vidět, jak se recenze vyvíjela, a `UPDATED` se dá odlišit od prvního doručení.
- Recenze pod watermarkem (`notify_from`) zůstává potlačená i po editaci — jinak by
  připojení staré appky poslalo do kanálu první úpravu let staré recenze.
