# Bezpečnostní politika

## Hlášení zranitelností

Nálezy posílejte na **security@matee.cz**, ne přes veřejné issue. Ozveme se do **72 hodin**
a budeme vás informovat o postupu opravy. Pokud chcete, uvedeme vás v changelogu.

Do hlášení pomáhá: verze / commit, kroky k reprodukci, dopad a případný PoC.

## Rozsah

Zajímá nás cokoli, co vede k:

- únikům klientských credentials (Google Play service account, App Store Connect klíč, tokeny
  Slacku a Teams),
- přístupu na data jiné organizace (cross-tenant),
- obejití verifikace webhooků (Slack signing secret, Bot Framework auth) nebo replay útoku,
- vzdálenému spuštění kódu, SQL injection, eskalaci oprávnění v konzoli.

Mimo rozsah: zjištění ze skenerů bez doložitelného dopadu, chybějící hlavičky bez exploitu,
DoS hrubou silou, social engineering.

## Jak s klíči zacházíme

Credentials klientů jsou šifrované envelope encryption s DEK per organizaci a AAD vázaným na
`org_id:credential_id:type`; dešifrují se až v okamžiku použití. API je nikdy nevrací
(write-only, ven jde jen fingerprint), do logů ani do error trackingu se nedostanou.
Viz [ADR 0005](docs/adr/0005-envelope-encryption.md).

Self-host: KEK zůstává ve vaší infrastruktuře. **Záloha keysetu je stejně kritická jako záloha
databáze** — bez něj jsou uložené credentials nenávratně ztracené.

## Podporované verze

Do prvního stabilního release (v1) opravujeme jen `main`.
