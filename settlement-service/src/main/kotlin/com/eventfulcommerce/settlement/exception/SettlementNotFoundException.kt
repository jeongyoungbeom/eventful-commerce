package com.eventfulcommerce.settlement.exception

import java.util.UUID

class SettlementNotFoundException(id: UUID) : RuntimeException("정산을 찾을 수 없습니다: $id")
