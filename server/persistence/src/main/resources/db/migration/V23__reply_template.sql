-- Šablony odpovědí (C2 produktového rozboru).
--
-- Podpora odpovídá na tytéž tři situace pořád dokola: poděkování, „ozvěte se na support",
-- „opraveno ve verzi X". Šablona je text s proměnnými `{jmeno}`, `{appka}`, `{verze}`,
-- které dosadí konzole při vložení do formuláře — server šablonu jen ukládá a nikdy ji
-- neodesílá sám (na rozdíl od automatického poděkování, které má vlastní pole u appky).
CREATE TABLE reply_template (
    id         uuid PRIMARY KEY,
    org_id     uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    app_id     uuid        NOT NULL REFERENCES app (id) ON DELETE CASCADE,
    name       text        NOT NULL,
    body       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX reply_template_app_idx ON reply_template (app_id, name);

COMMENT ON TABLE reply_template IS
    'Šablony odpovědí na recenze per appka. Proměnné {jmeno}, {appka}, {verze} dosazuje konzole.';
