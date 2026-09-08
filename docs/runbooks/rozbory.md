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

V sekci *Rozbory recenzí* jsou tři prahy, které platí pro všechny aplikace:

| Klíč | Co to je |
|---|---|
| `analysis.min_reviews` | Kolik recenzí **s textem** se musí nasbírat, aby rozbor odešel. Výchozí 10. |
| `analysis.min_topic_count` | Od kolika zmínek je téma tématem. Výchozí 3. |
| `analysis.top_issues` | Kolik témat se vejde do zprávy do kanálu. Výchozí 3. |

První dva se dají u konkrétní aplikace přebít v jejím nastavení — appky se objemem liší
o řád. Prázdné pole v konzoli znamená „platí platforma".

## Co se do rozboru počítá

**Jen recenze s textem.** Hodnocení bez textu jsou u Androidu většina (v archivu Play Console
klidně čtyři pětiny) a jejich „nálada" je jen přepsaná hvězdička — kdyby se počítala do
rozboru, byl by z něj průměr hvězd a témata by se v něm ztratila. Hvězdy má na starosti denní
přehled hodnocení, rozbor je o tom, co lidé píšou.

## Kadence a odložený termín

Aplikace má v nastavení volbu **týdně / měsíčně**. Týdenní rozbor jde v den a čas z nastavení,
měsíční prvního dne v měsíci ve stejný čas.

Když se od minulého rozboru nenasbírá `analysis.min_reviews` recenzí s textem, **termín se
přeskočí a období zůstane otevřené** — příští běh naváže od konce posledního odeslaného
rozboru. Z appky s řídkým provozem tak přijde jednou za čas rozbor za tři týdny místo tří
zpráv „zatím málo dat". V logu to je jako `Rozbor … odložen`, v CLI jako `neodesláno`.

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
3. **Nesedí den nebo čas?** Nastavení aplikace: *Jak často chodí rozbor*, *Den týdenního
   rozboru* a *Čas denního přehledu* (rozbor jde ve stejný čas). Platí v zóně aplikace.
4. **Nenasbíralo se dost recenzí?** Nejčastější důvod. Ruční běh to řekne rovnou; práh je
   `analysis.min_reviews` a výjimka pro aplikaci je v jejím nastavení.
5. **Neodešel už?** Rozbor se za dané období posílá jednou; druhý běh se zastaví o rezervaci
   v `analysis_digest`.
6. **Ruční spuštění:**
   ```bash
   appreviewzz analysis run --org <slug> --app <ID> [--period-start 2026-08-31] [--force true]
   ```
   Vypíše čísla, i když se nikam neposílala — při onboardingu je to nejrychlejší způsob,
   jak si rozbor prohlédnout před tím, než ho uvidí klient. `--force true` pošle i pod prahem;
   bez něj se u appky s řídkým provozem čeká, až se období nasbírá.

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

## Výkyvy (alerty)

Alert vzniká na konci dotagování appky, ne z vlastního plánovače — výkyv se pozná až
z výkladů a ty vznikají právě tam. Pravidlo: baseline 28 dní bez dneška, `z ≥ 3` a zároveň
aspoň 5 recenzí za den. Pod dvěma týdny historie se alert neposílá vůbec.

Dedup je unikátní klíč `(app_id, kind, topic_key, window_date)` v `analysis_alert`:
dotagování jede po dávkách a jedna appka jich za den spolkne klidně deset, ale zpráva
odejde jednou. Alerty za 90 dní jsou v konzoli na stránce *Rozbory*.

Když se v jeden den potkají oba druhy (záporný výkyv i výkyv tématu, což je běžné —
vymknou se naráz), **zaznamenají se oba, ale zpráva odejde jedna**: ta o záporném výkyvu,
protože v textu už nese nejčastější téma. V konzoli jsou vidět oba.

**„Přišel alert, který přijít neměl."** Podívej se do `analysis_alert` na `expected`
a `z_score` — z toho je vidět, proti čemu se to měřilo. Nejčastější příčina je dotagování
historie: když se za jeden den doplní výklady tisíci recenzím, `submitted_at` je sice
rozprostřený správně, ale u appky s krátkou baseline může den vyjít jako výkyv. Odesílání
se dá utlumit vypnutím `deliver_analyses` na kanálu.

