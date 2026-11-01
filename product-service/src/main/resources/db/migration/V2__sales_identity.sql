CREATE TABLE official_seller (
    id UUID PRIMARY KEY,
    display_name VARCHAR(100) NOT NULL CHECK (length(trim(display_name)) > 0),
    CONSTRAINT ck_official_seller_id CHECK (id = '00000000-0000-0000-0000-000000000001')
);
ALTER TABLE products ADD COLUMN sales_type VARCHAR(32) NOT NULL DEFAULT 'MARKETPLACE';
ALTER TABLE products ADD CONSTRAINT ck_products_sales_identity CHECK (
    (sales_type = 'MARKETPLACE' AND seller_id <> '00000000-0000-0000-0000-000000000001') OR
    (sales_type = 'DIRECT_PURCHASE' AND seller_id = '00000000-0000-0000-0000-000000000001' AND stock = 0)
);
