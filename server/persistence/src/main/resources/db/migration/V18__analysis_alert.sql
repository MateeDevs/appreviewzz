-- F8/B4: alert na výkyv v recenzích.
--
-- Týdenní rozbor je zpětné zrcátko. Když appce ráno po vydání začnou chodit desítky
-- stížností na pády, tým se to nemá dozvědět v pondělí — má to vidět týž den.
--
-- Výkyv je **statistika, ne AI**: baseline je 28 dní, spike je z ≥ 3 nad průměrem a
-- zároveň aspoň pět recenzí v absolutních číslech. Model do toho nesahá vůbec — kdyby
-- alert vyhlašovala AI, nešlo by po ránu vysvětlit, proč vlastně přišel.
CREATE TABLE analysis_alert (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      uuid          NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    app_id      uuid          NOT NULL REFERENCES app (id) ON DELETE CASCADE,
    kind        text          NOT NULL CHECK (kind IN ('NEGATIVE_SPIKE', 'TOPIC_SPIKE')),
    topic_key   text,
    window_date date          NOT NULL,
    observed    integer       NOT NULL,
    expected    numeric(8, 2) NOT NULL,
    z_score     numeric(6, 2) NOT NULL,
    created_at  timestamptz   NOT NULL DEFAULT now()
);

COMMENT ON COLUMN analysis_alert.topic_key IS
    'Jen u TOPIC_SPIKE; u NEGATIVE_SPIKE je NULL, protože ten se týká celé appky.';
COMMENT ON COLUMN analysis_alert.window_date IS
    'Kalendářní den v zóně aplikace, za který alert platí. Den, ne okamžik — dva běhy téhož dne mají poslat jednu zprávu.';
COMMENT ON COLUMN analysis_alert.expected IS
    'Průměr baseline (28 dní před dneškem). Do zprávy jde jako „obvykle bývá", aby bylo vidět, proti čemu se to měří.';

-- Dedup na den: dotagování běží po dávkách a jedna appka jich za den spolkne klidně
-- deset. Bez unikátního klíče by z jednoho výkyvu bylo deset zpráv.
--
-- COALESCE, ne holý UNIQUE: v Postgresu jsou dva řádky s NULL v klíči různé, takže by
-- NEGATIVE_SPIKE (topic_key IS NULL) nebyl unikátní vůbec.
CREATE UNIQUE INDEX analysis_alert_window_idx
    ON analysis_alert (app_id, kind, COALESCE(topic_key, ''), window_date);

CREATE INDEX analysis_alert_app_idx ON analysis_alert (app_id, window_date DESC);
