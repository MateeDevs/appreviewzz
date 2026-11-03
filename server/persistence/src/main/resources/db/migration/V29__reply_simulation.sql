-- Nullable kvůli souběhu se starším procesem; null znamená skutečnou publikaci.
ALTER TABLE reply ADD COLUMN simulated BOOLEAN;
ALTER TABLE review_message ADD COLUMN simulated_replies BOOLEAN;
