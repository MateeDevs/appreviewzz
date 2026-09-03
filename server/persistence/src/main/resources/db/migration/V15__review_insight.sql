-- F8: výklad recenze (témata, sentiment, typ, naléhavost).
--
-- Recenze se dnes doručí a tím to končí — co v nich klient čte, si musí přebrat sám.
-- Tahle tabulka drží strukturovaný výklad každé recenze, ze kterého pak vzniká týdenní
-- rozbor, stránka Rozbory a alert na výkyv. Čísla počítá databáze, ne model.
--
-- Řádek patří ke *konkrétnímu znění* recenze: `content_hash` je otisk toho, co autor
-- napsal. Když recenzi přepíše, otisk se rozejde a výklad se spočítá znovu. Totéž
-- `taxonomy_version` — po změně seznamu témat jsou stará data neporovnatelná.
CREATE TABLE review_insight (
    review_id        uuid PRIMARY KEY REFERENCES review (id) ON DELETE CASCADE,
    org_id           uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    app_id           uuid        NOT NULL REFERENCES app (id) ON DELETE CASCADE,
    content_hash     text        NOT NULL,
    taxonomy_version text        NOT NULL,
    prompt_version   text        NOT NULL,
    model            text        NOT NULL,
    sentiment        text        NOT NULL CHECK (sentiment IN ('POSITIVE', 'NEGATIVE', 'MIXED', 'NEUTRAL')),
    type             text        NOT NULL CHECK (type IN ('BUG', 'FEATURE_REQUEST', 'COMPLAINT', 'PRAISE', 'QUESTION', 'OTHER')),
    urgency          text        NOT NULL CHECK (urgency IN ('LOW', 'MEDIUM', 'HIGH')),
    language         text,
    translation      text,
    analyzed_at      timestamptz NOT NULL DEFAULT now()
);

COMMENT ON COLUMN review_insight.content_hash IS
    'Otisk znění recenze, ke kterému výklad patří. Rozdíl proti review.content_hash = recenzi někdo editoval a výklad je neplatný.';
COMMENT ON COLUMN review_insight.model IS
    'Model, který výklad vyrobil. Hodnota "rules" = recenze bez textu, sentiment odvozený z hvězd.';
COMMENT ON COLUMN review_insight.language IS
    'Jazyk podle modelu (BCP-47), ne podle storu — store hlásí jazyk zařízení, ne jazyk textu.';

CREATE INDEX review_insight_app_idx ON review_insight (app_id, analyzed_at DESC);

-- Multi-label je záměr, ne nedodělek: jedna recenze běžně mluví o ceně i o pádech
-- a sentiment se liší téma od tématu. Součet podílů témat proto přesahuje 100 %.
CREATE TABLE review_insight_topic (
    review_id uuid NOT NULL REFERENCES review_insight (review_id) ON DELETE CASCADE,
    topic_key text NOT NULL,
    sentiment text NOT NULL CHECK (sentiment IN ('POSITIVE', 'NEGATIVE', 'NEUTRAL')),
    quote     text,
    PRIMARY KEY (review_id, topic_key)
);

COMMENT ON COLUMN review_insight_topic.topic_key IS
    'Klíč základní taxonomie (crash, pricing, …) nebo ''custom:<uuid>'' u vlastního tématu aplikace.';
COMMENT ON COLUMN review_insight_topic.quote IS
    'Doslovný úryvek recenze, ověřený jako podřetězec originálu. NULL, když se ověřit nepodařilo — citát se nikdy nevymýšlí.';

CREATE INDEX review_insight_topic_key_idx ON review_insight_topic (topic_key, review_id);

-- Vlastní témata aplikace. Popis jde doslova do promptu, proto anglicky: model taguje
-- v původním jazyce recenze proti anglické taxonomii a popisy uzlů jsou to, co zvedá
-- recall nejvíc.
CREATE TABLE app_topic (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    app_id      uuid        NOT NULL REFERENCES app (id) ON DELETE CASCADE,
    name        text        NOT NULL CHECK (length(name) BETWEEN 2 AND 60),
    description text        NOT NULL CHECK (length(description) BETWEEN 10 AND 300),
    enabled     boolean     NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (app_id, name)
);

COMMENT ON COLUMN app_topic.description IS
    'Anglicky — jde doslova do promptu jako popis uzlu taxonomie. Název je jen pro UI.';

-- Den týdenního rozboru v zóně aplikace; čas se bere ze stávajícího daily_digest_at,
-- aby klient nemusel nastavovat dva časy pro dvě zprávy.
ALTER TABLE app ADD COLUMN weekly_digest_day smallint NOT NULL DEFAULT 1
    CHECK (weekly_digest_day BETWEEN 1 AND 7);

COMMENT ON COLUMN app.weekly_digest_day IS 'ISO den v týdnu (1 = pondělí) pro týdenní rozbor recenzí.';

-- Třetí druh zprávy vedle recenzí a hodnocení. Výchozí true: kdo má kanál, chce rozbor —
-- vypnout se dá, ale nemá smysl po klientovi chtít, aby to zapínal ručně.
ALTER TABLE channel ADD COLUMN deliver_analyses boolean NOT NULL DEFAULT true;

-- Plán se zatím **nevynucuje**, jen se ukládá: měsíční report se generuje jen platícím,
-- aby se databáze neplnila daty, která nikdo neuvidí. Vynucení přijde s billingem.
ALTER TABLE organization ADD COLUMN plan text NOT NULL DEFAULT 'STARTER'
    CHECK (plan IN ('STARTER', 'INSIGHTS', 'AGENCY'));

-- Rezervace týdenního rozboru — stejný důvod jako u ratings_digest: kdyby se zapisovalo
-- až po odeslání, pád mezi odesláním a zápisem by rozbor poslal podruhé.
CREATE TABLE analysis_digest (
    org_id       uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    app_id       uuid        NOT NULL REFERENCES app (id) ON DELETE CASCADE,
    channel_id   uuid        NOT NULL REFERENCES channel (id) ON DELETE CASCADE,
    period_start date        NOT NULL,
    sent_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (channel_id, period_start)
);

CREATE INDEX analysis_digest_app_idx ON analysis_digest (app_id, period_start DESC);
