-- Historie záloh (F1.8). Záloha, o které nikdo neví, že přestala chodit, není záloha —
-- tahle tabulka je zdroj pravdy pro metriku stáří poslední úspěšné zálohy i pro konzoli (F3).
--
-- Zapisuje se až doběhnutý pokus, úspěšný i neúspěšný. Spadlý proces řádek nezanechá,
-- a projeví se právě tím, že stáří poslední úspěšné zálohy roste.

CREATE TABLE backup_run (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    started_at  timestamptz NOT NULL,
    finished_at timestamptz NOT NULL,
    status      text        NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED')),
    -- Kam se dump uložil: s3://bucket/klíč nebo file:///cesta. U selhání NULL.
    location    text,
    size_bytes  bigint      CHECK (size_bytes >= 0),
    -- SHA-256 dumpu; při obnově se ověřuje, že se soubor cestou nezměnil.
    checksum    text,
    error       text,
    CHECK (status = 'FAILED' OR location IS NOT NULL)
);

CREATE INDEX backup_run_finished_idx ON backup_run (finished_at DESC);
CREATE INDEX backup_run_success_idx ON backup_run (finished_at DESC) WHERE status = 'SUCCEEDED';
