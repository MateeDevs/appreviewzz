# Produktový a vizuální směr

Ověřeno 25. 10. 2026. Primárně support a produktové týmy mobilních aplikací; sekundárně agentury s portfoliem klientů. Problém: recenze zůstává ve storu, odpověď vyžaduje přepínání nástrojů a jednotlivé stížnosti zakrývají opakující se problém. Sdělení: **App reviews. Answered. Understood.** Přesný podtitulek jmenuje oba story i Slack/Teams. AI pomáhá napsat odpověď; odpovědnost a schválení má tým.

CTA: „Get started“ → `https://console.appreviewzz.com/registrace`; přihlášení `/login`. Obě cesty ověřeny skutečným browser GET: HTTP 200 a vykreslené formuláře. První kontrola HEAD vracela 404, což neodpovídalo chování navigace; definitivní volba vychází z prohlížeče. Registraci jsme neodesílali a nevytvářeli účet. Sekundární CTA vede na lokální demo, agenturní kontakt na `info@matee.cz` s předmětem. Veškeré veřejné URL na jednom místě `src/data/site.ts`. Žádné ceny, trial, garance či nedoložené reference.

## Omezený průzkum

Prohlédnuté veřejné oficiální prezentace, nikoli přihlášené produkty. Bez převzetí assetů/textů:

- [Linear](https://linear.app/): velká hierarchie a produktový příběh intake → workflow. Závěr: místo kompletního miniaturního dashboardu jeden čitelný úkol a jasné pokračování.
- [Slack](https://slack.com/): konkrétní pracovní situace a viditelná akce. Závěr: ukázat recenzi přímo v kontextu kanálu; loga integrací nejsou reference zákazníků.
- [Appbot](https://appbot.co/): oddělené ratingy, sentiment a odpovídání. Závěr: nezaměňovat hvězdy celého storu s náladou textových recenzí; vysvětlit vzorek analýzy.
- [AppFollow](https://appfollow.io/): cesta od reputace k review managementu a poznatkům. Závěr: propojit analýzu se zdrojovou recenzí a konkrétním vydáním; neexportovat jejich statistiky nebo certifikace do našeho příběhu.
- Historický [web](https://appreviewzz.com/) a [setup](https://appreviewzz.com/get-started/): zachovat jasné Slack/Teams a store integrace, opustit n8n, nepodložené metriky a historickou nabídku.

## Kompozice

Teplý papír #f5f4f0, inkoust #172724, petrolej #146c60. Geist z konzole, self-host, OFL. Vlastní značka tří odezev a bodu. Rozvinutý motiv jemných soustředných kruhů, žádné bitmapové dekorace. Větší marketingová typografie a rytmus otevřené sazby, světlého demo okna a tmavé analytické sekce.

Dvě varianty hero se stejným obsahem: A — redakční rozdělení text / interaktivní recenze; B — centrovaný titulek s širokou ukázkou pod ním. Obě budou skutečně vyrenderovány; výběr a důvody doplníme po kontrole.

Vzorový produkt: fiktivní aplikace Atlas, vydání 3.8, 12.–18. října 2026. Recenze o offline mapách navazuje na téma Offline access a zdrojové recenze. Čísla jsou pouze označená ukázková data, nejsou výsledkem zákazníka. Simulace nedělá síťová volání ani neodesílá recenze.

Technologie ověřena podle [Astro install](https://docs.astro.build/en/install-and-setup/), [static deployment](https://docs.astro.build/en/guides/deploy/) a aktuálního npm registry: Astro 7.4.2, statický build, vlastní CSS/TypeScript, defaultně noindex. Marketingový obsah je v HTML a funguje bez JS.

## Výběr po skutečném renderu

`docs/variants/hero-a.png` a `hero-b.png` jsou rendery stejného obsahu při 1440 × 1000. Vybrána A: návštěvník současně vidí velký nadpis, konkrétní vysvětlení, CTA i celé schválení odpovědi. B má silný centrovaný titulek, ale odsouvá editaci a schválení pod první viewport a připomíná běžnější SaaS kompozici. Alternativní CSS je uložené v `docs/variants/hero-b.css`, není veřejnou routou.

Vědomá iterace po review: zvětšení textů zdrojových recenzí v analytickém demu (12 px místo 10), popisků témat a hodnot; oprava kontrastu sekundárních popisků a závěrečného CTA podle axe. Produktové detaily mají přednost před ozdobami.
