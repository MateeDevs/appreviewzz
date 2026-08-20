-- F3.2 — pozvánky do organizace.
--
-- Pozvánka je samostatný záznam, ne rovnou členství: dokud ji člověk nepřijme, nemá do
-- organizace přístup, a přitom je vidět, že na něj někdo čeká. Token jde e-mailem,
-- v databázi je jen jeho otisk — stejně jako u relací a obnovy hesla.

CREATE TABLE org_invitation (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    email         text        NOT NULL CHECK (email = lower(email) AND position('@' IN email) > 1),
    role          text        NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER')),
    -- Kdo zval. Když ho někdo z organizace vyhodí, pozvánka platí dál, proto ON DELETE SET NULL.
    invited_by    uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    token_hash    bytea       NOT NULL UNIQUE,
    expires_at    timestamptz NOT NULL,
    accepted_at   timestamptz,
    revoked_at    timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now()
);

-- Na jednu adresu smí v organizaci čekat nejvýš jedna pozvánka; nová nahradí starou.
CREATE UNIQUE INDEX org_invitation_pending_idx
    ON org_invitation (org_id, email)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;
