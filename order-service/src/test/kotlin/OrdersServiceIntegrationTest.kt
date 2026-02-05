import com.eventfulcommerce.order.domain.OrdersRequest
import com.eventfulcommerce.order.domain.OrdersStatus
import com.eventfulcommerce.order.domain.entity.Orders
import com.eventfulcommerce.order.repository.OrdersRepository
import com.eventfulcommerce.order.service.OrdersService
import com.eventfulcommerce.common.OutboxEventService
import org.assertj.core.api.Assertions.assertThat
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import java.util.UUID
import kotlin.test.Test

class OrdersServiceIntegrationTest {

    private val ordersRepository: OrdersRepository = mock()
    private val outboxEventService: OutboxEventService = mock()

    private val orderService = OrdersService(ordersRepository, outboxEventService)

    @Test
    fun `주문 저장 후 outbox recode가 저장된 주문 리스트로 호출된다`() {
        // given
        val requests = listOf(
            OrdersRequest(userId = "80f1b6b3-33a5-4984-b2a7-748964166dae", totalAmount = 10000L),
            OrdersRequest(userId = "e1de76a5-26bf-495f-8234-fba01c304beb", totalAmount = 20000L),
        )

        val savedOrders = listOf(
            Orders(
                userId = UUID.fromString("80f1b6b3-33a5-4984-b2a7-748964166dae"),
                totalAmount = 10000L,
                status = OrdersStatus.ORDER_CREATED
            ),
            Orders(
                userId = UUID.fromString("e1de76a5-26bf-495f-8234-fba01c304beb"),
                totalAmount = 20000L,
                status = OrdersStatus.ORDER_CREATED
            ),
        ).also {
            it[0].id = UUID.fromString("a7117d0c-873d-44a8-b330-266d32aee243")
            it[1].id = UUID.fromString("4edd2ca2-dd3e-4631-8ac8-daaf712a5571")
        }

        whenever(ordersRepository.saveAll(any<List<Orders>>())).thenReturn(savedOrders)

        val result = orderService.orders(requests)

        assertThat(result).isEqualTo("success")
        verify(ordersRepository, times(1)).saveAll(any<List<Orders>>())
        verify(outboxEventService, times(1)).recode(savedOrders)
        verifyNoMoreInteractions(outboxEventService)
    }

}