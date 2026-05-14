package com.eventfulcommerce.notification.service

import java.util.UUID

object NotificationTemplate {
    
    fun orderReserved(orderId: UUID): Pair<String, String> {
        val title = "주문 접수 완료"
        val message = """
🛒 <b>주문 접수 완료</b>

주문번호: <code>$orderId</code>

⏰ 10분 내 결제를 완료해주세요!
        """.trimIndent()
        
        return title to message
    }
    
    fun paymentCompleted(orderId: UUID, amount: Long): Pair<String, String> {
        val title = "결제 완료"
        val message = """
💳 <b>결제 완료</b>

주문번호: <code>$orderId</code>
결제금액: ${amount}원

📦 배송 준비 중입니다.
        """.trimIndent()
        
        return title to message
    }
    
    fun orderCanceled(orderId: UUID, reason: String): Pair<String, String> {
        val title = "주문 취소"
        val message = """
❌ <b>주문 취소</b>

주문번호: <code>$orderId</code>
취소 사유: $reason

환불 처리 예정입니다.
        """.trimIndent()
        
        return title to message
    }
    
    fun shippingStarted(orderId: UUID, trackingNumber: String): Pair<String, String> {
        val title = "배송 시작"
        val message = """
🚚 <b>배송 시작</b>

주문번호: <code>$orderId</code>
운송장번호: <code>$trackingNumber</code>

배송 조회: https://tracking.example.com/$trackingNumber
        """.trimIndent()
        
        return title to message
    }
    
    fun shippingCompleted(orderId: UUID): Pair<String, String> {
        val title = "배송 완료"
        val message = """
✅ <b>배송 완료</b>

주문번호: <code>$orderId</code>

상품이 배송되었습니다. 리뷰를 남겨주세요!
        """.trimIndent()

        return title to message
    }

    // ── 판매자용 ──────────────────────────────────────────────────

    fun sellerOrderReceived(orderId: UUID, amount: Long, quantity: Int): Pair<String, String> {
        val title = "새 주문 접수"
        val message = """
🛒 <b>새 주문이 들어왔습니다!</b>

주문번호: <code>$orderId</code>
주문 수량: ${quantity}개
주문 금액: ${amount}원

⏰ 결제 완료 후 배송을 준비해주세요.
        """.trimIndent()

        return title to message
    }

    fun sellerPaymentReceived(orderId: UUID, amount: Long): Pair<String, String> {
        val title = "결제 완료 — 배송 준비 요청"
        val message = """
💳 <b>결제가 완료되었습니다!</b>

주문번호: <code>$orderId</code>
결제 금액: ${amount}원

📦 배송을 준비해주세요.
        """.trimIndent()

        return title to message
    }

    fun sellerOrderCanceled(orderId: UUID, reason: String): Pair<String, String> {
        val title = "주문 취소됨"
        val message = """
❌ <b>주문이 취소되었습니다.</b>

주문번호: <code>$orderId</code>
취소 사유: $reason
        """.trimIndent()

        return title to message
    }

    private fun Int.formatAmount(): String {
        return "%,d".format(this)
    }
}
