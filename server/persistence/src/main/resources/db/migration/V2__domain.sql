-- F1: doménové schéma (plán §5.2). Enumy jsou text + CHECK — přidat hodnotu je pak
-- obyčejná migrace, ne ALTER TYPE se zámkem nad celou databází.
--
-- Tenancy: org_id je i na tabulkách, které by ho dokázaly odvodit joinem (review, reply…).
-- Repozitáře díky tomu filtrují jedním WHERE bez joinu a cross-tenant test má co hlídat.

CREATE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------- tenant a lidé

CREATE TABLE organization (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name       text        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    slug       text        NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,62}$'),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TRIGGER organization_updated_at BEFORE UPDATE ON organization
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Přihlašovací sloupce (heslo, verifikace, TOTP) doplní F3 — tady jde jen o identitu,
-- na kterou se odkazuje audit log a autorství odpovědí.
CREATE TABLE app_user (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    email        text        NOT NULL UNIQUE CHECK (email = lower(email) AND position('@' IN email) > 1),
    display_name text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TRIGGER app_user_updated_at BEFORE UPDATE ON app_user
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE org_member (
    org_id     uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role       text        NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER')),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (org_id, user_id)
);

CREATE INDEX org_member_user_idx ON org_member (user_id);

-- ---------------------------------------------------------------- vault (§5.3)

-- Wrapped DEK per organizace. Rozbalený klíč nikdy nesmí opustit paměť workeru.
CREATE TABLE org_data_key (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    kek_uri     text        NOT NULL,
    wrapped_dek bytea       NOT NULL,
    active      boolean     NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    retired_at  timestamptz,
    CHECK (active OR retired_at IS NOT NULL)
);

-- Aktivní DEK je vždy nejvýš jeden; při rotaci se starý nejdřív zneaktivní.
CREATE UNIQUE INDEX org_data_key_active_uq ON org_data_key (org_id) WHERE active;

CREATE TABLE credential (
    id                uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id            uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    type              text        NOT NULL CHECK (type IN (
                          'GP_SERVICE_ACCOUNT', 'ASC_API_KEY', 'SLACK_INSTALL', 'TEAMS_BOT_REF')),
    label             text        NOT NULL,
    data_key_id       uuid        NOT NULL REFERENCES org_data_key (id),
    ciphertext        bytea       NOT NULL,
    -- Co smí ven do API: otisk (SHA-256 prefix) a pár neutrálních identifikátorů
    -- (issuer id, client_email…). Nikdy ne payload.
    fingerprint       text        NOT NULL,
    hint              text,
    validation_status text        NOT NULL DEFAULT 'UNKNOWN'
                          CHECK (validation_status IN ('UNKNOWN', 'VALID', 'INVALID')),
    validation_error  text,
    validated_at      timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX credential_org_type_idx ON credential (org_id, type);

CREATE TRIGGER credential_updated_at BEFORE UPDATE ON credential
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------- aplikace

CREATE TABLE app (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id           uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    name             text        NOT NULL,
    gp_package_name  text,
    asc_app_id       text,
    locale           text        NOT NULL DEFAULT 'cs' CHECK (locale IN ('cs', 'en')),
    timezone         text        NOT NULL DEFAULT 'Europe/Prague',
    -- Watermark: recenze starší než tohle se uloží, ale neposílají se notifikace.
    -- Bez něj by připojení staré appky zaplavilo kanál (a migrace z n8n duplikovala).
    notify_from      timestamptz,
    ai_instructions  text,
    ingest_interval_minutes integer NOT NULL DEFAULT 30 CHECK (ingest_interval_minutes BETWEEN 5 AND 1440),
    daily_digest_at  time        NOT NULL DEFAULT '08:30',
    enabled          boolean     NOT NULL DEFAULT true,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CHECK (gp_package_name IS NOT NULL OR asc_app_id IS NOT NULL)
);

-- Unikátnost je per organizace, ne globální: jinak by si org zabráním balíčku mohla
-- zablokovat cizí onboarding. Přístup ke storu stejně hlídají credentials.
CREATE UNIQUE INDEX app_org_gp_package_uq ON app (org_id, gp_package_name) WHERE gp_package_name IS NOT NULL;
CREATE UNIQUE INDEX app_org_asc_id_uq ON app (org_id, asc_app_id) WHERE asc_app_id IS NOT NULL;
CREATE INDEX app_org_idx ON app (org_id);

CREATE TRIGGER app_updated_at BEFORE UPDATE ON app
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE app_credential (
    app_id        uuid NOT NULL REFERENCES app (id) ON DELETE CASCADE,
    credential_id uuid NOT NULL REFERENCES credential (id) ON DELETE CASCADE,
    purpose       text NOT NULL CHECK (purpose IN ('REVIEWS', 'REPLIES', 'RATINGS')),
    PRIMARY KEY (app_id, credential_id, purpose)
);

CREATE INDEX app_credential_credential_idx ON app_credential (credential_id);

CREATE TABLE channel (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id           uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    app_id           uuid        NOT NULL REFERENCES app (id) ON DELETE CASCADE,
    type             text        NOT NULL CHECK (type IN ('SLACK', 'TEAMS')),
    credential_id    uuid        REFERENCES credential (id),
    target_ref       text        NOT NULL,
    target_label     text,
    locale           text        NOT NULL DEFAULT 'cs' CHECK (locale IN ('cs', 'en')),
    deliver_reviews  boolean     NOT NULL DEFAULT true,
    deliver_ratings  boolean     NOT NULL DEFAULT true,
    enabled          boolean     NOT NULL DEFAULT true,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (app_id, type, target_ref)
);

CREATE INDEX channel_org_idx ON channel (org_id);

CREATE TRIGGER channel_updated_at BEFORE UPDATE ON channel
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------- recenze

CREATE TABLE review (
    id                      uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                  uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    app_id                  uuid        NOT NULL REFERENCES app (id) ON DELETE CASCADE,
    platform                text        NOT NULL CHECK (platform IN ('ANDROID', 'IOS')),
    store_review_id         text        NOT NULL,
    author_name             text,
    star_rating             smallint    NOT NULL CHECK (star_rating BETWEEN 1 AND 5),
    title                   text,
    body                    text,
    locale                  text,
    territory               text,
    app_version             text,
    device                  text,
    submitted_at            timestamptz NOT NULL,
    store_updated_at        timestamptz,
    -- Otisk textu + hvězd; obě platformy umí recenzi editovat a dnešní n8n dedup to ignoruje.
    content_hash            text        NOT NULL,
    developer_response_body text,
    developer_response_at   timestamptz,
    -- UPDATED = autor recenzi po doručení přepsal. Je to notifikovatelný stav: tým chce vidět,
    -- že se z trojky stala pětka, a případně odpovědět znovu.
    state                   text        NOT NULL DEFAULT 'NEW'
                                CHECK (state IN ('NEW', 'NOTIFIED', 'REPLIED', 'UPDATED', 'IGNORED', 'SUPPRESSED')),
    first_seen_at           timestamptz NOT NULL DEFAULT now(),
    last_seen_at            timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    UNIQUE (app_id, platform, store_review_id)
);

CREATE INDEX review_app_state_idx ON review (app_id, state);
CREATE INDEX review_org_submitted_idx ON review (org_id, submitted_at DESC);

CREATE TRIGGER review_updated_at BEFORE UPDATE ON review
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Historie znění: každá pozorovaná verze textu (zákazník i vývojář) jednou.
CREATE TABLE review_revision (
    id                      uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id               uuid        NOT NULL REFERENCES review (id) ON DELETE CASCADE,
    content_hash            text        NOT NULL,
    star_rating             smallint    NOT NULL CHECK (star_rating BETWEEN 1 AND 5),
    title                   text,
    body                    text,
    app_version             text,
    developer_response_body text,
    observed_at             timestamptz NOT NULL DEFAULT now(),
    UNIQUE (review_id, content_hash)
);

-- Kam už recenze doletěla — kvůli pozdějšímu update zprávy („✅ odpovězeno").
CREATE TABLE review_message (
    id                       uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                   uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    review_id                uuid        NOT NULL REFERENCES review (id) ON DELETE CASCADE,
    channel_id               uuid        NOT NULL REFERENCES channel (id) ON DELETE CASCADE,
    provider_conversation_id text,
    provider_message_id      text,
    status                   text        NOT NULL DEFAULT 'PENDING'
                                 CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    error                    text,
    sent_at                  timestamptz,
    -- Otisk znění, kvůli kterému zpráva vznikla. Editovaná recenze má jiný otisk, takže
    -- pro ni smí vzniknout další zpráva — a zároveň se každé znění nenotifikuje dvakrát.
    content_hash             text        NOT NULL,
    created_at               timestamptz NOT NULL DEFAULT now(),
    updated_at               timestamptz NOT NULL DEFAULT now(),
    UNIQUE (review_id, channel_id, content_hash)
);

CREATE INDEX review_message_org_idx ON review_message (org_id);

CREATE TRIGGER review_message_updated_at BEFORE UPDATE ON review_message
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE reply (
    id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    review_id           uuid        NOT NULL REFERENCES review (id) ON DELETE CASCADE,
    body                text        NOT NULL,
    -- Dvojklik na „Odeslat" nesmí publikovat dvě stejné odpovědi.
    body_hash           text        NOT NULL,
    author_user_id      uuid        REFERENCES app_user (id),
    author_external_id  text,
    author_display_name text,
    source              text        NOT NULL CHECK (source IN ('SLACK', 'TEAMS', 'CONSOLE')),
    status              text        NOT NULL DEFAULT 'PENDING'
                            CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    error               text,
    published_at        timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (review_id, body_hash)
);

CREATE INDEX reply_org_created_idx ON reply (org_id, created_at DESC);

CREATE TRIGGER reply_updated_at BEFORE UPDATE ON reply
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------- hodnocení

CREATE TABLE rating_snapshot (
    id            uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        uuid          NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    app_id        uuid          NOT NULL REFERENCES app (id) ON DELETE CASCADE,
    platform      text          NOT NULL CHECK (platform IN ('ANDROID', 'IOS')),
    snapshot_date date          NOT NULL,
    territory     text          NOT NULL DEFAULT 'GLOBAL',
    average       numeric(4, 3),
    total_count   bigint        CHECK (total_count >= 0),
    -- {"1": 12, …, "5": 340}; některé zdroje histogram nedávají
    histogram     jsonb,
    source        text          NOT NULL
                      CHECK (source IN ('ASC_LISTING', 'ITUNES_LOOKUP', 'GP_CSV', 'GP_SCRAPE')),
    collected_at  timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (app_id, platform, snapshot_date, territory)
);

CREATE INDEX rating_snapshot_app_date_idx ON rating_snapshot (app_id, snapshot_date DESC);

-- ---------------------------------------------------------------- provoz

CREATE TABLE audit_log (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_id         uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    actor_type     text        NOT NULL CHECK (actor_type IN ('USER', 'CHAT', 'SYSTEM')),
    actor_user_id  uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    actor_label    text,
    action         text        NOT NULL,
    target_type    text,
    target_id      text,
    metadata       jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX audit_log_org_created_idx ON audit_log (org_id, created_at DESC);

-- DLQ: co se nepovedlo ani po retry. Viditelné v consoli (F3), řešitelné ručně.
CREATE TABLE failed_job (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          uuid        REFERENCES organization (id) ON DELETE CASCADE,
    task_name       text        NOT NULL,
    task_instance   text        NOT NULL,
    -- Payload je serializovaný vstup tasku tak, jak ho předal scheduler; nečteme ho dotazem,
    -- jen ho ukazujeme člověku, proto text a ne jsonb.
    payload         text,
    error_class     text,
    error_message   text,
    attempts        integer     NOT NULL DEFAULT 1 CHECK (attempts > 0),
    first_failed_at timestamptz NOT NULL DEFAULT now(),
    last_failed_at  timestamptz NOT NULL DEFAULT now(),
    resolved_at     timestamptz
);

-- Nevyřešený záznam je per task+instance jeden; vyřešené zůstávají jako historie.
CREATE UNIQUE INDEX failed_job_open_uq ON failed_job (task_name, task_instance) WHERE resolved_at IS NULL;
CREATE INDEX failed_job_org_idx ON failed_job (org_id) WHERE resolved_at IS NULL;