**„Alert nepřišel, i když se něco stalo."** Zkontroluj tři věci: má appka aspoň 14 dní
recenzí s textem, mají recenze z toho dne výklad (`analysis status`), a je den v zóně
aplikace ten, který čekáš — hranice je půlnoc u klienta, ne v UTC.

## Měsíční report pro klienta

Generuje se prvního dne v měsíci v 6:00 v zóně aplikace (`monthly-report`) organizacím
s plánem `INSIGHTS` nebo `AGENCY`; `STARTER` report nedostane, aby se databáze neplnila
JSONem, na který nikdo neklikne. Plán se jinak nevynucuje — mění se přes `org plan`.

```bash
docker exec <api> java -jar /app/app.jar seed analysis report generate --org matee --app <ID> --month 2026-08
docker exec <api> java -jar /app/app.jar seed analysis report share --org matee --report <ID>
docker exec <api> java -jar /app/app.jar seed analysis report share --org matee --report <ID> --off true
```

Agregáty jsou **zmrazené**: přegenerování měsíce je přepíše, sdílený token přitom zůstane —
odkaz poslaný klientovi nesmí přestat platit kvůli tomu, že se doplnily výklady. Zrušení
sdílení token maže, takže starý odkaz přestane platit okamžitě a natrvalo; nové sdílení
vyrobí jiný.

Veřejná stránka je `/r/<token>`: bez session, bez skriptů, bez externích zdrojů, s tiskovým
CSS. PDF si z ní udělá prohlížeč („Uložit jako PDF"). Neexistující i zrušený token vrací
stejnou stránku 404 — rozlišovat je by z adresy udělalo nástroj na zjišťování, kdo je náš
zákazník.

## Automatické poděkování za 5 ★

Zapíná se per aplikace v konzoli (*Nastavení* → „Automaticky děkovat za 5 ★"). Odesílá se
**bez schválení**, proto jsou podmínky přísné: pět hvězd, výklad typu `PRAISE`, žádné téma
se záporným sentimentem, nízká naléhavost a recenze bez odpovědi ve storu. Text je AI návrh
odpovědi; když chybí, použije se záložní text z nastavení, a bez obojího se neodešle nic.

**Bez výkladu se neodpovídá.** Když AI selže, recenze jde do kanálu s formulářem jako
kterákoli jiná — automatická odpověď na stížnost je horší než žádná.

Odpověď jde do téže fronty jako odpověď ze Slacku (`reply.source = 'AUTO'`), takže se
publikuje stejnou cestou a v inboxu má odznak „auto". V auditu je jako `reply.published`
s `source=AUTO` a **actorem `SYSTEM`** — je to jediná odpověď, kterou nikdo neschválil,
a v logu to musí být poznat. Zprávě v kanálu chybí formulář:
odpověď už je ve frontě a vstup, který za vteřinu přestane dávat smysl, je horší než žádný.

## Slovní shrnutí v rozboru

Úvodní odstavec píše model (`ai.model`, tedy Flash — ne levnější model tagování). Do zprávy
se pustí, jen když projde dvěma kontrolami: každé číslo v textu je z agregátů a každé
citované `reviewId` je z ověřených kandidátských citátů. Co neprojde, se zahodí a odejde
šablonová verze — v logu je pak řádek `Shrnutí obsahuje čísla mimo agregáty`, resp.
`Shrnutí cituje recenze mimo zadání`.

Vypnout jde bez nasazení: *Správa platformy* → `analysis.narrative_enabled`.

## Překlad recenze

Překlad se žádá jen tehdy, když jazyk recenze ze storu (`review.locale`) neodpovídá jazyku
týmu — jinak by se za výstupní tokeny platilo u každé recenze zbytečně. Ukládá se do
`review_insight.translation` a zobrazuje **pod originálem**, ne místo něj.

Hotové výklady bez překladu se nepřepočítávají: překlad dostanou nové recenze. Doplnit ho
zpětně jde jen vynucením nové analýzy (změna `taxonomy_version`), což se kvůli překladu
nevyplatí.
