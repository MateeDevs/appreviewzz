# 0008 — Aplikace na Coolify, KEK zůstává v AWS KMS

- **Stav:** Přijato
- **Datum:** 2026-08-18
- **Kontext fáze:** F0
- **Upravuje:** [0005](0005-envelope-encryption.md) v části KEK provider; [0006](0006-jeden-image-dve-role.md) zůstává v platnosti

## Kontext

Původní plán počítal s během na ECS Fargate a RDS. Než přijde první klient, je to ale
nepoměr: dev prostředí na AWS vychází na ~100 USD měsíčně, zatímco zátěž prvních deseti
klientů je necelý jeden job za minutu — objem, který uveze jeden malý server s rezervou.

Zároveň platí, že credential vault je jediná část systému, kde na volbě infrastruktury
opravdu záleží, a KMS klíč nejde přesouvat mezi AWS účty — kdyby vznikl na nesprávném
místě, znamenalo by to přebalit všechny DEK.

## Rozhodnutí

Rozdělit to podle toho, kde na čem záleží:

- **Compute a databáze na Coolify** — aplikace i Postgres běží jako kontejnery na firemním
  VPS spravovaném přes Coolify. Nasazuje se hotový image z GHCR, který staví CI.
- **KEK zůstává v AWS KMS** přesně podle [ADR 0005](0005-envelope-encryption.md). Z AWS se
  používá jen správa klíčů: KMS klíč s automatickou rotací a IAM uživatel, který nad ním
  smí výhradně `GenerateDataKey`, `Decrypt` a `DescribeKey`.

Terraform je proto rozdělený: `modules/vault-kms` je nasazený, `modules/appreviewzz`
(ECS, RDS, ALB) zůstává v repozitáři nepoužitý.

## Důsledky

- **Aplikace se autentizuje statickým IAM klíčem**, protože mimo AWS nemůže použít instance
  roli. Klíč proto neumí nic než envelope operace nad jedním konkrétním klíčem a jde odvolat
  jedním kliknutím. Nevzniká terraformem, aby neležel ve state souboru.
- **Bezpečnostní tvrzení produktu se nemění.** Dump databáze je pořád bezcenný, každé rozbalení
  klíče je vidět v CloudTrailu a DEK per organizace drží tenanty oddělené. Volba KEK je
  nezávislá na tom, kde běží compute.
- **Žádné PITR.** Postgres v kontejneru znamená zálohy přes `pg_dump` a obnovu, kterou je
  potřeba jednou reálně vyzkoušet. Proto se zálohovací drill posouvá z F5 do F1.
- **Jeden hostitel je jeden bod selhání.** Bez redundance; doba obnovy je daná rychlostí
  nasazení image a natažení dumpu.
- **Hostitel je sdílený** s dalšími projekty a jeho control plane je spravovaná služba se
  SSH přístupem ke stroji. Provozní tajemství aplikace jsou tím dostupná i mimo tým produktu.
- **Detekce místo prevence.** Když je hostitel sdílený, těžiště se přesouvá k tomu, aby bylo
  vidět, co se s klíči děje. CloudTrail alarm na neobvyklý objem rozbalování přijde s F1,
  až vault poběží a půjde ho otestovat proti reálnému provozu.

## Zvažované alternativy

- **Plné AWS (ECS + RDS) hned.** Nejlepší izolace a jediné řešení s PITR, ale ~100 USD/měs
  a týden setupu ve fázi, kdy ještě neexistuje doménový model.
- **Railway.** Lepší izolace od ostatních projektů než sdílený hostitel, ale přidává dalšího
  provozovatele do cesty k datům klientů a zálohy má slabší.
- **KEK v lokálním keysetu na stejném stroji.** Ušetří dolar měsíčně a AWS účet, ale zahodí
  auditní stopu i možnost okamžitě odepřít přístup ke klíčům.
