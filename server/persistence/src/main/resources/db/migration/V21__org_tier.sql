-- Tarify se přejmenovávají na to, čemu rozumí i člověk, který neviděl ceník:
-- `INSIGHTS` → `REGULAR`, `AGENCY` → `ENTERPRISE`. Jména tarifů se od téhle chvíle
-- ukazují v konzoli, takže vokabulář v databázi, v kódu a na obrazovce musí být jeden.
--
-- Výchozí hodnota se zároveň mění na `REGULAR`. Dokud se nefakturuje, nemá `STARTER`
-- u nové organizace co dělat: jediné, co dnes tarif ovlivňuje, je měsíční report, a nová
-- organizace o něj neměla přijít jen proto, že o tarifech nikdo neřekl. S billingem se
-- výchozí hodnota vrátí na `STARTER` (a `core/model/Enums.kt` s ní).
--
-- Existující řádky se **jen přejmenují**, nikdo se nepovyšuje: tarif je údaj s cenou.

ALTER TABLE organization DROP CONSTRAINT IF EXISTS organization_plan_check;

UPDATE organization SET plan = 'REGULAR' WHERE plan = 'INSIGHTS';
UPDATE organization SET plan = 'ENTERPRISE' WHERE plan = 'AGENCY';

ALTER TABLE organization ALTER COLUMN plan SET DEFAULT 'REGULAR';

ALTER TABLE organization ADD CONSTRAINT organization_plan_check
    CHECK (plan IN ('STARTER', 'REGULAR', 'ENTERPRISE'));

COMMENT ON COLUMN organization.plan IS
    'Tarif organizace. Nevynucuje se — rozhoduje jen o tom, komu se generuje měsíční report (REGULAR a vyšší).';
