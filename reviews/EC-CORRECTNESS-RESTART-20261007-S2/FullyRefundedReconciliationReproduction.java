import com.eventfulcommerce.common.*;
import com.eventfulcommerce.common.repository.*;
import com.eventfulcommerce.payment.domain.PaymentStatus;
import com.eventfulcommerce.payment.domain.entity.Payment;
import com.eventfulcommerce.payment.repository.*;
import com.eventfulcommerce.payment.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.aop.support.AopUtils;
import org.springframework.test.context.TestContextManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class FullyRefundedReconciliationReproduction {
    private static PaymentService service;
    private static PaymentRepository payments;
    private static PaymentRefundRepository refunds;
    private static OutboxEventRepository outbox;
    private static ProcessedEventRepository markers;
    private static ObjectMapper mapper;
    private static TransactionTemplate tx;

    private static OutboxEventMessage message(OrderCanceledPayload payload, String type) throws Exception {
        return new OutboxEventMessage(UUID.randomUUID(), "ORDER", payload.getOrderId(), type,
            Instant.now(), mapper.writeValueAsString(payload));
    }

    private static OrderCanceledPayload payload(Payment payment, UUID user, UUID sellerOrder,
            UUID seller, long amount, String reason) {
        return new OrderCanceledPayload(payment.getOrderId(), user, reason,
            List.of(new OrderCanceledSellerPayload(sellerOrder, seller, amount, List.of())));
    }

    private static void verifyCase(String label, Payment payment, OrderCanceledPayload invalid,
            PaymentRefundedPayload original, boolean expectedAccepted) throws Exception {
        var event = message(invalid, "ORDER_CANCELLATION_RECONCILIATION_REQUESTED");
        var beforeOutbox = outbox.count();
        var beforeRefunds = refunds.count();
        var beforeAmount = tx.execute(status -> payments.findByOrderId(payment.getOrderId()).getRefundedAmount());
        Exception failure = null;
        try { service.handleOrderCancellationReconciliation(event); }
        catch (Exception ex) { failure = ex; }
        boolean accepted = failure == null;
        boolean marker = markers.existsById(event.getEventId());
        long delta = outbox.count() - beforeOutbox;
        if (accepted != expectedAccepted || marker != accepted || delta != (accepted ? 1 : 0)) {
            throw new AssertionError(label + " unexpected outcome: accepted=" + accepted + " marker=" + marker + " delta=" + delta);
        }
        if (refunds.count() != beforeRefunds || !beforeAmount.equals(tx.execute(status -> payments.findByOrderId(payment.getOrderId()).getRefundedAmount()))) {
            throw new AssertionError("Reproduction must not add a refund or change the balance");
        }
        if (accepted && outbox.findAll().stream().anyMatch(row -> {
            try { return row.getAggregateId().equals(payment.getId()) && !mapper.readValue(row.getPayload(), PaymentRefundedPayload.class).equals(original); }
            catch (Exception ex) { throw new RuntimeException(ex); }
        })) throw new AssertionError("Original refund content unexpectedly changed");
        System.out.printf("REPRO case=%s state=%s accepted=%s marker_committed=%s refund_outbox_delta=%d balance_unchanged=true error=%s%n",
            label, tx.execute(status -> payments.findByOrderId(payment.getOrderId()).getStatus()), accepted, marker, delta,
            failure == null ? "none" : failure.getClass().getSimpleName());
    }

    public static void main(String[] args) throws Exception {
        var postgres = PaymentConcurrencyPostgresIntegrationTest.getPostgres();
        var manager = new TestContextManager(PaymentConcurrencyPostgresIntegrationTest.class);
        try {
            postgres.start();
            manager.beforeTestClass();
            var test = new PaymentConcurrencyPostgresIntegrationTest();
            manager.prepareTestInstance(test);
            var context = manager.getTestContext().getApplicationContext();
            service = context.getBean(PaymentService.class);
            payments = context.getBean(PaymentRepository.class);
            refunds = context.getBean(PaymentRefundRepository.class);
            outbox = context.getBean(OutboxEventRepository.class);
            markers = context.getBean(ProcessedEventRepository.class);
            mapper = context.getBean(ObjectMapper.class);
            tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            test.cleanUp();
            if (!AopUtils.isAopProxy(service)) throw new AssertionError("Actual Spring service proxy required");
            System.out.println("REPRO environment=isolated_PostgreSQL_16 actual_Spring_service_proxy=true actual_repositories=true V3_applied=true");
            for (boolean fullyRefunded : new boolean[] {false, true}) {
                var payment = payments.saveAndFlush(new Payment(UUID.randomUUID(), UUID.randomUUID(),
                    PaymentStatus.PAYMENT_COMPLETED, 20_000L, "[]", 0L, false, null, Instant.now()));
                var sellerOrder = UUID.randomUUID();
                var seller = UUID.randomUUID();
                long originalAmount = fullyRefunded ? 20_000L : 5_000L;
                service.handleOrderCanceled(message(payload(payment, payment.getUserId(), sellerOrder,
                    seller, originalAmount, "최초 취소"), "ORDER_CANCELED"));
                var original = mapper.readValue(outbox.findAll().stream()
                    .filter(row -> row.getAggregateId().equals(payment.getId())).findFirst().orElseThrow().getPayload(),
                    PaymentRefundedPayload.class);
                verifyCase("valid_reconciliation", payment, payload(payment, payment.getUserId(), sellerOrder,
                    seller, originalAmount, "SAGA_RECONCILIATION"), original, true);
                for (long amount : new long[] {0L, -1L, originalAmount - 1L, 20_001L, Long.MAX_VALUE, Long.MIN_VALUE}) {
                    verifyCase("amount_" + amount, payment, payload(payment, payment.getUserId(), sellerOrder,
                        seller, amount, "SAGA_RECONCILIATION"), original, fullyRefunded);
                }
                verifyCase("wrong_user", payment, payload(payment, UUID.randomUUID(), sellerOrder,
                    seller, originalAmount, "SAGA_RECONCILIATION"), original, fullyRefunded);
                verifyCase("wrong_seller", payment, payload(payment, payment.getUserId(), sellerOrder,
                    UUID.randomUUID(), originalAmount, "SAGA_RECONCILIATION"), original, fullyRefunded);
            }
            System.out.println("REPRO confirmed: invalid reconciliation is rejected after partial refund and committed after full refund; original facts and balances remain unchanged");
        } finally {
            try { manager.afterTestClass(); }
            finally { postgres.stop(); }
        }
    }
}
