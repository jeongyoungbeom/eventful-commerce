DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM shipping WHERE seller_order_id IS NULL) THEN
        RAISE EXCEPTION 'Cannot enforce shipping.seller_order_id NOT NULL while NULL rows exist';
    END IF;
END $$;

ALTER TABLE shipping ALTER COLUMN seller_order_id SET NOT NULL;
