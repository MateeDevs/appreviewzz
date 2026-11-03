# Testování odpovědí bez publikace do storů

## Zapnutí a rozsah

Na stagingu otevři uživatelské menu → **Správa platformy → Nastavení → Testování odpovědí**.
Volba **Odpovědi ukládat pouze u nás = ano** je na stagingu výchozí. Správa vyžaduje
superadmina a zapnutý druhý faktor. Nastavení platí pro všechny stagingové organizace a
aplikace, pro Slack, Teams, konzoli i automatická poděkování. V produkci se nenabízí a
server odmítne jeho změnu přes API. Po zapnutí se nečeká na konfigurační cache ani restart.

Odpověď projde skutečným callbackem a databázovou frontou. Uloží se její text, autor,
zdroj a příznak simulace; do auditu přibude `reply.simulated`. Store publisher se nevolá
a není potřeba klíč s právem publikovat. Slack/Teams zprávu upraví na výsledek s textem
„Testovací odpověď – uloženo pouze v Appreviewzz, do storu neodesláno.“ Konzole označuje
historii „test – do storu neodesláno“. Recenze se neoznačí jako skutečně zodpovězená a
simulace nevstupuje do statistik publikovaných odpovědí.

Zachycený testovací režim se uchovává u zprávy v chatu, u úlohy i u odpovědi. Pozdější
vypnutí neodešle čekající testovací úlohu ani odpověď napsanou do staré testovací zprávy.
Tentýž text na stejné recenzi zůstane testem i při opakování. Pro ověření skutečné
publikace je po vypnutí nutný nový text a zpráva, která nevznikla v testovacím režimu,
případně nové odeslání z konzole.

## Příprava pilotu

1. Nasaď API i worker s migrací `V29__reply_simulation.sql`. Pro Slack se společným
   produkčním příjmem musí být routing nasazený i na produkčním API a workeru.
   Obě stagingové role musí mít `APPREVIEWZZ_ENV=staging`; podle prostředí se funkce povoluje.
2. Na stagingu ověř uložené **ano**. Pro nový pilot ponech automatická poděkování nejprve
   vypnutá, aby každá nová recenze měla formulář. Jejich samostatný test je níže.
3. Připoj oddělený testovací Slack kanál a Teams kanál. První ověřovací zpráva potvrzuje
   přístup bota; neověřuje callback odpovědi.
4. Vyber vlastní aplikaci s několika nově doručenými recenzemi. Použij běžný ingest;
   historické recenze pod `notify_from` se do chatu automaticky nedoručují. Testovací
   režim nevytváří syntetické recenze ve storech ani automaticky znovu neposílá staré zprávy.
5. Pro nejsilnější pilotní pojistku použij ke čtení recenzí klíč bez oprávnění publikovat.
   Simulace musí fungovat i bez přiřazení klíče k účelu REPLIES. Zprávy označené jen
   ALPHA samy o sobě testovací režim nepotvrzují; formulář má uvádět **Testovací režim**.

## Doporučené pořadí ručního testu

| Krok | Akce | Správný výsledek |
|---|---|---|
| 1 | Odpověz na novou Android recenzi ve Slacku, do textu dej `PILOT-SLACK-ANDROID` | Formulář se nahradí označenou testovací odpovědí; historie obsahuje zdroj SLACK a text; Play Console nemá novou odpověď. |
| 2 | Totéž pro iOS a `PILOT-SLACK-IOS` | Stejný průchod; v App Store Connect nevznikne nová ani upravená odpověď. |
| 3 | Zopakuj oba store scénáře v Teams s vlastními texty | Výsledek je označený přímo v kartě a historie má zdroj TEAMS. |
| 4 | Odešli test z konzole | Před odesláním vidíš testovací režim; historie po dokončení a refreshi uvádí test, nikoli „publikováno“. |
| 5 | Uprav AI návrh, použij více řádků, emoji a delší text | Uloží se upravený text; platí limit příslušného storu; žádná publikace. |
| 6 | Klikni dvakrát nebo zopakuj odeslání stejného textu | Jediný záznam odpovědi, žádná skutečná publikace. |
| 7 | Otevři stejnou recenzi z druhé organizace | API ji nezpřístupní; callback se váže na původní organizaci a zprávu. |
| 8 | Zapni automatické poděkování a nech doručit vhodnou pětihvězdičkovou pochvalu | Uloží se simulace se zdrojem AUTO; chat ji výslovně označuje jako test. |

