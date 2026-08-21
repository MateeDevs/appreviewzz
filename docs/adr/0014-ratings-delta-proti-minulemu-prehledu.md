# 0014 — Hodnocení: delta proti minulému přehledu, ne proti celkovému průměru

- **Stav:** Přijato
- **Datum:** 2026-08-22
- **Kontext fáze:** F4

## Kontext

Dnešní denní přehled ukazuje u každé platformy „⭐ Dnes: 4,5 (▼ −0,3)". Vypadá to jako vývoj
hodnocení, ale není: **`delta = průměr dnešních nových hodnocení − celkový průměr aplikace`**.
Důsledky jsou tři a všechny jsou špatně:

1. Appka, které za den nepřibylo ani jedno hodnocení, hlásí propad o celý průměr (▼ −4,3).
   Novější šablony (Improvio, spy) na to mají záplatu, starší (Matee, obě Teams karty) ne —
   takže dva klienti vidí u téhož jevu jiné číslo.
2. První běh nemá s čím srovnávat. Chybí-li včerejší snapshot, iOS větev prohlásí celý
   kumulativní histogram za „nová hodnocení" (čísla v řádu tisíců), androidí scrape větev
   rovnou spadne na validaci.
3. Vynechaný den má stejný efekt jako první běh, protože se hledá výhradně řádek z včerejška.

K tomu se v produkci ukládají do databáze **jen `ios` řádky** — androidí data jdou cestou,
která databázi vůbec nepoužívá, takže přírůstek Androidu nejde spočítat ani zpětně.

## Rozhodnutí

**Jedna šablona pro obě platformy i oba kanály; delta je rozdíl celkových průměrů proti
poslednímu předchozímu snapshotu.**

- `delta = celkový průměr teď − celkový průměr v minulém přehledu`. Je to číslo, které z toho
  lidi stejně vyčíst chtějí.
- Srovnává se s **posledním starším snapshotem**, ne s „včerejškem". Vynechaný den (výpadek,
  restart, nová appka) tak nic nerozbije.
- **První běh se pozná a řekne se to větou.** Žádná šipka, žádná nula — obojí by lhalo.
- „Nová hodnocení" se počítají z rozdílu histogramů, a když ho zdroj nedává, z rozdílu počtů.
  Záporné rozdíly se ořezávají na nulu: store hodnocení maže a „−3 nové pětky" nikomu nic
  neřeknou.
- Snapshot se ukládá **pro obě platformy**, po storefrontech i jako globální agregát
  (vážený počtem hodnocení, ne průměr průměrů).
- Do zprávy se nese **datum, ke kterému data platí**. Play Console export je den až dva pozadu
  a dnešní přehled to zamlčuje.

## Důsledky

- Čísla po migraci **nebudou souhlasit s dnešními** a je to záměr. V runbooku migrace to musí
  být napsané, ať klienta nepřekvapí, že „delta je najednou jiná".
- Seznam iOS storefrontů zůstává tentýž jako dnes (20 zemí), aby po migraci neskočil aspoň
  celkový průměr. Změna seznamu je pak vědomé rozhodnutí, ne vedlejší efekt přepisu.
- Přibývá řádků v `rating_snapshot` (iOS 21 řádků denně na appku místo jednoho). Je to
  jednotky megabajtů ročně na klienta a rozpad, který dnes nenávratně mizí v sumě.
- Doručení je idempotentní přes rezervaci v `ratings_digest`: opakovaný běh jobu nepošle druhý
  přehled — ten by navíc ukázal nulovou deltu, protože srovnávací snapshot už je z dneška.
