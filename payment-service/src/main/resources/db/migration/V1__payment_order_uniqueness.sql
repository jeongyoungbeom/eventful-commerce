DO $$
BEGIN
    IF to_regclass('public.payment') IS NULL THEN
        RETURN;
    END IF;

    IF EXISTS (SELECT 1 FROM payment GROUP BY order_id HAVING COUNT(*) > 1) THEN
        RAISE EXCEPTION 'Cannot add payment uniqueness: duplicate order_id rows exist';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_payment_order') THEN
        ALTER TABLE payment
            ADD CONSTRAINT uk_payment_order UNIQUE (order_id);
    END IF;
END $$;
