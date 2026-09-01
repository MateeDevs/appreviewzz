-- Onboarding wizard: odkud se klíč vzal.
--
-- U Google Play service account nevyrábí klient, ale my (per organizace, v našem GCP
-- projektu) — klient ho jen pozve do Play Console. Takový klíč se chová jinak: nemá smysl
-- u něj nabízet rotaci payloadu a v seznamu patří označit jako spravovaný.
--
-- Výchozí 'UPLOADED' sedí na všechno, co vzniklo do téhle migrace: to klient nahrál sám.
ALTER TABLE credential
    ADD COLUMN origin text NOT NULL DEFAULT 'UPLOADED'
        CHECK (origin IN ('UPLOADED', 'PROVISIONED'));

COMMENT ON COLUMN credential.origin IS
    'UPLOADED = nahrál klient, PROVISIONED = vyrobili jsme my (service account v našem projektu).';
