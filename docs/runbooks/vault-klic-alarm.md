# Runbook — alarm na použití vault klíče

Rozhodnutí a proč to tak je: [ADR 0011](../adr/0011-audit-vault-klice.md).
Tenhle dokument se čte, když dorazí e-mail z SNS. Čte se odshora dolů a nic se v něm nedomýšlí.

## Co alarmy hlídají

| Alarm | Kdy se ozve | První hypotéza |
|---|---|---|
| `appreviewzz-<env>-vault-unwrap-volume` | víc než `unwrap_threshold_per_hour` (150) volání `kms:Decrypt` nad vault klíčem za hodinu | rozbitá cache DEK nebo restart smyčka workeru; v horším případě hromadné odemykání credentials |
| `appreviewzz-<env>-vault-foreign-principal` | envelope operaci nad klíčem udělal někdo jiný než IAM uživatel aplikace | ruční práce v konzoli — nebo uniklý access key |
| `appreviewzz-<env>-vault-denied` | KMS odmítlo volání (`AccessDenied` a spol.) | zkažená práva po `apply`; nebo někdo zkouší, kam dosáhne |

Zdroj dat je vlastní CloudTrail (`appreviewzz-<env>-audit`) doručovaný do log group
`/aws/cloudtrail/appreviewzz-<env>`. Aplikace k tomu počítá vlastní metriku
`appreviewzz_vault_kek_unwrap_total` na `/metrics` — **to je nezávislý druhý pohled**:
když CloudTrail ukazuje víc rozbalení než aplikace, klíč používá i někdo jiný než ona.

## 1. Zjisti, kdo a odkud

V konzoli CloudWatch → Logs Insights nad log group `/aws/cloudtrail/appreviewzz-<env>`:

```
fields @timestamp, userIdentity.arn, eventName, sourceIPAddress, errorCode
| filter eventSource = "kms.amazonaws.com"
| sort @timestamp desc
| limit 200
```

Rozpad podle volajícího (na tohle se dívej u alarmu na objem):

```
filter eventSource = "kms.amazonaws.com"
| stats count(*) as volani by userIdentity.arn, eventName, bin(1h)
| sort volani desc
```

Očekávaný stav: jediný `arn:aws:iam::<účet>:user/appreviewzz-<env>-app`, jedna IP adresa
(server s aplikací), jednotky volání za hodinu. Cokoliv jiného je nález.

## 2. Rozhodni podle toho, kdo volal

**Volala aplikace ze své IP a objem sedí na provoz** (přibyli klienti, běžela rotace,
doběhl backfill) — nejde o incident. Zvedni práh podle skutečné metriky:

```bash
tofu apply -var 'unwrap_threshold_per_hour=<nová hodnota>'
```

Novou hodnotu spočítej, nehádej ji — postup je v kapitole „Jak se počítá práh". Změnu zapiš
do tabulky dole.

**Volala aplikace, ale objem nesedí na nic** — podívej se na worker: restart smyčka rozbalí
klíč po každém startu znovu, protože cache DEK žije jen v paměti procesu.

```bash
docker logs --since 2h <kontejner-worker> | grep -i "datový klíč\|KMS\|restart"
```

**Volal někdo jiný** — pokračuj bodem 3. Ruční `aws kms decrypt` z vlastní konzole se sem
počítá taky; než vyhlásíš incident, ověř, že to nebyl někdo z týmu.

## 3. Incident: access key aplikace je venku

Předpoklad nejhoršího: kdo má access key, umí rozbalit každý DEK v databázi. Pořadí kroků
je dané tím, že nejdřív se zavírají dveře, pak se uklízí.

1. **Zneplatni access key.** IAM → Users → `appreviewzz-<env>-app` → *Security credentials* →
   u klíče *Deactivate*. Aplikace tím přestane fungovat — to je záměr.
2. **Vyrob nový** (*Create access key*, use case *Application running outside AWS*), vlož ho
   do Coolify jako `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` a restartuj službu.
   Starý klíč pak **smaž**, ne jen deaktivuj.
3. **Zjisti dosah.** V Logs Insights vyfiltruj volání toho principala a spočítej, kolik
   `Decrypt` proběhlo a odkdy:

   ```
   filter eventSource = "kms.amazonaws.com" and userIdentity.arn != "arn:aws:iam::<účet>:user/appreviewzz-<env>-app"
   | stats count(*), earliest(@timestamp), latest(@timestamp) by userIdentity.arn, sourceIPAddress
   ```

4. **Považuj credentials klientů za kompromitované.** Rozbalený DEK odemyká klíče ke storům.
   Pro každou dotčenou organizaci: klient vygeneruje nové klíče (GP service account, ASC API
   key), nahrají se přes `credential add` a staré se ve storu revokují. Postup je stejný jako
   při onboardingu.
5. **Zapiš incident** do tabulky dole a rozhodni o rotaci KEK — plánovaná rotace v KMS běží
   sama jednou ročně, mimořádná se dělá jen tehdy, když je podezření na kompromitaci samotného
   AWS účtu, ne access keye.

