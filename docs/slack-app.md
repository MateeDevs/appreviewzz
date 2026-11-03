# Slack App — pilotní připojení

Pilot i produkce používají **jednu Slack App `appreviewz`** s OAuth. Klient **nevytváří
bota, nekopíruje token ani nehledá ID veřejného kanálu**. Publikaci do Marketplace tato příprava nezahrnuje.

## 1. Jednorázové nastavení provozovatelem

Potřebuješ Slack workspace pro vývoj appky a veřejné HTTPS API. API a worker musí běžet
nad stejnou databází a se stejným přístupem k vaultu. Samotné API přijme odpověď ze Slacku,
worker ji publikuje do storu.

Pilot poběží na **https://console.staging.appreviewzz.com** — zde jsou konzole i API.
Doména `https://staging.appreviewzz.com` je staging marketingového webu; Slack callbacky
na ni nesměřují. Rozdělení odpovídá [konfiguraci nasazení](../deploy/coolify/README.md).
Společný příjem interakcí má od začátku stálou produkční URL
**https://console.appreviewzz.com/webhooks/slack/interactivity**. Produkční API a worker
proto musí mít nasazený routing a běžet už během stagingového pilotu.

Hotový [manifest společné appky](../deploy/slack/manifest.json) můžeš rovnou vložit
do Slacku. Znovu ho vygeneruješ tímto příkazem:

```bash
node deploy/slack/render-manifest.mjs > /tmp/appreviewz-slack.json
```

1. Na <https://api.slack.com/apps> zvol **Create New App → From a manifest**, vyber svůj
   vývojový workspace a vlož JSON z vygenerovaného souboru.
2. Manifest nastaví bot oprávnění `chat:write`, `chat:write.public`, `channels:read`, OAuth
   callback a interaktivitu. Pro existující appku lze tytéž hodnoty nastavit ručně:
   - Redirect URL pro staging: `https://console.staging.appreviewzz.com/slack/oauth/callback`
   - Redirect URL pro produkci: `https://console.appreviewzz.com/slack/oauth/callback`
   - **Jedna stálá** Interactivity Request URL: `https://console.appreviewzz.com/webhooks/slack/interactivity`
   - App i bot se jmenují `appreviewz`. Pro produkci nezakládej druhou appku.
3. V **Basic Information → App Credentials** zkopíruj Client ID, Client Secret a Signing
   Secret do **Coolify → projekt Appreviewzz → příslušné prostředí → Docker Compose resource
   → Environment Variables**. Client ID vlož jako `SLACK_CLIENT_ID`, Client Secret jako
   `SLACK_CLIENT_SECRET` a Signing Secret jako `SLACK_SIGNING_SECRET`. Stejné tři hodnoty
   ulož u stagingového i produkčního resource. Compose předává signing secret API i workeru;
   OAuth údaje používá API. Přidej i relay adresy z bloku níže. Hodnoty patří do runtime
   prostředí, po uložení se projeví až po novém nasazení / vytvoření kontejnerů.
   Manifest žádné tajemství neobsahuje.
4. V **Manage Distribution → Share Your App with Other Workspaces** dokonči checklist a
   zapni **Activate Public Distribution**. Jde o distribuci pilotu instalačním odkazem;
   do Marketplace nic neposílej. Pro pilot v původním vývojovém workspace tento krok není
   potřeba, pro ostatní workspacy ano.

```dotenv
SLACK_SIGNING_SECRET=...
SLACK_CLIENT_ID=...
SLACK_CLIENT_SECRET=...
APPREVIEWZZ_ENV=staging
PUBLIC_BASE_URL=https://console.staging.appreviewzz.com
CONSOLE_BASE_URL=https://console.staging.appreviewzz.com
SLACK_RELAY_STAGING_BASE_URL=https://console.staging.appreviewzz.com
SLACK_RELAY_PROD_BASE_URL=https://console.appreviewzz.com
```

