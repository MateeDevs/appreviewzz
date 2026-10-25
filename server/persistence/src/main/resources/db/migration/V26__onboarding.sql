-- Průchod je osobní; nastavení aplikace i synchronizace jsou sdílené v organizaci.
CREATE TABLE onboarding_progress (
    org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    app_id uuid REFERENCES app(id) ON DELETE SET NULL,
    step text NOT NULL DEFAULT 'app',
    preferences_confirmed boolean NOT NULL DEFAULT false,
    completed boolean NOT NULL DEFAULT false,
    PRIMARY KEY (org_id, user_id)
);
CREATE TABLE onboarding_sync (
    org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    app_id uuid NOT NULL REFERENCES app(id) ON DELETE CASCADE,
    platform varchar(32) NOT NULL,
    status text NOT NULL,
    started_at timestamptz NOT NULL,
    finished_at timestamptz,
    fetched integer,
    message text,
    PRIMARY KEY (org_id, app_id, platform)
);
