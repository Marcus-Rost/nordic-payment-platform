-- Initialize separate databases for each service (database-per-service pattern)
-- Single PostgreSQL instance, multiple databases — saves RAM in development

CREATE DATABASE ledger;
CREATE DATABASE clearing;
CREATE DATABASE billpay;
CREATE DATABASE psd2;
CREATE DATABASE identity;
CREATE DATABASE notifications;
CREATE DATABASE audit;
CREATE DATABASE casework;

-- Each service connects with its own credentials
-- In production, these would be separate PostgreSQL instances with distinct credentials
GRANT ALL PRIVILEGES ON DATABASE ledger TO jrpay;
GRANT ALL PRIVILEGES ON DATABASE clearing TO jrpay;
GRANT ALL PRIVILEGES ON DATABASE billpay TO jrpay;
GRANT ALL PRIVILEGES ON DATABASE psd2 TO jrpay;
GRANT ALL PRIVILEGES ON DATABASE identity TO jrpay;
GRANT ALL PRIVILEGES ON DATABASE notifications TO jrpay;
GRANT ALL PRIVILEGES ON DATABASE audit TO jrpay;
GRANT ALL PRIVILEGES ON DATABASE casework TO jrpay;