Po každém z prvních čtyř kroků ověř **všechny tři vrstvy**: výsledek v chatu, historii a
audit v Appreviewzz, a nezměněnou odpověď ve správě storu. Úspěšné potvrzení callbacku
samotné ještě neznamená dokončené zpracování. Obsah veřejné stránky storu se může
zobrazovat se zpožděním; pro kontrolu použij také Play Console / App Store Connect.

## Odolnost a změna režimu

- **Restart workeru:** zastav stagingový worker, odešli test, znovu jej spusť. Úloha se
  dokončí jako simulace, bez ručního opakování kliknutí.
- **Čekající fronta:** při zapnuté simulaci zastav worker, odešli test, vypni simulaci,
  spusť worker. Výsledek musí zůstat testovací. Poté simulaci zase zapni.
- **Stará testovací zpráva:** nech ji doručit při zapnuté simulaci, vypni režim a odpověz
  do ní. Zůstane simulací díky příznaku uloženému u původní zprávy. Znovu zapni režim.
- **Rozpracovaný formulář v konzoli:** otevři jej při zapnuté simulaci, v jiné záložce
  režim vypni a v původní klikni na **Uložit testovací odpověď**. API musí požadavek
  odmítnout s výzvou k obnovení stránky; staré tlačítko nesmí způsobit skutečné odeslání.
- **Výpadek Slack relay:** znepřístupni stagingový callback cíl, odešli odpověď do testovací
  Slack zprávy a obnov spojení. Produkční příjem odpověď uchová a worker ji předá stagingu;
  výsledek zůstane testem. Tento zásah prováděj jen v určeném testovacím okně.
- **Chyba aktualizace chatu:** znepřístupni bota k testovací zprávě. Uložení simulace se
  nesmí změnit na skutečnou publikaci; chybějící úprava zprávy se dohledá v logu.

Zapnutí režimu nezruší HTTP požadavek, který už starý nebo běžící worker odeslal do storu.
Před prvním pilotem počkej na dokončení nasazení obou rolí. Při přechodu z aktivního
skutečného publikování pozastav příjem odpovědí a nech doběhnout právě zpracovávané úlohy.

## Automatické ověření a hranice simulace

Lokální testy ověřují společný publikační use-case pro Android/iOS a všechny čtyři zdroje,
označení Slack/Teams výsledku, podepsané callbacky, trvalou frontu po spuštění workeru,
zachování příznaku po změně režimu, deduplikaci, oddělení organizací, oprávnění správce a
produkční zákaz nastavení. Store a chat adaptéry jsou v těchto testech dvojníci; skutečné
callbacky a přístupy v pilotu ověřuje ruční průchod výše.

Simulace nepotvrzuje právo publikovat do storu, chování jeho moderace, kvóty, chyby ani
dobu zobrazení odpovědi. To vyžaduje jeden samostatně plánovaný skutečný test s vhodným
textem a recenzí. Přepnutím režimu se žádné dřívější testovací odpovědi hromadně neodešlou.

Nové úlohy se jmenují `process-reply`, aby je nepřevzal starý worker, který simulaci nezná.
Nový worker nadále zná i staré `publish-reply`. Terminální simulace má kvůli kompatibilitě
se staršími čtenáři `status=PUBLISHED`, ale vždy `simulated=true` a `publishedAt=null`;
API a UI ji od skutečné publikace rozlišují příznakem. Výsledky simulací se nikdy nemají
vyhodnocovat jen podle samotného `status`.
