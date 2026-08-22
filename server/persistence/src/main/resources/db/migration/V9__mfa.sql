-- F5.3 — druhý faktor přihlášení do console.
--
-- TOTP tajemství je plnohodnotný credential: kdo ho má, umí vyrobit platný kód navěky.
-- Proto se ukládá **zašifrované**, stejně jako klíče ke storům — z dumpu databáze bez
-- přístupu ke správci klíčů je sloupec bezcenný.
--
-- Datový klíč je vlastní, ne ten organizační: uživatel si zakládá účet dřív, než jakákoli
-- organizace existuje, takže na její klíč se navěsit nedá.

CREATE TABLE app_data_key (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    kek_uri     text        NOT NULL,
    wrapped_dek bytea       NOT NULL,
    active      boolean     NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    retired_at  timestamptz,
    CHECK (active OR retired_at IS NOT NULL)
);

COMMENT ON TABLE app_data_key IS
    'DEK pro tajemství vázaná na uživatele (TOTP), ne na organizaci. Vzniká líně při prvním použití.';

-- Aktivní klíč je vždycky nejvýš jeden; při rotaci se starý nejdřív zneaktivní.
CREATE UNIQUE INDEX app_data_key_active_uq ON app_data_key (active) WHERE active;

CREATE TABLE user_totp (
    user_id      uuid        PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    data_key_id  uuid        NOT NULL REFERENCES app_data_key (id),
    ciphertext   bytea       NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    confirmed_at timestamptz,
    -- Poslední uplatněný časový krok. Bez něj by odposlechnutý kód šel do konce svého
    -- třicetisekundového okna použít podruhé.
    last_step    bigint
);

COMMENT ON COLUMN user_totp.confirmed_at IS
    'NULL = rozdělané nastavení; přihlášení takový záznam neovlivňuje';

CREATE TABLE user_recovery_code (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    -- Jen otisk, stejně jako u tokenů z e-mailu. Kód má dost entropie, aby se nedal hádat.
    code_hash  bytea       NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    used_at    timestamptz
);

-- Uplatnění kódu hledá přesně tuhle dvojici a jen mezi nepoužitými.
CREATE UNIQUE INDEX user_recovery_code_unused_uq
    ON user_recovery_code (user_id, code_hash) WHERE used_at IS NULL;

-- Rozdělané přihlášení čekající na kód z appky. Vlastní hodnota v CHECKu, ne nová tabulka:
-- chová se přesně jako ostatní jednorázové tokeny, jen nechodí e-mailem.
ALTER TABLE user_token DROP CONSTRAINT user_token_purpose_check;
ALTER TABLE user_token ADD CONSTRAINT user_token_purpose_check
    CHECK (purpose IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET', 'MFA_CHALLENGE'));
