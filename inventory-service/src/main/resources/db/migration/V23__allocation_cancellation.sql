ALTER TABLE inventory_reservation DROP CONSTRAINT inventory_reservation_status_check;
ALTER TABLE inventory_reservation ADD CONSTRAINT inventory_reservation_status_check
    CHECK (status IN ('PENDING','RESERVED','REJECTED','RELEASED','EXPIRED','ALLOCATED','INVALIDATED','CANCELED'));
ALTER TABLE inventory_stock_ledger DROP CONSTRAINT inventory_ledger_document_check;
ALTER TABLE inventory_stock_ledger ADD CONSTRAINT inventory_ledger_document_check CHECK (
    (document_type IN ('INBOUND','INSPECTION') AND receipt_id IS NOT NULL AND receipt_line_id IS NOT NULL
        AND actor_id IS NOT NULL AND on_hand_delta <> 0 AND reserved_delta = 0 AND allocated_delta = 0)
    OR (document_type IN ('RESERVATION','ALLOCATION','RELEASE','EXPIRATION','INVALIDATION','ALLOCATION_CANCEL')
        AND receipt_id IS NULL AND receipt_line_id IS NULL AND on_hand_delta = 0 AND blocked_delta = 0 AND (
            (document_type = 'RESERVATION' AND reserved_delta > 0 AND allocated_delta = 0)
            OR (document_type = 'ALLOCATION' AND reserved_delta < 0 AND allocated_delta::numeric = -reserved_delta::numeric)
            OR (document_type IN ('RELEASE','EXPIRATION','INVALIDATION') AND reserved_delta < 0 AND allocated_delta = 0)
            OR (document_type = 'ALLOCATION_CANCEL' AND reserved_delta = 0 AND allocated_delta < 0)
        ))
);
