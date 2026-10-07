rootProject.name = "eventful-commerce"

include(
    "common-outbox",
    "common-idempotency",
    "common-auth",
    "api-gateway",
    "product-service",
    "settlement-service",
    "order-service",
    "payment-service",

    "shipping-service",
    "notification-service",
    "user-service",

)