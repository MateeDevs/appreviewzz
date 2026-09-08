-- F8/C1: měsíční report pro klienta.
--
-- Report je **zmrazený**, ne živý dotaz. Odkaz, který agentura pošle klientovi, musí za
-- půl roku ukázat totéž co dnes — a živý výpočet by se změnil s každým dotagováním, s
-- každou novou verzí taxonomie i s tím, jak se recenze doplní z archivu. Proto se agregáty
-- ukládají jako JSON: co se jednou poslalo ven, se už nemá měnit pod rukama.
CREATE TABLE insight_report (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id       uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    app_id       uuid        NOT NULL REFERENCES app (id) ON DELETE CASCADE,
    period_start date        NOT NULL,
    period_end   date        NOT NULL,
    aggregates   jsonb       NOT NULL,
    share_token  text UNIQUE,
    created_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (app_id, period_start)
);

COMMENT ON COLUMN insight_report.aggregates IS
    'Zmrazený výsledek rozboru za období. Přegenerování řádek přepíše; jinak se čísla nikdy nemění.';
COMMENT ON COLUMN insight_report.share_token IS
    'NULL = nesdíleno. Zrušení sdílení token maže, takže starý odkaz přestane platit okamžitě a natrvalo.';

CREATE INDEX insight_report_app_idx ON insight_report (app_id, period_start DESC);
