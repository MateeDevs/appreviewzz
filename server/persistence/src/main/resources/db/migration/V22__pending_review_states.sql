-- Produktový rozbor konzole (A2): importovaná historie se tvářila jako dluh.
--
-- Recenze z doby před připojením appky stály v inboxu jako „čeká na odpověď", přestože na ně
-- nikdo odpovídat nebude — a recenze, na které někdo odpověděl přímo v Play Console nebo
-- App Store Connectu, zůstaly ve stejném stavu, i když odpověď ve storu visí. Nový kód to
-- řeší při ingestu (`AnsweredInStore`, watermark), tahle migrace srovná, co vzniklo dřív.
--
-- Pořadí záleží: nejdřív odpověď ze storu (REPLIED má přednost před SUPPRESSED, protože je to
-- informace, ne jen „nezajímá nás").

UPDATE review
   SET state = 'REPLIED', updated_at = now()
 WHERE developer_response_body IS NOT NULL
   AND state IN ('NEW', 'NOTIFIED', 'UPDATED');

-- Pod watermarkem se recenze ukládají bez notifikace; co se tam dostalo jinak (migrace z n8n,
-- backfill watermarku), se dorovná. U editované recenze rozhoduje čas editace, ne vzniku —
-- stará recenze přepsaná dneska je novinka.
UPDATE review r
   SET state = 'SUPPRESSED', updated_at = now()
  FROM app a
 WHERE a.id = r.app_id
   AND (
        (r.state IN ('NEW', 'NOTIFIED') AND r.submitted_at < COALESCE(a.notify_from, a.created_at))
     OR (r.state = 'UPDATED' AND COALESCE(r.store_updated_at, r.submitted_at) < COALESCE(a.notify_from, a.created_at))
   );
