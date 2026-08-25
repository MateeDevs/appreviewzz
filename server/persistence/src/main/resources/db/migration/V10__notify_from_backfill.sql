-- Appky založené dřív, než se watermark vyplňoval sám, mají `notify_from` prázdné.
-- Prázdná hodnota znamenala „posílej všechno", takže první ingest po připojení klíče
-- vysypal do kanálu i recenze staré několik měsíců. Watermarkem je nově čas přidání
-- appky do systému; historie zůstane v databázi, jen se nenotifikuje.
UPDATE app SET notify_from = created_at WHERE notify_from IS NULL;

COMMENT ON COLUMN app.notify_from IS
    'Watermark: recenze starší než tohle se uloží, ale nenotifikují. Výchozí hodnota je čas přidání appky.';
