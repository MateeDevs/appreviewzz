# Threat model

> Stav k F5. Model se přepisuje, kdykoli přibude hranice důvěry — ne jednou za rok.

Cílem není vyjmenovat všechno, co se může pokazit, ale **napsat, co konkrétně chráníme, před
kým, a co jsme se rozhodli nechat být.** Poslední část je stejně důležitá jako ta první:
nevyřčené zbytkové riziko se za pár měsíců čte jako opomenutí.

## 1. Co je tu cenného

Seřazeno podle toho, co by bolelo nejvíc:

| Aktivum | Proč na něm záleží | Kde leží |
|---|---|---|
| **Klíče klientů ke storům** | service account Google Play a `.p8` App Store Connectu umí publikovat odpovědi jménem klienta a číst jeho reporty. Ztráta je incident klienta, ne náš. | `credential.ciphertext`, zašifrované DEK per organizace |
| **Tokeny Slacku a Teams** | umí psát do kanálů klienta | tamtéž |
| **KEK** | odemyká všechno výše najednou | AWS KMS (cloud) / soubor keysetu (self-host) |
| **Hesla a druhý faktor uživatelů** | cesta do konzole, odkud jde vyměnit klíč a přesměrovat kanál | `app_user.password_hash` (argon2id), `user_totp.ciphertext` |
| **Relace konzole** | totéž, bez nutnosti znát heslo | `user_session.token_hash` (SHA-256), plaintext jen v cookie |
| **Recenze a hodnocení** | obchodní data klienta; samostatně nejsou tajná, ale prozrazují, kdo je náš zákazník | `review`, `rating_snapshot` |
| **Audit log** | to jediné, co po incidentu řekne, co se dělo | `audit_log` |

## 2. Kdo je proti nám

| Aktér | Co má k dispozici | Co ho zajímá |
|---|---|---|
| **Anonym z internetu** | veřejné endpointy (`/api/auth/*`, `/webhooks/*`, `/slack/install`, konzole) | dostat se do jakéhokoli účtu, vytáhnout klíče, shodit službu |
| **Přihlášený uživatel cizí organizace** | plné API konzole | data a klíče organizace, do které nepatří |
| **Člen organizace v roli MEMBER** | API konzole ve své organizaci | to, co má vidět jen OWNER (klíče, pozvánky, audit) |
| **Kdo umí číst logy** | výstup kontejneru, agregátor logů | tajemství, která se do nich dostala nedopatřením |
| **Kdo má dump databáze** | záloha, snapshot disku, ukradený `pg_dump` | klíče klientů |
| **Uživatel Slacku či Teams klienta** | může klikat na naše zprávy a karty | publikovat odpověď jménem klienta, dosáhnout na cizí organizaci |
| **Provozovatel hostingu** | root na stroji, přístup k proměnným prostředí | všechno; viz zbytková rizika |

Mimo model: útočník s fyzickým přístupem k HSM v AWS, kompromitace samotného Postgresu
dodavatelským řetězcem, cílený útok na zaměstnance mimo pracovní zařízení.

## 3. Hranice důvěry

```
   prohlížeč ──1── reverzní proxy (TLS) ──2── API ──3── Postgres
                                            │
                Slack / Bot Connector ──4───┤
                                            │
                                     worker ├──5── AWS KMS
                                            ├──6── Google Play / App Store Connect
                                            ├──7── Gemini
                                            └──8── object storage (zálohy)
```

1. **Prohlížeč → proxy.** Nedůvěryhodný vstup, TLS povinné, HSTS.
2. **Proxy → API.** Jediné místo, kde věříme `X-Forwarded-For`, a to jen počtu skoků
   z `TRUSTED_PROXY_HOPS`. Metriky jedou na neveřejném portu 8081.
