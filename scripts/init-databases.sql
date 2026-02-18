-- Create databases for each service
CREATE DATABASE order_service;
CREATE DATABASE payment_service;
CREATE DATABASE shipping_service;
CREATE DATABASE notification_service;

-- Grant privileges
GRANT ALL PRIVILEGES ON DATABASE order_service TO postgres;
GRANT ALL PRIVILEGES ON DATABASE payment_service TO postgres;
GRANT ALL PRIVILEGES ON DATABASE shipping_service TO postgres;
GRANT ALL PRIVILEGES ON DATABASE notification_service TO postgres;

-- Initialize Redis stock (will be done via Redis CLI or application)
-- redis-cli SET stock:default 100
