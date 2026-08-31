-- F7.2 — konfigurace platformy v databázi.
--
-- Prostředí je správné místo pro to, co aplikace potřebuje, **aby nastartovala**
-- (`DATABASE_*`, `VAULT_KEK_URI`). Není to místo pro to, co potřebuje, **aby fungovala**:
-- u klíče k AI chceme změnu bez restartu, historii a audit. Pořadí přebíjení je
-- databáze > prostředí > výchozí hodnota v kódu (ADR 0018).

-- Hodnota je text, ne jsonb: každý dnešní klíč je skalár, typ a rozsah hlídá katalog
-- v jádře, a text je to, co je v `psql` čitelné bez odvozování.
CREATE TABLE platform_setting (
    key        text        PRIMARY KEY,
    value      text        NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid        REFERENCES app_user (id) ON DELETE SET NULL
);

COMMENT ON TABLE platform_setting IS
    'Přebíjí hodnotu z prostředí. Smazaný řádek = zpátky na prostředí, resp. na výchozí hodnotu.';

-- Oddělená tabulka, ne příznak „tohle je tajné" ve sloupci: jeden `SELECT *` v nesprávném
-- handleru by jinak poslal klíč do JSON odpovědi. Sem se jen zapisuje; ven jde fingerprint.
CREATE TABLE platform_secret (
    key         text        PRIMARY KEY,
    data_key_id uuid        NOT NULL REFERENCES app_data_key (id),
    ciphertext  bytea       NOT NULL,
    -- SHA-256 prefix hodnoty. Slouží k tomu, aby člověk v consoli poznal, jestli je uložený
    -- ten klíč, co si myslí — a aby šlo v auditu zapsat změnu bez zapsání hodnoty.
    fingerprint text        NOT NULL,
    hint        text,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    updated_by  uuid        REFERENCES app_user (id) ON DELETE SET NULL
);

COMMENT ON TABLE platform_secret IS
    'Write-only tajemství platformy pod `app_data_key` (AAD platform:<klíč>). Payload z API nikdy nevyjde.';

-- Vlastní tabulka, protože `audit_log.org_id` je NOT NULL a nullovat ho by znamenalo, že
-- jedna zapomenutá podmínka pustí platformní záznam do auditu klienta.
CREATE TABLE platform_audit_log (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_user_id uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    actor_label   text,
    action        text        NOT NULL,
    target_key    text,
    metadata      jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at    timestamptz NOT NULL DEFAULT now()
);

COMMENT ON COLUMN platform_audit_log.metadata IS
    'U tajemství jen otisk před a po — hodnota se do auditu nezapisuje nikdy.';

CREATE INDEX platform_audit_log_created_idx ON platform_audit_log (created_at DESC);