3. **API → Postgres.** Jedna aplikace, jeden účet, žádný přímý přístup zvenčí.
4. **Slack / Bot Connector → API.** Vstup od cizí služby, ne od člověka. Kdo neprojde
   ověřením podpisu, respektive tokenu, nemá endpoint jak použít.
5. **worker → KMS.** Jediné místo, kde se rozbaluje datový klíč. Objem volání se měří.
6. **worker → stores.** Odchozí; odpovědi jsou nedůvěryhodný vstup (parsujeme HTML storu).
7. **worker → AI.** Odchozí. **Text recenze opouští náš systém** — viz zbytková rizika.
8. **worker → object storage.** Odchozí, zálohy.

## 4. Hrozby a co proti nim stojí

Řazeno podle hranice. „Zbývá" znamená vědomě nedodělané, ne přehlédnuté.

### 4.1 Přihlášení do konzole

| Hrozba | Obrana |
|---|---|
| Hádání hesla | argon2id, zamčení účtu po 8 pokusech na 15 minut ([Authentication.kt]), limit požadavků per adresa **i per účet** (F5.1) |
| Uniklé heslo použité napoprvé | druhý faktor TOTP ([ADR 0015]) — dobrovolný, viz zbytková rizika |
| Krádež session cookie | `httpOnly`, `Secure` mimo lokální běh, `SameSite=Lax`; v databázi jen otisk; změna hesla ruší ostatní relace |
| CSRF | double-submit token v hlavičce, nasazený **na stromě cest**, ne v handlerech — nová sekce API ho nemůže vynechat |
| Zjištění, které e-maily u nás jsou | stejná odpověď i stejná doba běhu pro známý i neznámý účet (počítá se dummy hash) |
| Přehrání kódu z autentizační appky | uplatněný časový krok se ukládá; kód platný ještě 25 sekund už podruhé neprojde |
| Uhádnutí kódu druhého faktoru | challenge platí 5 minut a limit je vázaný na ni, ne na adresu |
| XSS v konzoli | React escapuje, žádné `dangerouslySetInnerHTML`; CSP bez `'unsafe-inline'` u skriptů (F5.4) |
| Clickjacking | `frame-ancestors 'none'` + `X-Frame-Options: DENY` |

### 4.2 Cross-tenant

| Hrozba | Obrana |
|---|---|
| Dosáhnout na data cizí organizace | **každá metoda repozitáře, která čte data organizace, bere `orgId` jako první parametr** ([ADR 0009]); výjimky jsou vyjmenované a okomentované |
| Zjistit hádáním adres, kdo je náš zákazník | nečlen dostane `404`, ne `403` |
| Podstrčit ciphertext z jiné organizace | AAD `org_id:credential_id:type` — ciphertext je kryptograficky svázaný s řádkem |
| Eskalace uvnitř organizace (MEMBER → OWNER) | role se ověřuje v use-case, ne v UI; testy na to jsou |

### 4.3 Webhooky

| Hrozba | Obrana |
|---|---|
| Podvržený požadavek „ze Slacku" | HMAC nad **syrovým tělem** před jakýmkoli parsováním, časově konstantní porovnání |
| Podvržená aktivita „z Teams" | JWT proti JWKS Bot Connectoru: vydavatel, `aud` proti naší registraci, endorsement kanálu, a `serviceUrl` z tokenu proti tomu v těle |
| Přehrání zachyceného požadavku | tolerance stáří 5 minut **plus** paměť už viděných podpisů a `id` aktivit (F5.2) |
| Odpověď podstrčená do cizí organizace | recenze se dohledává v **naší** databázi podle konverzace a zprávy, ne z payloadu tlačítka |
| Zahlcení ověřování podpisů | limit požadavků sedí **před** kryptografií |
| Instalace Slacku jménem cizí organizace | podepsaný `state` s expirací, nově jednorázový (F5.2) |

### 4.4 Data v klidu

