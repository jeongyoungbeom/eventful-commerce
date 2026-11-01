CREATE TABLE inventory_reservation (
    id UUID PRIMARY KEY, order_attempt_id UUID NOT NULL, order_item_id UUID NOT NULL,
    -- 생성 전 해제와 미등록 SKU의 종결 결과도 보존하므로 SKU FK를 두지 않는다.
    sku_id UUID NOT NULL, quantity BIGINT NOT NULL CHECK (quantity > 0), expires_at TIMESTAMPTZ NOT NULL,
    request_content TEXT NOT NULL, status VARCHAR(20) NOT NULL
        CHECK (status IN ('PENDING','RESERVED','REJECTED','RELEASED','EXPIRED','ALLOCATED','INVALIDATED')),
    warehouse_id UUID REFERENCES inventory_warehouse(id), reason VARCHAR(50),
    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
    CHECK (status NOT IN ('RESERVED','ALLOCATED') OR warehouse_id IS NOT NULL),
    UNIQUE (id, sku_id, warehouse_id)
);
CREATE INDEX idx_inventory_reservation_expiry ON inventory_reservation(expires_at, id) WHERE status = 'RESERVED';
ALTER TABLE inventory_stock_balance ADD CONSTRAINT uq_inventory_balance_scope UNIQUE (warehouse_id, lot_id, sku_id);
CREATE TABLE inventory_reservation_line (
    id UUID PRIMARY KEY, reservation_id UUID NOT NULL, sku_id UUID NOT NULL,
    warehouse_id UUID NOT NULL, lot_id UUID NOT NULL, quantity BIGINT NOT NULL CHECK (quantity > 0),
    FOREIGN KEY (reservation_id, sku_id, warehouse_id) REFERENCES inventory_reservation(id, sku_id, warehouse_id),
    FOREIGN KEY (warehouse_id, lot_id, sku_id) REFERENCES inventory_stock_balance(warehouse_id, lot_id, sku_id),
    UNIQUE (reservation_id, lot_id)
);

-- S2 입고 원장을 보존하며 실물 수량을 바꾸지 않는 예약/할당 변화도 같은 원장에 기록한다.
ALTER TABLE inventory_stock_ledger
    ALTER COLUMN receipt_id DROP NOT NULL, ALTER COLUMN receipt_line_id DROP NOT NULL,
    ALTER COLUMN actor_id DROP NOT NULL,
    ADD COLUMN reserved_delta BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN allocated_delta BIGINT NOT NULL DEFAULT 0;
ALTER TABLE inventory_stock_ledger DROP CONSTRAINT inventory_stock_ledger_document_type_check;
ALTER TABLE inventory_stock_ledger DROP CONSTRAINT inventory_stock_ledger_on_hand_delta_check;
ALTER TABLE inventory_stock_ledger DROP CONSTRAINT inventory_stock_ledger_check;
ALTER TABLE inventory_stock_ledger ADD CONSTRAINT inventory_ledger_delta_direction CHECK (
    (on_hand_delta >= 0 AND blocked_delta BETWEEN 0 AND on_hand_delta)
    OR (on_hand_delta < 0 AND blocked_delta BETWEEN on_hand_delta AND 0)
);
ALTER TABLE inventory_stock_ledger ADD CONSTRAINT inventory_ledger_document_check CHECK (
    (document_type IN ('INBOUND','INSPECTION') AND receipt_id IS NOT NULL AND receipt_line_id IS NOT NULL
        AND actor_id IS NOT NULL AND on_hand_delta <> 0 AND reserved_delta = 0 AND allocated_delta = 0)
    OR (document_type IN ('RESERVATION','ALLOCATION','RELEASE','EXPIRATION','INVALIDATION')
        AND receipt_id IS NULL AND receipt_line_id IS NULL AND on_hand_delta = 0 AND blocked_delta = 0 AND (
            (document_type = 'RESERVATION' AND reserved_delta > 0 AND allocated_delta = 0)
            OR (document_type = 'ALLOCATION' AND reserved_delta < 0 AND allocated_delta::numeric = -reserved_delta::numeric)
            OR (document_type IN ('RELEASE','EXPIRATION','INVALIDATION') AND reserved_delta < 0 AND allocated_delta = 0)
        ))
);
