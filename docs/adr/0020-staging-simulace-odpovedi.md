# 0020 — Simulace odpovědí na stagingu

- **Stav:** Přijato
- **Datum:** 2026-11-03

## Kontext

Staging čte skutečné recenze a při testu Slack/Teams callbacku mohl publikovat skutečnou
odpověď. Označení ALPHA odlišuje prostředí, ne právo zapsat odpověď do storu.

## Rozhodnutí

Správce platformy může pouze na stagingu měnit `replies.simulation_enabled`; výchozí
hodnota je `true`. Ochrana je ve společném `PublishReplyUseCase`, před vyhledáním klíče
k publikaci i voláním storu. Platí pro oba story a všechny zdroje odpovědí. Čte se bez TTL.

Simulace ukládá skutečný text a autora, audit `reply.simulated` a upraví původní chatovou
zprávu s jednoznačným testovacím označením. Nemění recenzi na skutečně zodpovězenou.
Příznak se zachytí u zprávy, úlohy a odpovědi; pozdější vypnutí nesmí publikovat čekající
test, odpověď ze staré testovací zprávy ani dříve simulovaný text.

Databáze přidává nullable příznaky s významem `null=false`. Dokončená simulace používá
existující terminální stav `PUBLISHED` společně s `simulated=true` a `publishedAt=null`.
Nová neznámá hodnota enumu by rozbila čtení ve starší verzi; ponechání PENDING by starému
publikačnímu procesu naopak dovolilo odpověď později odeslat. UI a API mají explicitní
příznak simulace a skutečné publikace v agregacích vyžadují vyplněné `publishedAt`.

Nové úlohy mají jméno `process-reply`, které starý worker nepřebírá. Nový worker současně
registruje původní `publish-reply`, aby po upgradu dokončil čekající staré úlohy. Všechny
nové úlohy sdílejí jedno jméno a stejný klíč deduplikace bez ohledu na aktuální režim.

## Důsledky

Pilot může prověřit callback, frontu, uložení a aktualizaci Slack/Teams i bez práva
publikovat do storu. Nastavení v produkci není dostupné, ani když databáze obsahuje jeho
hodnotu. Simulace nenahrazuje samostatný skutečný test store publisheru. Zapnutí neodvolá
již odeslaný HTTP požadavek; pilot začíná po dokončení nasazení API a workeru.

Postup a testovací matice: [Testování odpovědí](../testing/reply-simulation.md).
