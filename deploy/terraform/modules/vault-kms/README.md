# vault-kms

Minimální AWS stack pro [credential vault](../../../../docs/adr/0005-envelope-encryption.md):
KEK v KMS a IAM uživatel, který nad ním smí právě a pouze envelope operace.

Používá se, když aplikace běží mimo AWS (Coolify) a z AWS potřebuje jen správu klíčů —
viz [ADR 0008](../../../../docs/adr/0008-hosting-coolify-kek-v-kms.md).

## Co modul vytvoří

| Zdroj | Poznámka |
|---|---|
| `aws_kms_key` | KEK, automatická rotace zapnutá, 30denní okno pro smazání |
| `aws_kms_alias` | `alias/appreviewzz-<env>-vault` |
| `aws_iam_user` | identita aplikace — mimo AWS nejde použít instance role |
| `aws_iam_user_policy` | `GenerateDataKey`, `Decrypt`, `DescribeKey` nad jedním klíčem, nic víc |

## Access key se záměrně negeneruje terraformem

Kdyby `aws_iam_access_key` vznikl v terraformu, ležel by tajný klíč otevřeně ve state
souboru v S3. Proto se vytváří ručně v konzoli a rovnou se vkládá do Coolify —
nikam jinam se nedostane.

IAM → Users → `appreviewzz-dev-app` → *Security credentials* → *Create access key* →
use case *Application running outside AWS*.

## Detekce zneužití

Sama policy nestačí — kdo získá access key aplikace, volá `Decrypt` úplně stejně jako ona.
Použití klíče proto hlídá modul [`key-audit`](../key-audit/README.md): CloudTrail stopa,
alarm na objem rozbalování, na cizí volající a na odmítnutá volání
([ADR 0011](../../../../docs/adr/0011-audit-vault-klice.md)).
