-- Uchová výsledek poslední zkušební zprávy i po obnovení konzole.
ALTER TABLE channel ADD COLUMN last_checked_at timestamptz;
ALTER TABLE channel ADD COLUMN last_check_error text;
