# 0015 — Druhý faktor: TOTP v appce, tajemství pod KEKem

- **Stav:** Přijato
- **Datum:** 2026-08-22
- **Kontext fáze:** F5

## Kontext

Do console se přihlašuje heslem a v ní jsou **klíče ke storům klientů**. Ne přímo — vault je
write-only a payload z něj ven nevyjde — ale kdo se dostane do účtu, umí klíč vyměnit,
přesměrovat kanál a odpovídat jménem klienta. Samotné heslo na to je málo, protože nejčastější
cesta dovnitř nevede přes naši aplikaci: je to heslo znovupoužité odjinud.

Zamykání účtu po sérii špatných hesel (F3) a limity požadavků (F5.1) řeší hádání. Neřeší
uniklé heslo, které sedí napoprvé.

Možnosti, které přicházely v úvahu:

| | Proč ne |
|---|---|
| **SMS kód** | provozní závislost navíc (brána, kredit), SIM swap, a platí se za každý pokus |
| **Magic link do e-mailu** | pošta u nás dodnes visí na doplnění SMTP; navíc druhý faktor, který chodí do schránky, není druhý faktor, když je schránka ta samá věc, kterou útočník už má |
| **WebAuthn / passkeys** | správně nejlepší, ale znamená správu více klíčů na účet, recovery flow a UX, které se za jednu fázi nedodělá — a self-host by na to potřeboval HTTPS i na localhostu |
| **TOTP** | nula provozních závislostí, funguje offline, appku už klient nejspíš má |

## Rozhodnutí

**TOTP podle RFC 6238 s výchozími parametry (SHA-1, 6 číslic, krok 30 s), tajemství zašifrované
stejným KEKem jako credentials.**

- **Výchozí parametry schválně.** SHA-256 ani osm číslic autentizační appky spolehlivě nečtou;
  HMAC-SHA1 v roli PRF žádnou známou slabinou netrpí. Odchylka by znamenala „nefunguje mi to"
  na podpoře, ne vyšší bezpečnost.
- **Tolerance ±1 krok** k rozejitým hodinám. Víc by prodlužovalo dobu, po kterou je odposlechnutý
  kód použitelný.
- **Uplatněný krok se ukládá** (`user_totp.last_step`). Bez toho jde zachycený kód použít
  celé jeho třicetisekundové okno ještě jednou.
- **Tajemství je zapečetěné pod vlastním DEK** (`app_data_key`), AAD = `user_id:totp`. Vlastní
  klíč proto, že credential vault stojí celý na klíči per organizace a uživatel žádnou mít
  nemusí — účet si zakládá dřív. Dělit klíč po uživatelích by nic nepřidalo a znamenalo by
  volání do KMS na každé přihlášení.
- **Bez `VAULT_KEK_URI` druhý faktor nejde zapnout** a console to řekne větou. Ukládat seed
  otevřeně by z něj udělalo řádek, který z dumpu rovnou funguje.
- **Mezistav přihlášení je jednorázový token**, ne polovičatá relace. Dokud druhý faktor
  neprojde, žádná session nevznikne — není co ukrást ani čím se prokázat. Platí pět minut,
  špatný kód ho nespotřebuje (jinak by jeden překlep znamenal zadávat heslo znovu) a hádání
  kódu drží limit vázaný na tenhle token.
- **Deset záchranných kódů**, ukázaných jednou, v databázi jen otisk. Jsou jediná cesta zpátky
  po ztrátě telefonu; bez nich by odemčení znamenalo ruční zásah v databázi.
- **Vypnutí chce heslo i platný kód.** Ukradená relace tak druhý faktor nesundá — což je jediný
  důvod, proč to není jen tlačítko.

Zapnutí zůstává **dobrovolné**. Vynutit ho plošně dřív, než ho projde první klient, znamená
akorát zamčené účty a telefonáty; povinnost pro role OWNER/ADMIN je kandidát na F6.

## Důsledky

- Přibývá tabulka `app_data_key` a s ní druhé místo, které se musí objevit v postupu rotace
  klíčů. Kryje to `vault rotate` v CLI (F5.6), které bez `--org` projde organizace i klíč
  uživatelských tajemství.
- **Záloha databáze bez keysetu je po ztrátě KEK nepoužitelná i pro druhý faktor.** Runbook
  obnovy to musí zmiňovat vedle credentials.
- Console dostala závislost `qrcode.react` (MIT). QR se vykresluje v prohlížeči, takže obrázek
  s tajemstvím nikam neputuje.
- Přihlášení má nově dva výsledky (`200` s profilem, `202` s challenge). Kdo si píše vlastního
  klienta nad API, musí `202` obsloužit — proto ten stavový kód, a ne příznak v těle.
