-- Recenze, kterou autor ve storu smazal.
--
-- Store o smazání nedá vědět, recenze prostě přestane chodit. Poznat se to dá jen tam, kde
-- víme, že bychom ji vidět měli: App Store vrací výpis od nejnovější bez mezer, takže co
-- v pokrytém období chybí, už tam není; Google Play `reviews.get` na smazanou vrátí 404.
-- Řádek zůstává — byla to skutečná zpětná vazba a patří do rozborů — jen se označí.
ALTER TABLE review
    ADD COLUMN removed_at timestamptz;

COMMENT ON COLUMN review.removed_at IS
    'Kdy jsme zjistili, že recenze ze storu zmizela (smazal ji autor). NULL = ve storu je. Když se znovu objeví, vrací se na NULL.';