> Rotace DEK organizace existuje v kódu (`CredentialVault.rotateDataKey`), ale nemá zatím
> CLI příkaz — přijde s konzolí (F3). Dnes se rotace dělá výměnou credentials podle kroku 4;
> ta je stejně nutná, protože samotné přešifrování DEK klíče ke storům nezneplatní.

## 4. Alarm na odmítnutá volání

Nejčastěji je to vlastní chyba, ne útok: po `tofu apply` s ořezanou policy nebo po výměně
access keye, který ještě není v Coolify. Ověř, že aplikace žije:

```bash
curl -fsS http://<host>:8081/metrics | grep appreviewzz_vault_kek_failure_total
```

Roste-li `failure_total` (a v logu workeru přibývají `KeyManagementException`), jde o práva —
`/health/ready` to nepozná, ten kouká jen na databázi. Zkontroluj IAM policy
`vault-envelope-only` u uživatele aplikace a key policy klíče proti modulu `vault-kms`.

## Jak se počítá práh

Rozbalený DEK je **jeden na organizaci** a drží se v paměti workeru pět minut. Ingest jedné
appky sáhne na credential jednou za platformu, ale obě sáhnutí jsou v tomtéž okamžiku — jde
tedy o **jedno rozbalení na běh appky**, a víc než dvanáct rozbalení za hodinu na organizaci
legitimní provoz fyzicky neumí.

```
normál za hodinu ≈ organizace × appky na organizaci × (60 / ingest_interval_minutes)
strop cache      = organizace × 12
```

| Provoz | Normál | Strop cache | Práh |
|---|---|---|---|
| 10 klientů, 1 appka, ingest 30 min | 20/h | 120/h | 60 by stačilo |
| 10 klientů, 2 appky, ingest 30 min | 40/h | 120/h | **150** (dnešní výchozí) |
| 10 klientů, 2 appky + ratings (F4) | ~60/h | 120/h | **150** |
| 25 klientů, 2 appky | 100/h | 300/h | 350 |

Práh nad stropem cache je záměr: pod ním se legitimní provoz nikdy neocitne, takže alarm
nefiruje na deploy ani na backfill, zato pořád chytí rozbitou cache, restart smyčku workeru
a skript, který si prochází credentials. Cenou za falešný poplach je totiž to, že se e-mail
přestane číst — a pak proklouzne i ten pravý.

Po měsíci provozu si číslo ověř proti skutečnosti a drž ho zhruba na trojnásobku naměřené
špičky:

```bash
aws cloudwatch get-metric-statistics --namespace appreviewzz/vault --metric-name VaultKeyUnwrap \
  --statistics Sum --period 3600 --start-time <začátek> --end-time <konec> \
  --profile appreviewzz-dev --query 'sort_by(Datapoints,&Sum)[-5:]'
```

**Co tenhle alarm nechytí:** cílenou exfiltraci. Kdo má access key aplikace, potřebuje na
všechny klienty tolik volání `Decrypt`, kolik je organizací — deset volání se v objemu
neschová jen proto, že se v něm neztratí; ono se v něm úplně utopí. Na to je alarm na cizí
principal (práh 1) a rychlá invalidace klíče podle kapitoly 3.

## Ověření, že alarm vůbec funguje

Dělá se po každém nasazení modulu a pak jednou za pololetí. Bez tohohle kroku je alarm
jenom položka v terraformu.

1. **Odběr je potvrzený.** `SubscriptionArn` nesmí být `PendingConfirmation`:

   ```bash
   aws sns list-subscriptions-by-topic --topic-arn <arn z výstupu alarm_topic_arn> --profile appreviewzz-dev
   ```

2. **Alarm má data.** `StateValue` má být `OK`, ne `INSUFFICIENT_DATA`:

   ```bash
   aws cloudwatch describe-alarms --alarm-name-prefix appreviewzz-dev-vault --profile appreviewzz-dev --query 'MetricAlarms[].[AlarmName,StateValue]' --output table
   ```

3. **Zkušební poplach.** Nejlevnější je alarm na cizí principal: rozbal cokoliv vault klíčem
   ze své SSO session (ne klíčem aplikace) a čekej e-mail. Doručení trvá jednotky minut,
   protože CloudTrail událost do CloudWatch Logs doručuje se zpožděním.

   ```bash
   echo -n drill > /tmp/drill.txt
   aws kms encrypt --key-id alias/appreviewzz-dev-vault --plaintext fileb:///tmp/drill.txt --profile appreviewzz-dev --query CiphertextBlob --output text | base64 -d > /tmp/drill.bin
   aws kms decrypt --ciphertext-blob fileb:///tmp/drill.bin --key-id alias/appreviewzz-dev-vault --profile appreviewzz-dev --query Plaintext --output text
   ```

   Přijde-li e-mail, zapiš drill do tabulky. Nepřijde-li do deseti minut, projdi bod 1 a 2 —
   nejčastější příčina je nepotvrzený odběr.

## Historie prahů a poplachů

| Datum | Kdo | Co | Závěr |
|---|---|---|---|
| 2026-08-19 | Tadeáš | výchozí práh 150 volání/h (strop cache pro 10 klientů je 120/h) | čeká na první drill po `apply` |
