-- F3.1 — přihlášení do console.
--
-- Do F2 uživatel existoval jen jako adresát v audit logu; teď se jím někdo přihlašuje.
-- Heslo se ukládá jako argon2id PHC řetězec (`$argon2id$v=19$m=…`), tokeny relací
-- a odkazů jen jako SHA-256 otisk — z ukradeného dumpu se nikdo nepřihlásí.

ALTER TABLE app_user
    ADD COLUMN password_hash       text,
    ADD COLUMN email_verified_at   timestamptz,
    ADD COLUMN last_login_at       timestamptz,
    ADD COLUMN failed_login_count  integer     NOT NULL DEFAULT 0,
    ADD COLUMN locked_until        timestamptz;

COMMENT ON COLUMN app_user.password_hash IS
    'argon2id PHC řetězec; NULL = účet založený pozvánkou, heslo si teprve nastaví';
COMMENT ON COLUMN app_user.locked_until IS
    'Dočasné zamčení po sérii špatných hesel — brzda proti hádání online';

CREATE TABLE user_session (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    -- Jen otisk. Plaintext token zná výhradně prohlížeč v cookie.
    token_hash   bytea       NOT NULL UNIQUE,
    user_agent   text,
    client_ip    text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz NOT NULL,
    revoked_at   timestamptz
);

-- „Odhlásit všude" a výpis aktivních relací; neaktivní řádky index nezajímají.
CREATE INDEX user_session_active_idx ON user_session (user_id) WHERE revoked_at IS NULL;

CREATE TABLE user_token (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    purpose     text        NOT NULL CHECK (purpose IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET')),
    token_hash  bytea       NOT NULL UNIQUE,
    expires_at  timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- Nový odkaz zneplatní ty předchozí; hledá se přesně tahle dvojice.
CREATE INDEX user_token_pending_idx ON user_token (user_id, purpose) WHERE consumed_at IS NULL;
