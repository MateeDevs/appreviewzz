-- F0 baseline. Doménové schéma (organization, app, credential, review, …) přijde v F1.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Jediný řádek, který drží metadata instalace. Slouží i jako smoke test migrací.
CREATE TABLE schema_meta (
    singleton      boolean     PRIMARY KEY DEFAULT true CHECK (singleton),
    initialized_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO schema_meta DEFAULT VALUES;