V produkci nastav `APPREVIEWZZ_ENV=prod` a obě base URL na
`https://console.appreviewzz.com`. Relay cíle zůstávají stejné v obou prostředích.
**Signing secret, Client ID a Client Secret patří stejné appce a jsou společné pro obě
prostředí.** Token workspace se přesto ukládá do odděleného vaultu příslušného prostředí.
Každé prostředí připoj přes jeho vlastní konzoli. Ve stejném Slack workspace zůstává jeden
bot; prostředí se neodvozuje od workspace ani kanálu.

`SLACK_SIGNING_SECRET` a oba `SLACK_RELAY_*_BASE_URL` nastav v **API i workeru**.
Compose soubory je předávají oběma rolím. Bez workeru v prostředí společného příjmu
zůstane předání callbacku čekat ve frontě. Bez workeru cíle se nepublikuje odpověď do storu.
Oba relay cíle jsou pevné HTTPS originy bez cesty; nepřebírají se ze Slack payloadu.

Nový build obsahuje migraci `V28__channel_connectivity_check.sql` pro uložení výsledku
zkoušky; spouští se standardním mechanismem migrací aplikace. Po změně konfigurace
restartuj API a worker. Ověř jejich healthchecky. AI je volitelná;
bez ní jsou recenze doručené s prázdným polem pro odpověď. Klíče ke storu pro odpovídání
jsou samostatné připojení v Appreviewzz.

Oprávnění na historii zpráv ani user tokeny nepotřebujeme. Aktuální nastavení token rotation
v manifestu je vypnuté; implementace neobnovuje rotující OAuth tokeny.

