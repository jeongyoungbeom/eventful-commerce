ALTER TABLE payment ADD COLUMN IF NOT EXISTS pg_tx_id VARCHAR(200);
ALTER TABLE payment ADD CONSTRAINT uk_payment_pg_tx UNIQUE (pg_tx_id);
ALTER TABLE payment ADD CONSTRAINT ck_payment_pg_tx_nonblank
    CHECK (pg_tx_id IS NULL OR (length(btrim(pg_tx_id)) > 0 AND pg_tx_id = btrim(pg_tx_id)));
