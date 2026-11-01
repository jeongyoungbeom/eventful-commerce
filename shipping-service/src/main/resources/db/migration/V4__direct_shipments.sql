-- 직매입은 출고 지시별 별도 배송을 사용하므로 입점 seller_order_id UNIQUE를 유지한다.
CREATE TABLE direct_shipment (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL, seller_order_id UUID NOT NULL, warehouse_id UUID NOT NULL,
    request_content TEXT NOT NULL, status VARCHAR(20) NOT NULL CHECK (status IN ('STARTED','COMPLETED')),
    shipped_at TIMESTAMPTZ NOT NULL, completed_at TIMESTAMPTZ,
    CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))
);
CREATE INDEX idx_direct_shipment_completion ON direct_shipment(shipped_at, id) WHERE status = 'STARTED';
