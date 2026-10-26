# Audit výchozího stavu

Baseline ze skutečného Chromium renderu, syntetické API fixtures: `before/app-{1440,390}.png`, `before/google-{1440,390}.png`, `before/apple-{1440,390}.png`. Nejde o živé store účty.

1. Přidání aplikace zavírá 560px dialog a otevírá jiný; druhá platforma zůstane mimo průchod.
2. Návody v úzkém sloupci, bez přehledu fází a společného návratu. Backdrop může zahodit práci.
3. Copy míchá tykání/vykání, role a zápisová práva; nepravdivý Google IAM návod a neověřený Apple Admin fallback.
4. Google interval může překrýt pomalé validace, callbacky po odchodu upravují starý stav. Provisioning není chráněn souběhem na serveru.
5. Upload neomezuje velikost, chyby čtení ani závody souborů. Starý Key ID může zůstat po výměně souboru.
6. ASC zachovává multi-select, ale retry opakuje i úspěšné položky; zastaralá mapa existujících aplikací může způsobit duplicity.
7. Lookup chrání zobrazený identifikátor, ale pozdní odpověď může přepsat název. Shoda pouze jedné platformy není bezpečně obsloužena.
8. Reporting PATCH neobsahuje povinný název; samotná uložená adresa nedokazuje dostupný export.

Existující API stačí pro identitu, lookup, aplikace, klíče, vazby, app-specific validaci, reporting a první sync. Onboarding již persistuje osobní postup; přidání další aplikace ho nesmí přepsat. Nové obecné wizard API není potřeba. Serverová oprava souběhu provisioningu je nezbytná.
