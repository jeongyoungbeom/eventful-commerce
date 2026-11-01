ALTER TABLE inventory_reservation DROP CONSTRAINT inventory_reservation_status_check;
ALTER TABLE inventory_reservation ADD CONSTRAINT inventory_reservation_status_check CHECK
    (status IN ('PENDING','RESERVED','REJECTED','RELEASED','EXPIRED','ALLOCATED','INVALIDATED','CANCELED','SHIPPED','RETURNED'));
CREATE TABLE inventory_outbound (
    id UUID PRIMARY KEY REFERENCES inventory_reservation(id),
    order_id UUID NOT NULL, seller_order_id UUID NOT NULL, order_item_id UUID NOT NULL UNIQUE,
    warehouse_id UUID NOT NULL REFERENCES inventory_warehouse(id), request_content TEXT NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('READY','PICKING','PACKED','SHIPPED','CANCELED')),
    cancellation_requested BOOLEAN NOT NULL DEFAULT false, cancellation_approved BOOLEAN,
    decision_content TEXT, pick_content TEXT, pack_content TEXT, ship_content TEXT,
    ineligibility_cancellation_content TEXT,
    created_at TIMESTAMPTZ NOT NULL, shipped_at TIMESTAMPTZ, delivered_at TIMESTAMPTZ,
    CHECK (cancellation_approved IS NULL OR cancellation_requested),
    CHECK ((cancellation_approved IS NULL) = (decision_content IS NULL)),
    CHECK ((status = 'SHIPPED') = (shipped_at IS NOT NULL)),
    CHECK (delivered_at IS NULL OR shipped_at IS NOT NULL),
    CONSTRAINT inventory_outbound_ineligibility_cancellation_check CHECK (
        ineligibility_cancellation_content IS NULL OR (status = 'CANCELED' AND cancellation_approved IS FALSE)
    )
);
CREATE INDEX idx_inventory_outbound_order ON inventory_outbound(order_id, id);
CREATE TABLE inventory_return_receipt (
    id UUID PRIMARY KEY, outbound_id UUID NOT NULL UNIQUE REFERENCES inventory_outbound(id),
    reason VARCHAR(500) NOT NULL, actor_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('REQUESTED','RECEIVED','INSPECTED')),
    inspection_content TEXT, requested_at TIMESTAMPTZ NOT NULL, received_at TIMESTAMPTZ, inspected_at TIMESTAMPTZ,
    CHECK ((status <> 'REQUESTED') = (received_at IS NOT NULL)),
    CHECK ((status = 'INSPECTED') = (inspected_at IS NOT NULL)),
    CHECK ((status = 'INSPECTED') = (inspection_content IS NOT NULL))
);
ALTER TABLE inventory_stock_ledger DROP CONSTRAINT inventory_ledger_document_check;
ALTER TABLE inventory_stock_ledger ADD CONSTRAINT inventory_ledger_document_check CHECK (
    (transfer_held_delta = 0 AND (
    (document_type IN ('INBOUND','INSPECTION') AND receipt_id IS NOT NULL AND receipt_line_id IS NOT NULL
        AND actor_id IS NOT NULL AND on_hand_delta <> 0 AND reserved_delta = 0 AND allocated_delta = 0)
    OR (document_type IN ('RESERVATION','ALLOCATION','RELEASE','EXPIRATION','INVALIDATION','ALLOCATION_CANCEL')
        AND receipt_id IS NULL AND receipt_line_id IS NULL AND on_hand_delta = 0 AND blocked_delta = 0 AND (
            (document_type = 'RESERVATION' AND reserved_delta > 0 AND allocated_delta = 0)
            OR (document_type = 'ALLOCATION' AND reserved_delta < 0 AND allocated_delta::numeric = -reserved_delta::numeric)
            OR (document_type IN ('RELEASE','EXPIRATION','INVALIDATION') AND reserved_delta < 0 AND allocated_delta = 0)
            OR (document_type = 'ALLOCATION_CANCEL' AND reserved_delta = 0 AND allocated_delta < 0)))
    OR (document_type = 'OUTBOUND' AND receipt_id IS NULL AND receipt_line_id IS NULL AND actor_id IS NOT NULL
        AND on_hand_delta < 0 AND allocated_delta = on_hand_delta AND reserved_delta = 0 AND blocked_delta = 0)
    OR (document_type = 'RETURN' AND receipt_id IS NULL AND receipt_line_id IS NULL AND actor_id IS NOT NULL
        AND on_hand_delta > 0 AND allocated_delta = 0 AND reserved_delta = 0 AND blocked_delta BETWEEN 0 AND on_hand_delta)
))
    OR (document_type IN ('TRANSFER_HOLD','TRANSFER_CANCEL','TRANSFER_DISPATCH','TRANSFER_ARRIVAL')
        AND receipt_id IS NULL AND receipt_line_id IS NULL AND actor_id IS NOT NULL
        AND reserved_delta = 0 AND allocated_delta = 0 AND blocked_delta = 0 AND (
            (document_type = 'TRANSFER_HOLD' AND on_hand_delta = 0 AND transfer_held_delta > 0)
            OR (document_type = 'TRANSFER_CANCEL' AND on_hand_delta = 0 AND transfer_held_delta < 0)
            OR (document_type = 'TRANSFER_DISPATCH' AND on_hand_delta < 0 AND transfer_held_delta = on_hand_delta)
            OR (document_type = 'TRANSFER_ARRIVAL' AND on_hand_delta > 0 AND transfer_held_delta = 0)
        ))
    OR (document_type = 'STOCK_COUNT' AND receipt_id IS NULL AND receipt_line_id IS NULL AND actor_id IS NOT NULL
        AND reserved_delta = 0 AND allocated_delta = 0 AND transfer_held_delta = 0
        AND (blocked_delta = 0 OR blocked_delta = on_hand_delta))
);
