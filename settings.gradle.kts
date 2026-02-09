rootProject.name = "eventful-commerce"

include(
    "common-outbox",
    "common-idempotency",
    "order-service",
    "payment-service",
//    "inventory-service",
    "shipping-service",
    "notification-service",
//    "integration-tests",
)