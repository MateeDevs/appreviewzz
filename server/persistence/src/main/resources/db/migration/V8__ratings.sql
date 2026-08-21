-- F4: denní přehledy hodnocení.
--
-- Oficiální Android čísla leží v reportingovém bucketu Play Console (`pubsite_prod_…`).
-- Jeho jméno nejde z balíčku odvodit — klient ho opisuje z Play Console („Copy Cloud Storage
-- URI"). Dnešní n8n ho má natvrdo v uzlu workflow pro každého klienta zvlášť, což je přesně
-- ta ruční práce, kvůli které se to celé přepisuje.
--
-- Prázdné pole je legitimní stav: klient bez přístupu do Play Console dostane hodnocení
-- ze scrapu veřejného listingu.
ALTER TABLE app ADD COLUMN gp_reporting_bucket text;

-- Poslední doručený přehled per aplikace a kanál. Bez toho by se po restartu nebo při
-- opakovaném běhu jobu poslal digest dvakrát — a druhý by ještě ukazoval nulovou deltu,
-- protože srovnávací snapshot už je z dneška.
CREATE TABLE ratings_digest (
    org_id        uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    app_id        uuid        NOT NULL REFERENCES app (id) ON DELETE CASCADE,
    channel_id    uuid        NOT NULL REFERENCES channel (id) ON DELETE CASCADE,
    digest_date   date        NOT NULL,
    sent_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (channel_id, digest_date)
);

CREATE INDEX ratings_digest_app_idx ON ratings_digest (app_id, digest_date DESC);
