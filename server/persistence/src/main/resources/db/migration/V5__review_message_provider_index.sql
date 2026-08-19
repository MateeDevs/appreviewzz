-- Párování příchozí interakce ze Slacku zpátky na recenzi (kanál + `ts`) je jediné čtení,
-- které má tvrdý časový strop: Slack čeká na potvrzení do tří sekund, jinak uživateli ukáže
-- chybu. Bez indexu je to sekvenční průchod přes všechny doručené zprávy všech klientů,
-- který se s časem jen zhoršuje.
--
-- Částečný index schválně: zprávy ve stavu PENDING a FAILED ještě `provider_message_id`
-- nemají a hledat se podle nich nedá.
CREATE INDEX review_message_provider_idx
    ON review_message (provider_conversation_id, provider_message_id)
    WHERE provider_message_id IS NOT NULL;
