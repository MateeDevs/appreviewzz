# Sebe-audit podle OWASP ASVS 4.0.3, úroveň 2

> Provedeno v F5 (2026-08-22) nad větví `epic/v2`. Není to certifikace ani pen-test — je to
> **poctivé projití seznamu s odkazy do kódu.** Cena téhle věci je v řádcích „nesplněno":
> ty se jinak nikdy nesepíšou.

Legenda: **✅** splněno · **◐** částečně (napsáno, co chybí) · **❌** nesplněno · **—** netýká se

Souhrn: **z 92 posuzovaných požadavků 70 splněno, 13 částečně, 5 nesplněno, 4 se netýkají.**
Nesplněné a částečné jsou vypsané v [§15](#15-co-z-toho-plyne).

---

## V1 Architektura

| | Požadavek | Stav | Poznámka |
|---|---|---|---|
| 1.1.2 | Threat model | ✅ | [threat-model.md](threat-model.md) |
| 1.1.5 | Hranice důvěry popsané | ✅ | tamtéž, §3 |
| 1.2.1 | Aplikace neběží pod rootem | ✅ | `USER appreviewzz` (uid 10001) v [Dockerfile](../deploy/docker/Dockerfile) |
| 1.2.2 | Komponenty se autentizují navzájem | ✅ | Postgres heslem, KMS přes IAM roli, stores přes vlastní klíče |
| 1.4.1 | Vynucování přístupu na důvěryhodné vrstvě | ✅ | role se ověřuje v use-case, ne v konzoli |
| 1.5.1 | Vstupy a výstupy mají popsaný typ důvěry | ✅ | webhooky a odpovědi storů jako nedůvěryhodné |
| 1.6.1 | Správa klíčů oddělená od aplikace | ✅ | KEK v KMS, aplikace ho nikdy nedrží ([ADR 0005](adr/0005-envelope-encryption.md)) |
| 1.8.2 | Citlivá data označená | ✅ | typ `SecretPayload` v signaturách |
| 1.9.1 | Šifrovaná komunikace mezi komponentami | ◐ | TLS ven ano; API↔Postgres jede po lokální síti Dockeru bez TLS |
| 1.11.2 | Kritické operace nejsou závodivé | ✅ | uplatnění tokenu i záchranného kódu je jedno `UPDATE` s podmínkou |
| 1.14.6 | Žádné nepodporované technologie | ✅ | JDK 21 LTS, Kotlin 2.4, Ktor 3.5 |

## V2 Autentizace

| | Požadavek | Stav | Poznámka |
|---|---|---|---|
| 2.1.1 | Heslo aspoň 12 znaků | ✅ | `AuthPolicy.minPasswordLength = 12` |
| 2.1.2 | Povoleno aspoň 64 znaků | ✅ | strop je 200 (kvůli argon2 jako DoS) |
| 2.1.3 | Bez ořezávání a bez omezení znaků | ✅ | heslo se nijak nenormalizuje |
| 2.1.5–6 | Změna hesla vyžaduje to staré | ✅ | `changePassword` ověřuje současné |
| 2.1.7 | Kontrola proti uniklým heslům | ❌ | neděláme; kandidát je k-anonymita přes HIBP |
| 2.1.9 | Žádná pravidla na složení | ✅ | délka místo tříd znaků, podle NIST |
| 2.2.1 | Brzda proti hádání | ✅ | zamčení po 8 pokusech + limity per adresa i per účet (F5.1) |
| 2.2.2 | Druhý faktor není SMS | ✅ | TOTP ([ADR 0015](adr/0015-druhy-faktor-totp.md)) |
| 2.3.1 | Aktivace účtu bez sdíleného výchozího hesla | ✅ | pozvánka je jednorázový token, heslo si nastaví člověk |
| 2.5.1 | Obnova hesla nepošle heslo | ✅ | posílá se jednorázový odkaz |
| 2.5.4 | Žádné výchozí účty | ✅ | seed CLI nezakládá nic bez zadání |
| 2.5.6 | Obnova neprozradí existenci účtu | ✅ | stejná odpověď v obou případech |
| 2.5.7 | Reset druhého faktoru | ◐ | záchranné kódy ano; když dojdou i ty, je to ruční zásah |
| 2.6.1–3 | Jednorázové kódy jsou jednorázové a náhodné | ✅ | `SecureRandom`, otisk v databázi, uplatnění atomické |
| 2.7.2 | Odkaz z e-mailu má krátkou platnost | ✅ | reset hodinu, ověření adresy tři dny |
| 2.8.1 | TOTP tajemství aspoň 128 bitů | ✅ | 160 bitů |
| 2.8.4–5 | Kód nejde použít dvakrát | ✅ | uplatněný krok se ukládá (`user_totp.last_step`) |
| 2.8.6 | Tajemství uložené šifrovaně | ✅ | vlastní DEK pod týmž KEKem ([ADR 0015](adr/0015-druhy-faktor-totp.md)) |
| 2.10.1–4 | Tajemství služeb nejsou v kódu | ✅ | proměnné prostředí, credentials klientů ve vaultu |

## V3 Správa relací

| | Požadavek | Stav | Poznámka |
|---|---|---|---|
| 3.1.1 | Token nikdy v URL | ✅ | výhradně cookie |
| 3.2.1 | Nová relace při přihlášení | ✅ | token se generuje při každém přihlášení |
| 3.2.2 | Aspoň 64 bitů entropie | ✅ | 256 bitů ze `SecureRandom` |
| 3.2.3 | Token jen v cookie, ne v localStorage | ✅ | `httpOnly` — JavaScript ho nevidí |
| 3.3.1 | Odhlášení ukončí relaci | ✅ | `revoke` na serveru, ne jen smazání cookie |
| 3.3.2 | Časový limit relace | ◐ | absolutní 14 dní ano, **idle timeout ne** — `last_seen_at` se zapisuje, ale nikdo ho nevyhodnocuje |
| 3.3.3 | Možnost zrušit relace po změně hesla | ✅ | reset ruší všechny, změna všechny ostatní |
| 3.4.1–3 | `Secure`, `httpOnly`, `SameSite` | ✅ | `SameSite=Lax`, `Secure` mimo lokální běh |
| 3.4.4 | Cookie s prefixem `__Host-` | ❌ | vědomě: rozbilo by lokální běh na http |
| 3.5.2 | Žádné statické API tokeny | ✅ | konzole jede na relacích |
| 3.7.1 | Ověření relace před citlivou operací | ✅ | `requireSession` na stromě cest |

## V4 Řízení přístupu

| | Požadavek | Stav | Poznámka |
|---|---|---|---|
| 4.1.1 | Vynucení na serveru | ✅ | konzole si nic nehlídá sama |
| 4.1.2 | Nedá se měnit metadata přístupu | ✅ | role se čte z databáze, ne z požadavku |
| 4.1.3 | Nejmenší oprávnění | ✅ | OWNER / ADMIN / MEMBER, kontrola v use-case |
| 4.1.5 | Deny by default | ✅ | podstrom bez session neexistuje, ne že vrátí prázdno |
| 4.2.1 | Ochrana proti IDOR | ✅ | `orgId` jako první parametr každé metody ([ADR 0009](adr/0009-domenove-schema-tenancy.md)), cross-tenant testy |
| 4.2.2 | CSRF | ✅ | double-submit token, nasazený na stromě cest |
| 4.3.1 | Administrace odděleně | — | žádné globální admin rozhraní není |

## V5 Validace, sanitizace a kódování

| | Požadavek | Stav | Poznámka |
|---|---|---|---|
| 5.1.1 | Ochrana proti HTTP parameter pollution | ✅ | Ktor bere pojmenované parametry, ne slévá |
| 5.1.3–4 | Validace vstupů | ✅ | `kotlinx.serialization` na typy, doména na hodnoty (slug, časová zóna, interval) |
| 5.2.4 | Žádné dynamické vyhodnocování kódu | ✅ | nikde `eval` ani reflexe nad vstupem |
| 5.2.5 | Ochrana proti template injection | — | nepoužíváme šablonovací engine |
| 5.3.1 | Kódování na výstupu podle kontextu | ✅ | React escapuje; Slack Block Kit a Adaptive Cards se skládají jako JSON, ne řetězcem |
| 5.3.3 | Ochrana proti XSS | ✅ | žádné `dangerouslySetInnerHTML`, CSP bez `'unsafe-inline'` u skriptů |
| 5.3.4 | Parametrizované dotazy | ✅ | Exposed; v celém repu není řetězcem skládané SQL |
| 5.3.8 | Ochrana proti path traversal | ✅ | statické soubory odmítají `..` a čtou se z classpath |
| 5.5.2 | Bezpečné parsování XML | — | XML nepoužíváme |

## V6 Kryptografie

| | Požadavek | Stav | Poznámka |
|---|---|---|---|
| 6.1.1 | Citlivá data šifrovaná v klidu | ✅ | credentials a TOTP seed |
| 6.2.1 | Ověřené algoritmy | ✅ | AES-256-GCM (Tink), argon2id, HMAC-SHA256 |
| 6.2.2 | Žádné vlastní schéma | ✅ | envelope encryption podle vzoru KMS |
| 6.2.3–4 | Autentizované šifrování s AAD | ✅ | AAD váže ciphertext na řádek |
| 6.2.5–6 | Žádný ECB, nonce se neopakuje | ✅ | GCM, nonce generuje Tink |
| 6.2.8 | Porovnávání v konstantním čase | ✅ | `MessageDigest.isEqual` u CSRF, podpisu Slacku i TOTP |
| 6.3.1–2 | Náhoda z kryptografického zdroje | ✅ | `SecureRandom` všude, kde vzniká tajemství |
| 6.4.1 | Správa klíčů | ✅ | KEK v KMS/keysetu, DEK zabalený v databázi |
| 6.4.2 | Rotace klíčů | ◐ | `rotateDataKey` umí organizace; pro `app_data_key` (TOTP) chybí |

## V7 Chyby a logování

| | Požadavek | Stav | Poznámka |
|---|---|---|---|
| 7.1.1 | Do logu nechodí credentials | ✅ | `SecretPayload` + redakční filtr (F5.4) |
| 7.1.2 | Do logu nechodí citlivé osobní údaje | ◐ | e-mail se do logu dostane přes hlášku náhradního odesílatele |
| 7.1.3 | Logují se bezpečnostní události | ✅ | přihlášení, zamčení účtu, změna hesla, druhý faktor, limity, odmítnuté podpisy |
| 7.1.4 | Log má kontext | ✅ | `requestId` v MDC skrz celý požadavek |
| 7.2.1–2 | Ověření a řízení přístupu se loguje | ✅ | plus `audit_log` na doménové operace |
| 7.3.1 | Ochrana logů proti podvržení | ◐ | JSON encoder escapuje; append-only kopie mimo databázi není |
| 7.4.1 | Chybová hláška neprozradí vnitřek | ✅ | ven jde `requestId` a neutrální kód, detail zůstává v logu |

## V8 Ochrana dat

| | Požadavek | Stav | Poznámka |
|---|---|---|---|
| 8.1.1 | Citlivá data se necachují | ✅ | `Cache-Control: no-store` na `/api` (F5.4) |
| 8.2.1–3 | Klient necachuje a needosílá dál | ✅ | `no-store`, `Referrer-Policy: no-referrer` |
| 8.3.1 | Citlivá data nejdou v URL | ✅ | tokeny z e-mailu jsou výjimka daná tím, že jde o odkaz |
| 8.3.4 | Přehled o tom, kde citlivá data jsou | ✅ | [threat-model.md](threat-model.md) §1 |
| 8.3.7 | Silné šifrování citlivých dat | ✅ | viz V6 |

## V9 Komunikace

| | Požadavek | Stav | Poznámka |
|---|---|---|---|
| 9.1.1 | TLS na všechny spoje ven | ✅ | Let's Encrypt na reverzní proxy, https ke storům i KMS |
| 9.1.2 | Jen doporučené šifry | ✅ | výchozí sada proxy a JDK |
| 9.1.3 | Staré verze TLS vypnuté | ✅ | proxy jede TLS 1.2+ |
| 9.2.1 | Ověřování certifikátů odchozích spojení | ✅ | výchozí truststore JDK, nikde nevypnuto |

## V10 Škodlivý kód

| | Požadavek | Stav | Poznámka |
|---|---|---|---|
| 10.2.1 | Aplikace neposílá data nikam navíc | ✅ | volání ven jsou vyjmenovaná v threat modelu |
| 10.3.2 | Ověřená integrita závislostí | ◐ | konzole má `package-lock.json`; Gradle nemá zapnuté zamykání závislostí ani ověřování otisků |
| 10.3.3 | Sken závislostí | ✅ | Trivy v CI, `exit-code: 1` |

## V11 Business logika

| | Požadavek | Stav | Poznámka |
|---|---|---|---|
| 11.1.1 | Kroky v pořadí | ✅ | druhý faktor jde až po ověření hesla, potvrzení nastavení až po naskenování |
| 11.1.4 | Ochrana proti automatizovanému zneužití | ✅ | limity požadavků (F5.1) |
| 11.1.6 | Ochrana proti závodům | ✅ | atomické `UPDATE` u tokenů, rezervace u přehledu hodnocení |

## V12 Soubory

| | Požadavek | Stav | Poznámka |
|---|---|---|---|
| 12.1.1 | Omezená velikost nahrávaného obsahu | ◐ | klíč ke storu chodí jako JSON tělo; horní mez těla neřešíme, spoléháme na proxy |
| 12.3.1–4 | Cesty ze vstupu | ✅ | uživatelský vstup se nikdy nestává cestou v souborovém systému |
| 12.4.1 | Nahraný obsah mimo webroot | — | nic se neukládá na disk |

## V13 API

| | Požadavek | Stav | Poznámka |
|---|---|---|---|
| 13.1.1 | Stejná ochrana jako u UI | ✅ | konzole a API jsou totéž |
| 13.1.3 | Adresy neprozrazují verzi | ✅ | `/health/live` vrací verzi vědomě, jinde ne |
| 13.2.1 | Povolené jen smysluplné metody | ✅ | routy se registrují jmenovitě |
| 13.2.3 | CSRF u REST | ✅ | double-submit token |
| 13.2.5 | Kontrola `Content-Type` | ✅ | content negotiation odmítne cizí typ |
| 13.4.1 | GraphQL | — | nepoužíváme |

## V14 Konfigurace

| | Požadavek | Stav | Poznámka |
|---|---|---|---|
| 14.1.1 | Sestavení je automatizované a opakovatelné | ✅ | Gradle + Docker + GitHub Actions |
| 14.1.3 | Build selže na zranitelné závislosti | ✅ | Trivy s `exit-code: 1` |
| 14.2.1 | Závislosti aktuální | ✅ | version catalog na jednom místě |
| 14.2.3 | Obsah třetích stran z důvěryhodného zdroje | ✅ | žádné CDN, konzole se servíruje z našeho image |
| 14.3.2 | Vypnutý debug | ✅ | žádný debug endpoint, stacktrace ven nejde |
| 14.3.3 | Neprozrazujeme verze komponent | ◐ | `/health/live` vrací verzi a git SHA; je to vědomé kvůli deploy |
| 14.4.1 | `Content-Type` u odpovědí | ✅ | content negotiation, u statiky podle přípony |
| 14.4.3 | Content-Security-Policy | ✅ | F5.4 |
| 14.4.4 | `X-Content-Type-Options: nosniff` | ✅ | |
| 14.4.5 | HSTS | ✅ | jen na https |
| 14.4.7 | `frame-ancestors` / `X-Frame-Options` | ✅ | obojí |
| 14.5.2 | Ochrana proti cizímu původu | ✅ | žádné CORS — cizí původ se k API nedostane |
| 14.5.3 | Kontrola `Origin` u měnících požadavků | ✅ | přes CSRF token a `SameSite` |

---

## 15. Co z toho plyne

**Nesplněno nebo jen zčásti, s dopadem (6):**

1. **2.1.7** kontrola hesla proti známým únikům — backlog, k-anonymita přes HIBP je pár desítek řádků
2. **3.3.2 (část)** idle timeout relace — `last_seen_at` se zapisuje, ale platnost se řídí jen absolutními 14 dny
3. **3.4.4** cookie s prefixem `__Host-` — rozbilo by lokální běh; řešitelné podle prostředí
4. **6.4.2 (část)** rotace `app_data_key` — chybí protějšek `rotateDataKey` pro uživatelská tajemství
5. **7.3.1 (část)** append-only kopie audit logu mimo hlavní databázi
6. **10.3.2 (část)** Gradle bez zamykání závislostí — `libs.versions.toml` drží verze, ale ne otisky

**Zbylé částečné položky** — nejdůležitější tři:

- **1.9.1** spojení do Postgresu bez TLS. Na jednom stroji v jedné Docker síti; TLS by dávalo smysl až s databází jinde.
- **7.1.2** e-mailová adresa v logu náhradního odesílatele. Zavře to nastavené SMTP.
- **2.5.7** vyčerpané záchranné kódy znamenají ruční odemčení.

Nic z toho není zádrhel pro zveřejnění repa: jsou to buď věci vázané na provoz (SMTP, víc
instancí), nebo vědomé kompromisy vůči lokálnímu vývoji. Pořadí, ve kterém se to má zavírat,
je v [threat-model.md](threat-model.md) §6.
