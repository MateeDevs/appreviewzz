# 0005 — Envelope encryption s DEK per organizaci

- **Stav:** Přijato
- **Datum:** 2026-08-18
- **Kontext fáze:** F0 (implementace F1)

## Kontext

Držíme cizí produkční klíče: Google Play service account JSON, App Store Connect `.p8`,
Slack a Teams tokeny. Únik databáze nesmí znamenat únik klientských storů. Zároveň chceme,
aby self-hoster nemusel mít AWS. Dnešní stav ve starším n8n řešení (klíče v plaintextu ve workflow
JSONech) je přesně to, co nová architektura ruší.

## Rozhodnutí

Tři vrstvy klíčů:

1. **KEK** žije v externím správci a nikdy neopustí jeho hranici. Provider se volí URI:
   `aws-kms://arn:…` (cloud), `local://cesta` (self-host), `vault://transit/klic`.
2. **DEK per organizaci** (AES-256), uložený jen ve wrapped podobě.
3. **Payload** šifrovaný AES-256-GCM, **AAD = `org_id:credential_id:type`**.

Pravidla: dešifruje se pouze ve workeru v okamžiku použití; hodnoty nikdy nejdou do logů,
API odpovědí ani do error trackingu (credentials jsou write-only, ven jde jen fingerprint).

## Důsledky

- Dump databáze bez přístupu ke KEK je bezcenný — to je celý „trust story“ pro README.
- AAD binding znamená, že ciphertext z jedné organizace nelze podstrčit do řádku jiné;
  je to testovatelné tvrzení a bude mít vlastní test.
- Rotace DEK se dotkne jen jedné organizace.
- Provozní cena: KMS volání v horké cestě (mitigace: cache unwrapped DEK v paměti s TTL) a
  **záloha lokálního keysetu je u self-hostu stejně kritická jako záloha databáze** — bez něj
  jsou credentials nenávratně ztracené. Musí být v instalační dokumentaci.

## Zvažované alternativy

- **Jeden globální klíč pro celou instanci.** Jednodušší, ale kompromitace odemyká všechny
  tenanty najednou.
- **Šifrování na úrovni disku/RDS.** Chrání odcizený disk, ne odcizený SQL dump ani SQL injection.
- **Externí secret manager per credential** (Secrets Manager, Vault kv). Drahé při stovkách
  credentials a rozbíjí self-host bez cloudu.
