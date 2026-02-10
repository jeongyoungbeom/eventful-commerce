package com.eventfulcommerce.order.scheduler

import com.eventfulcommerce.order.domain.OrdersStatus
import com.eventfulcommerce.order.repository.OrdersRepository
import com.eventfulcommerce.order.service.InventoryReservationService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class OrderExpireScheduler(
    private val ordersRepository: OrdersRepository,
    private val inventoryReservationService: InventoryReservationService
) {

    @Scheduled(fixedDelayString = "10000")
    fun expireReservedOrders() {
        val now = Instant.now()

        val expiredOrders =
            ordersRepository.findByStatusAndExpiresAtBefore(OrdersStatus.ORDER_RESERVED, now)

        if (expiredOrders.isEmpty()) return

        expiredOrders.forEach { order ->
            val reservationId = order.reservationId
            if (reservationId != null) {
                inventoryReservationService.release(reservationId)
            }

            order.status = OrdersStatus.ORDER_EXPIRED
        }

        ordersRepository.saveAll(expiredOrders)
    }


}