-- Kadence rozborů a prahy per aplikace.
--
-- Týdenní rozbor s pevným prahem deseti recenzí znamenal, že appka s řídkým provozem
-- dostávala každý týden zprávu „zatím málo dat" a nikdy skutečný rozbor. Nově se termín
-- pod prahem **přeskočí** a období se přičte k příštímu — z málo dat se tak stane delší
-- období, ne prázdná zpráva. Proto `analysis_digest.period_end`: bez něj se neví, odkud
-- má navazující období začít.
ALTER TABLE app
    ADD COLUMN analysis_cadence text NOT NULL DEFAULT 'WEEKLY'
        CHECK (analysis_cadence IN ('WEEKLY', 'MONTHLY')),
    -- NULL = drž se platformního nastavení. Appky se objemem liší o řád, takže výjimka
    -- musí jít udělit, ale výchozí stav je jedno číslo pro všechny.
    ADD COLUMN analysis_min_reviews smallint
        CHECK (analysis_min_reviews IS NULL OR analysis_min_reviews BETWEEN 1 AND 1000),
    ADD COLUMN analysis_min_topic_count smallint
        CHECK (analysis_min_topic_count IS NULL OR analysis_min_topic_count BETWEEN 1 AND 100);

COMMENT ON COLUMN app.analysis_cadence IS
    'Jak často se posílá rozbor recenzí: WEEKLY podle weekly_digest_day, MONTHLY prvního dne v měsíci.';
COMMENT ON COLUMN app.analysis_min_reviews IS
    'Výjimka od platformního prahu počtu recenzí s textem. NULL = platí platforma.';
COMMENT ON COLUMN app.analysis_min_topic_count IS
    'Výjimka od platformního prahu zmínek tématu. NULL = platí platforma.';

-- Konec období odeslaného rozboru. Historii dopočítáme z týdne, který tehdy platil jako
-- jediná možná kadence.
ALTER TABLE analysis_digest ADD COLUMN period_end date;
UPDATE analysis_digest SET period_end = period_start + 6 WHERE period_end IS NULL;
ALTER TABLE analysis_digest ALTER COLUMN period_end SET NOT NULL;

COMMENT ON COLUMN analysis_digest.period_end IS
    'Poslední den odeslaného období. Příští rozbor navazuje od následujícího dne, aby se přeskočený termín neztratil.';
