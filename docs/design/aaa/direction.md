# Směr a produktové reference

24. 10. 2026. Reference jsou z veřejných oficiálních stránek a jejich obrázků. Nejde o přihlášený audit placených produktů.

| Zdroj a zhlédnutý obrázek | Ověřené pozorování | Vlastní závěr pro appreviewzz |
| --- | --- | --- |
| [AppFollow Home Hub](https://support.appfollow.io/hc/en-us/articles/4416118653585-Home-Hub), [oficiální screenshot](https://support.appfollow.io/hc/article_attachments/48603908683409) | Kontext aplikace/období/trhu nahoře, oddělený reply effect, fronta, AI souhrn a porovnání období. | Neopakovat stejně silné karty. Začít čekající prací a konkrétní naléhavou aplikací. |
| [Appbot sentiment](https://appbot.co/features/sentiment-analysis-tools/), [oficiální grafická ukázka](https://appbot.co/images/marketing/app-review-tools-sentiment-graph-799d9e10.svg), homepage Ask Appbot | Oficiální ukázka sentimentu zobrazuje změny po verzích; homepage ilustruje oddělený rating, témata a sentiment. Jsou to marketingové ilustrace, nikoli důkaz interakce živého produktu. | Témata a dopad verzí musí mít cestu na důkazy. Nepřidávat nepodporovaný AI chat ani sentiment score. |
| [Appfigures](https://webflow-prod.appfigures.com/), [oficiální ukázka Reviews](https://cdn.prod.website-files.com/6898f58187470a67d09f0d87/68cc946ed9e7ca7e5ce00fb8_1x-hp-reviews.webp) | Current Rating a Total Reviews mají různé hodnoty a vzorky; graf uvádí rozsah posledních 12 měsíců. Starší appfigures.com stránky vracely při přímém načtení 403. | Rozlišovat store rating a hvězdy recenzí; vždy popsat období a základ procent. |
| [Linear Inbox](https://linear.app/docs/inbox), oficiální dark screenshot | Priority/Other, jednoznačně vybraný kontext, stručné řádky. Dokumentace potvrzuje j/k a pohyb i v detailu. | Seznam recenzí musí zůstat rychle skenovatelný. Navigace klávesnicí nesmí zasahovat do editoru. |
| [Intercom Inbox](https://www.intercom.com/helpdesk/inbox), oficiální úvodní screenshot | Složky, seznam, konverzace a kontext jsou samostatné sloupce; editor v návaznosti na obsah. | Dělený inbox od 1181 px, na menší ploše samostatný detail. Nezavádět další sloupec bez potřebných dat. |

## Tři varianty, stejný obsah

V `variants/` je šest HTML prototypů a jejich skutečné rendery při 1440 × 1000. Všechny používají stejné označené syntetické údaje: 52 čekajících, 5 naléhavých, Atlas/Orbit/Kompas, stejnou recenzi a koncept.

- **Signál:** inkoustový boční rám, dominantní souhrn, portfolio v souvislých řádcích, seznam/detail 340 px + zbytek. Nejlepší orientace a prostor pro dlouhé texty.
- **Analytická:** úzká navigační lišta, chladná plocha, kompaktní metriky a rovnoměrně dělený inbox. Slabší popisy navigace a příliš velký podíl seznamu na úkor odpovědi.
- **Tmavá:** horní navigace, otevřená velká sazba souhrnu a široký seznam. Charakter má, ale velké číslo vytlačuje pracovní obsah a horní navigace hůře odděluje správu.

Vybrán **Signál**. Tmavý režim je plnohodnotná alternativa stejného pracovního systému, nikoli samostatný layout. Vlastní značka tří odezev a bodu je v SVG; soustředné kruhy odkazují k signálu. Žádné nesouvisející bitmapy.

Písmo: [Geist](https://vercel.com/font), [oficiální zdroj](https://github.com/vercel/geist-font), OFL. Jedna proměnná rodina 69 760 B, česká diakritika, tabulární číslice, system fallback. Bez CDN nebo změny CSP.

## Co změnilo vizuální review

- Mobilní filtry inboxu původně zabíraly příliš mnoho výšky. Další filtry jsou nyní za rozbalením, základní kontext a stav zůstávají viditelné.
- Detail aplikace měl i po změně shellu stále dlouhý formulář. Nastavení je nyní rozdělené na Základ / Zprávy a čas / Rozbory a historie; URL zachovává výběr.
- První trend míchal sentiment a hvězdy bez jasné druhé osy. Nový graf ukazuje jen náladu v procentech; hvězdy jsou samostatná metrika a sloupec datové alternativy.
- Zeslabení dokončených kroků opacity zhoršovalo kontrast. Zůstalo pouze rozlišení stavovým symbolem a číslem.
