-- Create databases for each service (only if they don't exist)
-- PostgreSQL doesn't support CREATE DATABASE IF NOT EXISTS directly,
-- so we use conditional execution with \gexec

SELECT 'CREATE DATABASE order_service'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'order_service')\gexec

SELECT 'CREATE DATABASE payment_service'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'payment_service')\gexec

SELECT 'CREATE DATABASE shipping_service'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'shipping_service')\gexec

SELECT 'CREATE DATABASE notification_service'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'notification_service')\gexec

SELECT 'CREATE DATABASE user_service'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'user_service')\gexec

-- Grant privileges (these are idempotent - safe to run multiple times)
\c order_service
GRANT ALL PRIVILEGES ON DATABASE order_service TO postgres;
GRANT ALL PRIVILEGES ON SCHEMA public TO postgres;

\c payment_service
GRANT ALL PRIVILEGES ON DATABASE payment_service TO postgres;
GRANT ALL PRIVILEGES ON SCHEMA public TO postgres;

\c shipping_service
GRANT ALL PRIVILEGES ON DATABASE shipping_service TO postgres;
GRANT ALL PRIVILEGES ON SCHEMA public TO postgres;

\c notification_service
GRANT ALL PRIVILEGES ON DATABASE notification_service TO postgres;
GRANT ALL PRIVILEGES ON SCHEMA public TO postgres;

\c user_service
GRANT ALL PRIVILEGES ON DATABASE user_service TO postgres;
GRANT ALL PRIVILEGES ON SCHEMA public TO postgres;
