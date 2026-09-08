-- A10: jak hluboko se pro aplikaci stahuje historie recenzí.
--
-- Nová appka nemá první měsíc co rozebírat: Google Play API vrací jen ~týden zpět a jen
-- recenze s textem. Historie se proto bere z měsíčních exportů v reportingovém bucketu
-- (`reviews/reviews_<package>_<YYYYMM>.csv`) a tohle číslo říká, kolik měsíců zpátky.
--
-- Není to jednorázová volba při zakládání: export se čte i průběžně, protože hodnocení bez
-- textu přes API nepřijdou nikdy a jednorázový import by udělal řadu, ve které historie
-- obsahuje něco, co živé období nemá.
ALTER TABLE app
    ADD COLUMN history_months smallint NOT NULL DEFAULT 1
        CHECK (history_months BETWEEN 1 AND 24);

COMMENT ON COLUMN app.history_months IS
    'Kolik měsíců zpětné historie recenzí se stahuje z reportingového bucketu Play Console. Bez bucketu se neuplatní.';
