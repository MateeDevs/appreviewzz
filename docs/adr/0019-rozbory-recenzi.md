# 0019 — Rozbory recenzí: čísla počítá databáze, výklad recenze počítá model

- **Stav:** Přijato
- **Datum:** 2026-09-04
- **Kontext fáze:** F8

## Kontext

Doručit recenzi do Slacku umí každý. Otázka, na kterou klient odpověď nemá, zní jinak:
*o čem ty recenze vlastně jsou a co se za poslední týden změnilo.* Dneska si to musí přebrat
sám — projít třicet zpráv v kanálu a udělat si obrázek. Většina to nedělá.

Nabízí se dát celý úkol jazykovému modelu: „shrň mi ty recenze". Vypadá to jako nejrychlejší
cesta a je to past. Souhrn, který napíše model, se nedá zopakovat, nedá se ověřit a nedá se
z něj postavit graf; navíc modely v aritmetice chybují způsobem, který v hotové větě nejde
poznat — „devět recenzí o pádech" vypadá stejně důvěryhodně, ať už jich bylo devět nebo pět.

## Rozhodnutí

**Model odpovídá na otázku „o čem je tahle recenze". Na otázku „kolik jich bylo" odpovídá SQL.**

- Každá recenze dostane při zpracování **strukturovaný výklad**: 1–4 témata z uzavřené
  taxonomie, sentiment per téma, celková nálada, typ a naléhavost. Výstup je JSON podle
  schématu, takže model nemá jak vrátit téma, které neexistuje.
- **Taxonomie je uzavřená a verzovaná.** Otevřené „objevování témat" dává pokaždé jiný seznam
  a nedá se z něj počítat trend. Klient si k ní může přidat vlastní témata (název + popis
  anglicky, protože popis jde doslova do promptu).
- **Podíly, trendy, mediány i pořadí problémů počítá databáze** z uložených výkladů. Týdenní
  rozbor je šablona s doplněnými čísly, ne generovaný text.
- **Citát se ověřuje.** Model smí k tématu přiložit úryvek recenze; ten se před uložením
  porovná jako podřetězec originálu (po normalizaci mezer a diakritiky). Neprojde-li,
  uloží se `null`. Citát se nikdy nevymýšlí.
- **Selhání AI nikdy neblokuje doručení.** Recenze odejde do kanálu bez štítků a dávkový
  job ji zkusí vyložit později. Stejný kontrakt jako u návrhů odpovědí.
- **Výklad patří ke znění recenze**, ne k recenzi: drží si otisk textu a verzi taxonomie.
  Když autor recenzi přepíše nebo se změní taxonomie, výklad se spočítá znovu — jinak by
  v jednom grafu ležela vedle sebe čísla ze dvou různých pravítek.

Model je levnější než u návrhů odpovědí (`ai.analysis_model`, výchozí `gemini-2.5-flash-lite`),
protože strukturovaný výstup je snazší úloha než napsat odpověď zákazníkovi. Provider a klíč
se sdílejí s návrhy; přepnutí modelu je změna jedné hodnoty v platformní správě, bez restartu.

## Důsledky

- Rozbor jde **zopakovat a ověřit**: kdo se zeptá „odkud je těch devět", dostane odkaz do
  inboxu s filtrem tématu a devět recenzí.
- Instalace bez AI se chová jako dřív: žádné štítky, žádný rozbor, a konzole vysvětlí proč.
- Tagování stojí zhruba 0,00005 USD na recenzi — deset tisíc recenzí je půl dolaru. Cena
  je tím pádem argument pro dávkování (patnáct recenzí na volání), ne proti rozborům.
- Recenze bez textu (na Androidu zhruba polovina) se do AI vůbec neposílají: sentiment se
  odvodí z hvězd. Bez toho by v podílech chyběly a čísla by lhala.
- Slovní shrnutí se **nezakazuje navždy** — přibude, ale až s validací: každé číslo ve větě
  musí být z agregátů a každý citovaný odkaz z ověřených citátů, jinak se věta zahodí
  a pošle se šablonová verze.
