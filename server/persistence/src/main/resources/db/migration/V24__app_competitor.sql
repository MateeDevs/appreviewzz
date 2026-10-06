-- Sledování konkurence (C4 produktového rozboru).
--
-- Cizí appka se přidá bez klíče: recenze se berou z veřejných zdrojů (App Store RSS feed),
-- nikdy se nenotifikují a nedá se na ně odpovídat. Rozbory nad nimi jedou stejně jako nad
-- vlastní appkou, takže agentura vidí, na co si lidé stěžují u konkurence.
--
-- Je to vlastnost dané při založení, ne přepínač: z konkurence se vlastní appka nestane
-- přidáním klíče — na to je nová appka.
ALTER TABLE app ADD COLUMN competitor boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN app.competitor IS
    'Konkurenční appka: jen veřejné recenze, bez klíče, bez kanálu, bez odpovídání. Rozhodnuto při založení.';
