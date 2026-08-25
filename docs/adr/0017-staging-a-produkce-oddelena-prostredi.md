# 0017 — Staging a produkce jako oddělená prostředí, každé s vlastním KEK

- **Stav:** Přijato
- **Datum:** 2026-08-23
- **Kontext fáze:** F6
- **Navazuje na:** [0008](0008-hosting-coolify-kek-v-kms.md), [0016](0016-produkce-zustava-na-coolify.md)

## Kontext

Do F5 stačilo jedno nasazení, které bylo zároveň dev sandboxem, demem a jediným místem, kde
běželo něco skutečného. F6 to rozbíjí: ve chvíli, kdy do vaultu přijdou credentials cizích
klientů, přestává být přijatelné, aby na tomtéž Postgresu někdo zkoušel nový build.

Rozdělit prostředí ale není jen „udělat druhý Coolify projekt". Rozhoduje se u toho, **které
prostředí zdědí dnešní databázi**, a to rozhodnutí je nevratné kvůli tomu, jak se zachází
s klíčem.

`AwsKmsKekProvider.unwrap()` volá KMS `Decrypt` s explicitním `keyId`. Jakmile se
`VAULT_KEK_URI` přepne na jiný klíč, starší zabalené DEK už nikdo neotevře — a `vault rotate`
to nezachrání, protože ten při rotaci nejdřív **načítá** payloady stávajícím providerem.
Migrace „data zůstanou, klíč se vymění" v kódu není a doplňovat ji kvůli jednorázovému
přechodu by znamenalo psát druhou cestu k dešifrování, tedy přesně tu část systému, kde se
druhá cesta nechce.

## Rozhodnutí

**Dvě oddělená prostředí, každé s vlastním KMS klíčem, vlastním IAM uživatelem a vlastním
bucketem na zálohy. Dnešní nasazení se stane stagingem; produkce se staví načisto.**

| | staging | produkce |
|---|---|---|
| Doména | `console.staging.appreviewzz.com` | `console.appreviewzz.com` |
| KEK | `alias/appreviewzz-staging-vault` | `alias/appreviewzz-prod-vault` |
| IAM uživatel | `appreviewzz-staging-app` | `appreviewzz-prod-app` |
| Zálohy | `appreviewzz-staging-backups`, 10 dní | `appreviewzz-prod-backups`, 35 dní |
| Image | `:latest`, nasazuje se každým pushem | `:prod`, povyšuje se ručně |
| Terraform | `envs/staging` | `envs/prod` |

Produkce je do onboardingu prvního klienta prázdná, takže nový klíč nestojí nic. Opačné
pořadí by naopak znamenalo, že produkční databáze natrvalo zdědí klíč, uživatele a bucket
pojmenované `dev` — a přejmenovat by je už nešlo bez přešifrování celého vaultu.

Cena je jedna: **credentials ke storům se do produkce zadávají znovu.** Jsou naše, dají se
vygenerovat nanovo, a je to zároveň nanečisto zkoušený onboarding z F6.

### Co se odděluje a co ne

Odděluje se to, kde by sdílení znamenalo, že stagingový klíč otevře produkční data: **KMS
klíče, IAM uživatelé, buckety, databáze, Slack App**. Neodděluje se **AWS účet** — potřebnou
hranici dělají klíče a uživatelé, ne účet, a druhý účet přidá správu bez užitku, dokud do AWS
pouští jen jeden člověk. Neodděluje se ani **CloudTrail**: první kopie management events je
zdarma jen jednou, produkční trail sbírá celý účet, takže je v něm vidět i použití
stagingového klíče.

### Co staging naopak dělat smí

**Staging odpovídá do reálných storů.** Zvažoval se přepínač, který by publikaci vypnul, ale
odpověď, která se nikdy neodešle, netestuje tu část, kde se chybuje — tedy oprávnění, limity
a formát na straně storu. Hlídá si to tým na testovacích aplikacích.

Pošta jde přes tentýž Resend jako v produkci, jen s vlastním API klíčem a jiným odesílatelem.

## Důsledky

- **Dvě sady secrets a dva access keye.** Rotace se od téhle chvíle dělá dvakrát; stagingový
  klíč v produkční konfiguraci se pozná až tím, že se nerozbalí vault.
- **Produkce běží na tagu `prod`, ne na `latest`.** Tag se přesměruje výhradně ručním
  spuštěním workflow s konkrétním sha, takže restart kontejneru nikdy nepřinese jiný build,
  než jaký byl schválený. Rollback je totéž spuštění se starším sha.
- **Staging se nesmí indexovat.** Servíruje tutéž konzoli na veřejné adrese, takže mimo
  produkci posílá aplikace `X-Robots-Tag: noindex` a zakazující `robots.txt`.
- **Produkční data se nikdy nekopírují do stagingu.** Ani „jen na zkoušku" — staging má svůj
  Slack workspace a svoje testovací aplikace.
- **`envs/dev` se po `destroy` z repozitáře maže.** Tři prostředí, z nichž jedno je mrtvé,
  jsou horší než dvě.
- Limity požadavků a ochrana proti přehrání dál drží stav v paměti procesu
  ([0016](0016-produkce-zustava-na-coolify.md)), takže obě prostředí zůstávají na jedné
  instanci API.

## Zvažované alternativy

- **Nechat dnešní nasazení produkcí a postavit načisto staging.** Levnější o jedno zadávání
  credentials, ale produkce by zdědila jména `dev` napořád (viz výše).
- **Doplnit do vaultu migraci mezi dvěma KEK.** Řeší obecný problém, který máme jednou —
  a přidává druhou cestu k dešifrování do části systému, kde je jedna cesta záměr.
- **Samostatný AWS účet pro staging.** Správná odpověď na „víc lidí v AWS", ne na „dvě
  prostředí". Až bude první důvod, bude i ta hranice.