Distribuci odkazem a pravidla správce workspace popisuje
[Slack dokumentace](https://docs.slack.dev/app-management/distribution/).

## Společný příjem callbacků a oddělení prostředí

Slack posílá všechny interakce na produkční URL, včetně stagingového pilotu. Požadavek projde ověřením
originálního Slack podpisu. Tlačítko každé nové recenze má podepsanou hodnotu obsahující
prostředí, organizaci a recenzi. Viditelný štítek ALPHA se pro směrování nepoužívá.
Produkční zprávy se zpracují místně; stagingové se po uložení do fronty předají staging API.
Jde o předání mezi servery přes podepsaný relay, nikoli HTTP přesměrování Slack požadavku.

- **Místní zpráva:** podle `channel + ts` se dohledá v místní DB, porovná se organizace
  a recenze z podepsané hodnoty a odpověď se zařadí do místní publikační fronty.
- **Zpráva z druhého prostředí:** normalizovaná odpověď se uloží do úlohy `slack-relay`.
  Slack dostane potvrzení po uložení do DB, bez čekání na druhé prostředí. Worker ji
  předá na `/webhooks/slack/relay` cílového API s vlastním časově omezeným HMAC podpisem.
  Cíl znovu ověří prostředí a vazbu na zprávu v DB a založí úlohu pro publikaci.
- Cíl relay už nic nepřeposílá. Chybná konfigurace nevytvoří smyčku. Přenos má retry
  a DLQ; opakované doručení nevytvoří další publikaci stejného textu. Do fronty se neukládá
  původní Slack payload s tajným `response_url`, pouze pole potřebná pro odpověď.

Každá stagingová zpráva, přehled, alert, ověřovací zpráva i chyba ve vlákně končí
**oddělovačem a „⚠️ ALPHA · Zpráva z testovacího prostředí staging.“** Označení zůstává
po úpravě zprávy na odpovězenou a je i v mobilním notifikačním textu. Produkce patičku
ALPHA nemá. Lokální vývoj je označený ALPHA s dodatkem lokálního vývoje.
ALPHA určuje prostředí systému. Staging má ve správě platformy výchozí testovací režim
**Odpovědi ukládat pouze u nás**; při něm odpověď do storu neodejde. Po jeho vypnutí se
nové skutečné odpovědi publikují přes nakonfigurovaný store účet. Podrobný průchod pro
Slack i Teams: [Testování odpovědí bez publikace](testing/reply-simulation.md).

OAuth používá dvě návratové URL povolené ve **stejné** appce. Každé prostředí výslovně
posílá vlastní `redirect_uri`; podepsaný `state` je svázaný s prostředím. Stagingový
instalační odkaz proto nelze dokončit v produkční DB se stejným signing secretem.
Po upgradu je třeba staré instalační odkazy bez označení prostředí vygenerovat znovu.

Před nastavením produkční Request URL nasaď tento routing do obou prostředí a spusť jejich
API i workery. Výchozí manifest už používá produkční příjem; při přechodu z pilotu do
produkce se Request URL nemění. Pro vlastní instalaci lze origin příjmu změnit prvním
argumentem generátoru nebo proměnnou `SLACK_INGRESS_BASE_URL` při generování manifestu.

Staré zprávy bez podepsané hodnoty se zpracovávají pouze v prostředí, které callback
přijalo. Neprovádí se odhad podle textu ani přeposílání do obou prostředí. Odinstalování
společné appky ze stejného workspace ovlivní staging i produkci.

## 2. Průchod uživatele

1. V Appreviewzz otevři aplikaci → **Klíče a kanály**, případně Slack v posledním kroku
   onboardingu. Klikni na **Připojit přes Slack**.
2. Ve Slacku vyber workspace a schval přístup. Pokud firma vyžaduje souhlas správce,
   dokonči žádost o schválení a poté připojení znovu spusť.
3. Automaticky se vrátíš na stejné místo. Právě připojený workspace bude vybraný.
4. Vyber kanál podle názvu a klikni na **Připojit a ověřit kanál**. Tlačítko předem říká,
   že odešle zkušební zprávu.
5. V kanálu se objeví „✅ Kanál je připojený“ se jménem aplikace. V konzoli uvidíš úspěch.
   Jazyk se přebírá z aplikace, další přepínače jsou až v úpravě kanálu. Výsledek poslední
   zkoušky zůstává uložený i po obnovení stránky; další neúspěšná zkouška ho přepíše.

Seznam zobrazuje veřejné nearchivované kanály. Ve větším workspace použij hledání mezi
načtenými kanály a **Načíst další kanály**. Pro privátní kanál zvol **Privátní kanál / zadat
ID**, přidej Appreviewzz v kanálu přes Integrace (nebo `/invite @appreviewz`) a vlož jeho ID.

Když ověřovací zpráva selže, nový kanál se neuloží jako aktivní. Oprav přístup a opakuj
stejné tlačítko. Opakované připojení stejného kanálu nevytváří další kanál.
Zrušený OAuth tě vrátí do konzole a dovolí začít znovu. Návrat je součástí podepsaného
jednorázového state; instalační odkaz platí dvě hodiny. Při vypršení session se přihlas,
cíl rozpracovaného připojení zůstává v přihlašovacím odkazu.

### Odpojení workspace

U vybraného Slack workspace klikni na **Odpojit workspace** a potvrď dialog. Uložený
přístup se odebere pro celou organizaci v daném prostředí; appka `appreviewz` zůstane ve
Slacku nainstalovaná. Pokud připojení používá některý kanál, nejprve jej odpoj tlačítkem
**Odpojit** v seznamu kanálů, včetně kanálů ostatních aplikací v organizaci. API používaný
přístup smazat nedovolí. Poté lze začít znovu tlačítkem **Připojit přes Slack**.

## 3. Co otestovat v pilotu

- První připojení appky `appreviewz` přes tlačítko: žádné tokeny ani ruční ID veřejného kanálu, automatický
  návrat, jedna ověřovací zpráva po potvrzení.
- Stejný průchod z onboardingu, i na telefonu. Po návratu je sekce integrací otevřená.
- Zrušení přístupu na stránce Slacku a následný úspěšný pokus.
- Druhý workspace: po OAuth je vybraný nově připojený workspace.
- Privátní kanál bez bota: srozumitelná chyba, žádný nově aktivní kanál; po přidání bota
  stejný pokus projde.
- Oprávnění: po odebrání tokenu se objeví chyba a lze použít **obnovit oprávnění**.
- Nová nebo editovaná recenze: objeví se zpráva s formulářem. Historické recenze pod
  `notify_from` se automaticky neposílají, připojení staré appky kanál nezaplaví.
- Stejný workspace i kanál v obou prostředích: stagingová zpráva má patičku ALPHA,
  produkční ji nemá; kliknutí skončí vždy ve frontě správného prostředí.
- Dočasný výpadek cílového API: callback zůstane jako `slack-relay` ve frontě a worker ho
  po obnově spojení předá. Vyčerpané retry je vidět v `jobs failed` na straně příjmu.
- Odpověď z formuláře: ve stagingovém testovacím režimu se uloží pouze u nás a formulář
  nahradí výslovně označená testovací odpověď s textem a autorem. Vypnutí režimu dovoluje
  nové skutečné publikace; tento test proveď samostatně na určené recenzi.

První ověřovací zpráva potvrzuje přístup do kanálu; neověřuje oprávnění ke storu ani
publikaci odpovědi. Tu je třeba vyzkoušet na skutečné recenzi. Nové recenze vyžadují
běžící worker a zapnutý ingest, případně spusť jednorázový ingest z konzole.

## 4. Diagnostika

| Projev | Co ověřit |
|---|---|
| Tlačítko hlásí, že připojení není nastavené | OAuth údaje, signing secret, PUBLIC_BASE_URL a restart API |
| Slack hlásí redirect mismatch | Přesná Redirect URL v nastavení appky, včetně schématu a domény |
| Kanály nejdou načíst | Scope `channels:read`; po přidání scope workspace připoj znovu |
| Veřejný kanál vrací `not_in_channel` | Scope `chat:write.public`, případně přidej bota do kanálu |
| Privátní kanál zprávu nepřijme | Přidej bota přímo do tohoto kanálu |
| Odeslat nepublikuje odpověď | Společná Interactivity URL, stejný signing secret v obou prostředích, workery, klíč ke storu pro odpovědi |
| Callback do druhého prostředí neodchází | `SLACK_RELAY_*_BASE_URL` v API i workeru, stav úlohy `slack-relay` a DLQ |
| ALPHA nebo podepsané prostředí chybí | `APPREVIEWZZ_ENV` a `SLACK_SIGNING_SECRET` ve workeru, nasazení stejné verze API i workeru |
| Recenze nepřicházejí | Zapnutá aplikace i kanál, přepínač recenzí, watermark, worker a delivery health |
| Zpráva nemá AI návrh | Konfigurace AI v nastavení platformy a logy workeru |

```bash
docker compose exec api appreviewzz jobs failed
docker compose logs --tail 100 api worker
```

Doručení recenzí používá frontu s retry a evidencí chyb. Vzácný pád po přijetí zprávy
Slackem a před uložením jejího ID do databáze může stále způsobit duplicitní zprávu;
rezervace v databázi sama neposkytuje záruku přesně jednoho odeslání.

## 5. Ruční připojení pro self-host a podporu

Původní CLI zůstává funkční:

```bash
docker compose exec api appreviewzz slack connect --org islegrow --token xoxb-...
docker compose exec api appreviewzz slack install-url --org islegrow
docker compose exec api appreviewzz credential list --org islegrow
docker compose exec api appreviewzz channel add --org islegrow --app <APP_ID> \
  --credential <ID_INSTALACE> --slack-channel C0123456789 --locale cs
docker compose exec api appreviewzz channel test --org islegrow --app <APP_ID>
```

`slack connect` ověří bot token přes `auth.test` a uloží ho zašifrovaný do vaultu.
CLI instalační odkaz bez návratové cesty nadále zobrazí jednoduché potvrzení instalace;
kanál pak připojíš v konzoli nebo CLI.
