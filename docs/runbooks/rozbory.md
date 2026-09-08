# Runbook: rozbory recenzí

Co dělat, když se rozbory chovají jinak, než mají. Předpokládá přístup k `docker exec` do
API kontejneru (viz [nasazení do produkce](nasazeni-do-produkce.md)).

## Zapnutí

Rozbory jedou na stejném provideru a klíči jako návrhy odpovědí, liší se jen model.
V konzoli **Správa platformy** (dole v postranním panelu) → sekce *AI návrhy odpovědí a rozbory*:

| Klíč | Co to je |
|---|---|
| `ai.provider` | `gemini` nebo `none`. `none` = bez rozborů, recenze chodí jako dřív. |
| `ai.api_key` | Klíč Gemini. **Nikdy free tier** — trénuje na datech. |
| `ai.analysis_model` | Model pro tagování; výchozí `gemini-2.5-flash-lite`. |

Změna se propíše do minuty, restart není potřeba.

## Doplnění výkladů za historii

Nová appka má výklad jen u recenzí, které přišly po zapnutí. Zbytek se doplní dávkově:

```bash
appreviewzz analysis status --org <slug> --app <ID>     # kolik jich výklad má
appreviewzz analysis backfill --org <slug> --app <ID>   # doplní zbytek
```

Z konzole totéž udělá tlačítko *Doplnit za historii* na detailu aplikace. Běží na pozadí
v dávkách po 150 recenzích, takže se u velké appky vyplatí `status` po chvíli zopakovat.

## Historie recenzí

Rozbor umí vyložit jen recenze, které v databázi jsou. Google Play API dá ~týden zpět a jen
recenze s textem, takže historii Androidu drží **měsíční export v reportingovém bucketu**
(`reviews/reviews_<package>_<YYYYMM>.csv` — tentýž bucket i service account jako oficiální
hodnocení, viz nastavení aplikace). iOS historii vrací App Store Connect sám, jen se k ní
dostránkovává hlouběji než při běžném ingestu.

Kolik měsíců zpět, se vybírá při přidání aplikace (*Historie k rozboru*, 1–24 měsíců) a jde
změnit v jejím nastavení. Prodloužení zařadí import hned; jinak běží denně hodinu před denním
přehledem.

```bash
appreviewzz history import --org <slug> --app <ID> [--months <1-24>]
```

Bez `--months` se vezme nastavení aplikace. Výpis říká, kolik řádků archiv vrátil, kolik z nich
bylo nových a kolik už bylo v databázi z API (`už známé z API`).

**Co se z importu nedoručuje:** nic. Všechno se zakládá jako potlačené — jsou to data stará
dny až roky a část z nich jsou hodnocení bez textu, ke kterým není co napsat.

**Proč denně, a ne jednou:** export je jediné místo, kde jsou hodnocení bez textu. Kdyby se
četl jen při onboardingu, měla by appka historii s nimi a živé období bez nich — objem i podíl
spokojených by na té hranici skočily a srovnání „co se zlepšilo" by lhalo.

### Když historie nepřibývá

1. **Má appka bucket?** Bez něj se Android historie nedá číst vůbec — nastavení aplikace,
   pole *Reporting bucket Play Console*. Tlačítko u něj řekne, jestli na něj náš účet dosáhne.
2. **Je export v bucketu?** Play Console ho generuje jednou denně a pro měsíc bez jediné
   recenze nevznikne. `history import` vypíše `staženo 0`.
3. **DLQ:** `jobs failed` — úloha `reviews-history-app`, u jednorázového dotažení
   `reviews-history-backfill`. `AUTH` znamená odebranou roli Storage Object Viewer.
4. **Duplicity v inboxu.** Neměly by nastat: recenze z exportu mají ID s prefixem `csv:`
   a proti recenzím z API se párují časem odeslání (na vteřinu). Kdyby přesto přibyly, je to
   tenhle mechanismus a patří to do issue, ne k přemazání dat.

### Odpovědi napsané ve storu

Do „odpovězeno X z Y" v rozboru se počítají i odpovědi napsané v Play Console nebo App Store
Connectu, ne jen ty publikované přes nás — klient se ptá, jestli recenze odpověď dostala.
U recenze, která má obojí, platí ta dřívější. Bez toho by rozbor za dotaženou historii tvrdil
„odpovězeno 0", i kdyby se odpovídalo na všechno.

## Rozbor nepřišel

1. **Má appka výklady?** `analysis status`. Nula = zkontroluj `ai.provider` a klíč.
2. **Má kanál zapnuté rozbory?** Detail aplikace → *Kanály* → sloupec *Co chodí*.
3. **Nesedí den nebo čas?** Nastavení aplikace: *Den týdenního rozboru* + *Čas denního
   přehledu* (rozbor jde ve stejný čas). Platí v zóně aplikace.
4. **Neodešel už?** Rozbor se za dané období posílá jednou; druhý běh se zastaví o rezervaci
   v `analysis_digest`.
5. **Ruční spuštění:**
   ```bash
   appreviewzz analysis weekly run --org <slug> --app <ID> [--period-start 2026-08-31]
   ```
   Vypíše čísla, i když se nikam neposílala — při onboardingu je to nejrychlejší způsob,
   jak si rozbor prohlédnout před tím, než ho uvidí klient.

## AI padá

Selhání se do doručení nepromítne: recenze odejde bez štítků. V DLQ (`jobs failed`) se
objeví úloha `analyze-app`. Nejčastější příčiny:

- **429** — vyčerpaná kvóta projektu. Neopakuje se, backfill to zkusí při dalším běhu.
- **5xx / timeout** — jeden pokus navíc proběhne automaticky, pak se dávka odloží.
- **Neplatný JSON** — model nedodržel schema. Pokud se to opakuje, přepni model.

## Přepnutí modelu

```bash
appreviewzz analysis eval --file gold.jsonl --model gemini-2.5-flash
```

Porovná model proti ručně otagovanému zlatému setu (postup je v interních dokumentech).
Rozdíl v mikro-F1 pod pár procent nestojí za trojnásobnou cenu.

Po přepnutí modelu se **historie nepřepočítává**: staré výklady zůstanou, nové recenze
dostanou výklad z nového modelu. Model je u každého výkladu uložený, takže jde zpětně
poznat, co čím vzniklo.

## Změna taxonomie

Přidání nebo úprava tématu v `Taxonomy.kt` znamená **zvýšit `TAXONOMY_VERSION`**. Tím se
všechny staré výklady stanou neplatnými a backfill je postupně přepočítá. Bez toho by se
v jednom grafu míchala čísla ze dvou různých seznamů témat.

Vlastní témata aplikace se verze netýkají: změna popisu ovlivní jen nové recenze a historie
zůstane, jak je.
