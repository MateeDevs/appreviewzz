-- F7.4 — interval stahování recenzí přestává být volbou klienta.
--
-- Není to zákaznická preference, je to knob na náš provoz: kvóty store API, zátěž workeru,
-- počet volání do AI. Sloupec zůstává, ale mění význam — z „hodnoty" se stává **výjimka**,
-- kterou uděluje provozovatel platformy. `NULL` znamená „platí platformní výchozí".

ALTER TABLE app
    ALTER COLUMN ingest_interval_minutes DROP NOT NULL,
    ALTER COLUMN ingest_interval_minutes DROP DEFAULT;

COMMENT ON COLUMN app.ingest_interval_minutes IS
    'Výjimka od platformní výchozí hodnoty (platform_setting ingest.default_interval_minutes); NULL = výchozí.';

-- Dnešních 30 minut je výchozí hodnota, kterou nikdo vědomě nenastavil — po tomhle appky
-- poslouchají nastavení platformy. Odlišnou hodnotu (někdo ji zadal ručně) necháváme být,
-- z té se stává výjimka.
UPDATE app SET ingest_interval_minutes = NULL WHERE ingest_interval_minutes = 30;