| Hrozba | Obrana |
|---|---|
| Dump databáze | credentials i TOTP seed zašifrované; KEK není v databázi ani v obrazu kontejneru |
| Ukradená záloha | tentýž ciphertext; keyset se zálohuje **zvlášť** ([runbook]) |
| Tiché rozbalování klíčů | metrika `appreviewzz_vault_kek_unwrap_total` + CloudTrail alarm ([ADR 0011]) |
| Tajemství v logu | typový obal `SecretPayload` s redigovaným `toString()` **a** redakční filtr na výstupu (F5.4) |
| Jednorázové odkazy v logu | tělo e-mailu se bez SMTP vypisuje jen s `MAIL_LOG_LINKS=true` (F5.4) |

### 4.5 Odchozí volání

| Hrozba | Obrana |
|---|---|
| Nedůvěryhodná odpověď storu (scraping HTML) | parsery jsou best-effort a degradují bez pádu; kontraktní testy nad zachycenými odpověďmi |
| Store nebo AI provider je nedostupný | fronta s DLQ; nedoručená zpráva se pozná v přehledu, ne mlčením |
| Injekce z textu recenze do AI promptu | návrh odpovědi je **vždy jen draft** — publikuje ho člověk kliknutím |

## 5. Zbytková rizika

Věci, o kterých víme a rozhodli jsme se s nimi zatím žít. Každá má napsáno, co by ji zavřelo.

1. **Text recenze odchází do Gemini.** Recenze jsou veřejné, takže únik dat to není, ale je to
   třetí strana v cestě. Zavře to buď `AI_PROVIDER=none`, nebo model běžící u nás.
2. **Druhý faktor je dobrovolný.** Účet OWNERa bez něj chrání jen heslo. Vynutit ho plošně
   dřív, než jím projde první klient, by znamenalo zamčené účty a telefonáty — kandidát na F6.
3. **Limity požadavků a ochrana proti přehrání platí per instance procesu.** S jedním API
   kontejnerem je to totéž jako per deployment; při víc replikách by je musela řešit proxy
   nebo sdílený stav. Restart okno vynuluje.
4. **Provozovatel hostingu vidí proměnné prostředí.** Přístup ke KEK má tedy fakticky každý,
   kdo má root na stroji nebo přístup do Coolify. Zavře to jen oddělení rolí, na které je
   jednočlenný tým malý — je to důvod, proč je alarm na použití klíče a ne jen jeho ochrana.
5. **Nemáme SSO ani vynucenou rotaci hesel.** Vědomě: pro pár desítek uživatelů je to složitost
   navíc bez odpovídajícího zisku. SSO je v backlogu za v1.
6. **Odpověď publikuje ten, kdo klikne ve Slacku.** Neověřujeme, že člen workspace klienta je
   zároveň někdo, koho známe — kdo je v kanálu, může odpovědět. Je to záměr (přesně tak se
   nástroj používá) a klient si to řídí členstvím v kanálu, ne my.
7. **Audit log jde smazat spolu s databází.** Nemáme append-only kopii mimo hlavní úložiště.
   Denní záloha to zmírňuje, nenahrazuje.

## 6. Co by nás nejdřív dostalo

Kdyby na bezpečnost byl jeden den, tohle je pořadí:

1. Rotace secrets ze staršího n8n řešení (klíče v plaintextu v exportech) — plánováno na F6.
2. Vynutit druhý faktor pro role OWNER a ADMIN.
3. Sdílený stav pro limity, jakmile poběží víc než jedna instance API.
4. Append-only kopie audit logu mimo hlavní databázi.

[Authentication.kt]: ../server/core/src/main/kotlin/cz/matee/appreviewzz/core/usecase/Authentication.kt
[ADR 0009]: adr/0009-domenove-schema-tenancy.md
[ADR 0011]: adr/0011-audit-vault-klice.md
[ADR 0015]: adr/0015-druhy-faktor-totp.md
[runbook]: runbooks/zalohy-a-obnova.md
