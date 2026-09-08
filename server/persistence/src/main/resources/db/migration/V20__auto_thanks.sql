-- F8/C2: automatické poděkování za pětihvězdičkovou recenzi.
--
-- Pochvala je jediný druh recenze, kde odpověď nikdo nemusí vážit: nic se neřeší, nic se
-- neslibuje, jen se poděkuje. A přitom je to většina toho, na co týmy nestíhají odpovídat —
-- z pěti hvězd se dlouhodobě odpovídá nejmíň, protože to nikdy není nejnaléhavější.
--
-- Odesílá se **bez schválení**, takže podmínky musí být přísné a rozhoduje o nich výklad
-- recenze (pět hvězd, typ PRAISE, žádné záporné téma). Bez výkladu se neodesílá nic —
-- automatická odpověď na stížnost je horší než žádná.
ALTER TABLE app
    ADD COLUMN auto_thanks_enabled  boolean NOT NULL DEFAULT false,
    ADD COLUMN auto_thanks_template text;

COMMENT ON COLUMN app.auto_thanks_enabled IS
    'Automatická odpověď na pětihvězdičkové recenze bez kritiky. Výchozí false: zapíná to klient vědomě.';
COMMENT ON COLUMN app.auto_thanks_template IS
    'Záložní text, když AI návrh odpovědi chybí. NULL = bez návrhu se nic neodešle.';

-- Nový zdroj odpovědi. Bez rozšíření CHECKu by první automatická odpověď spadla na
-- constraint violation — a to až ve chvíli publikace, ne při zapnutí funkce.
ALTER TABLE reply DROP CONSTRAINT reply_source_check;
ALTER TABLE reply ADD CONSTRAINT reply_source_check
    CHECK (source IN ('SLACK', 'TEAMS', 'CONSOLE', 'AUTO'));

COMMENT ON COLUMN reply.source IS
    'Odkud odpověď přišla. AUTO = automatické poděkování za 5 ★; v inboxu se označuje odznakem.';
