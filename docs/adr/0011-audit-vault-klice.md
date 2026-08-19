# 0011 — Použití vault klíče hlídá CloudTrail alarm, ne jen důvěra v IAM

- **Stav:** Přijato
- **Datum:** 2026-08-19
- **Kontext fáze:** F1

## Kontext

Credentials klientů jsou zašifrované DEK per organizaci a ten je zabalený KEK v KMS
([0005](0005-envelope-encryption.md), [0008](0008-hosting-coolify-kek-v-kms.md)). Aplikace běží
mimo AWS, takže se autentizuje **access keyem IAM uživatele** — dlouhodobým tajemstvím
uloženým v Coolify. Kdo ten klíč získá, umí zavolat `Decrypt` úplně stejně jako aplikace;
IAM policy mu v tom nezabrání, protože přesně tohle právo aplikace potřebuje.

Prevence je tedy vyčerpaná a zbývá **detekce**. KMS o použití klíče sám nic neřekne — jediný
záznam „kdo, kdy, kolikrát" je v CloudTrailu. Bez vlastního trailu leží ta informace jen
v 90denní Event history, kterou si musí někdo pamatovat otevřít a nad kterou se nedá postavit
alarm.

Zároveň má systém běžet i u self-hostera, který žádný CloudTrail nemá.

## Rozhodnutí

**Vlastní CloudTrail → CloudWatch Logs → metric filter → alarm do e-mailu, a k tomu vlastní
metrika aplikace.**

- Trail v regionu klíče, management events **včetně čtecích** — `Decrypt` je čtecí operace,
  bez nich by trail o rozbalování klíčů nevěděl. Do S3 jako archiv, do CloudWatch Logs proto,
  že jenom nad nimi jde postavit metric filter.
- Tři alarmy (modul `key-audit`):
  1. **objem** — `Decrypt` nad vault klíčem nad práh za hodinu,
  2. **cizí principal** — envelope operaci udělal někdo jiný než IAM uživatel aplikace,
  3. **odmítnutí** — KMS volání skončilo `AccessDenied`.
- Práh objemu vychází z toho, že vault drží rozbalený DEK v paměti pět minut: legitimní provoz
  neumí překročit 12 volání za hodinu na organizaci. Výchozích **150/h** je proto nad stropem
  cache pro deset klientů — alarm nefiruje na provoz ani na deploy, zato chytí rozbitou cache
  a skript procházející credentials. Vzorec je v runbooku.
- Aplikace počítá volání KEK i sama (`appreviewzz_vault_kek_unwrap_total`) — pro self-host
  je to jediný signál a v našem provozu druhý, nezávislý pohled na totéž.

## Důsledky

- Ke KMS klíči přibyl trvale běžící trail a log group. První kopie management events je
  v CloudTrailu zdarma, platí se uložení: reálně jednotky centů plus $0,30 za tři alarmy.
  Retenci (a tím účet) drží `log_retention_days` a `trail_retention_days`.
- Alarm hlídá **objem, ne obsah** — nepozná jedno cílené rozbalení jednoho credentialu.
  Při deseti klientech stačí útočníkovi s access keyem aplikace deset volání na všechny
  credentials, a to je pod jakýmkoli rozumným prahem. Alarm na objem je proto hlídač zdraví
  systému; proti exfiltraci stojí alarm na cizí principal, invalidace klíče a audit log
  v aplikaci (F3).
- Trail je regionální a záměrně nesleduje data events S3. Kdyby produkce dostala vlastní účet
  (F5), stojí za to vrátit se ke GuardDuty; na jeden účet s jedním klíčem je to dělo na vrabce.
- Alarmy chodí na e-mail přes SNS. Odběr je potřeba potvrdit klikem — dokud se to neudělá,
  alarm se přepne, ale nikdo se to nedozví. Ověření je součástí postupu v
  [runbooku](../runbooks/vault-klic-alarm.md).
- Falešný poplach umí vyvolat i vlastní práce: ruční `aws kms decrypt` z konzole nebo test
  klíče spustí alarm na cizí principal. Je to schválně — vědět o ručním sáhnutí na klíč je
  přesně to, co chceme.

## Zvažované alternativy

- **Nedělat nic a spolehnout se na IAM.** Access key aplikace je dlouhodobé tajemství mimo
  AWS; bez detekce by jeho únik nebyl vidět, dokud by se credentials neobjevily jinde.
- **EventBridge pravidlo nad KMS událostmi.** Reaguje na jednotlivou událost, ale neumí
  „kolikrát za hodinu" — právě objem je přitom to, co odlišuje provoz od exfiltrace.
- **GuardDuty.** Rozpozná víc vzorů a nic se nekonfiguruje, jenže stojí řádově víc než celý
  dnešní AWS účet a jeho nálezy stejně končí ve stejném e-mailu.
- **Kratší TTL cache DEK, aby byl provoz vidět jemněji.** Zhoršilo by poměr signálu k šumu
  i účet za KMS; cache je naopak to, co dělá z běžného provozu plochou čáru.
- **Vlastní grafana/alerting nad metrikou aplikace.** Vidí jen to, co hlásí sama aplikace —
  útočník s access keyem volá KMS mimo ni a v její metrice se neobjeví.
