-- Ověření klíče pro jednu aplikaci nepotvrzuje přístup k jiné aplikaci téhož týmu.
ALTER TABLE app_credential ADD COLUMN validation_status varchar(32) DEFAULT 'UNKNOWN';
ALTER TABLE app_credential ADD COLUMN validation_error text;
ALTER TABLE app_credential ADD COLUMN validated_at timestamptz;
-- Zachovat stav existujících aktivních instalací; nové vazby začínají neověřené.
UPDATE app_credential ac SET validation_status = c.validation_status,
    validation_error = c.validation_error, validated_at = c.validated_at
FROM credential c WHERE ac.credential_id = c.id;
