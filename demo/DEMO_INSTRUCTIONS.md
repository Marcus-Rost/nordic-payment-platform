# JRPay — Demo Instructions

## Prerequisites

- JDK 21 (installed via SDKMAN: `sdk install java 21.0.6-tem`)
- Gradle 8.12 (installed via SDKMAN: `sdk install gradle 8.12`)
- Docker + Docker Compose (for Kafka and PostgreSQL)
- ~4 GB free RAM (services use swap for additional memory)

## Step 1: Build the Project

```bash
cd implementation
source ~/.sdkman/bin/sdkman-init.sh
gradle build
```

This compiles all 6 services and runs 223 tests. Expected output: `BUILD SUCCESSFUL`.

## Step 2: Start Infrastructure

```bash
cd docker
docker compose up -d
```

This starts:
- **Kafka** (KRaft mode, single broker) on port 9092
- **PostgreSQL** (with ledger, clearing, billpay, psd2 databases) on port 5432

Verify with `docker ps` — both containers should show "healthy" or "Up".

### Create Kafka Topics

```bash
docker exec docker-kafka-1 bash -c '
  kafka-topics --create --if-not-exists --bootstrap-server kafka:29092 --topic payment.instant-instructions --partitions 3 --replication-factor 1
  kafka-topics --create --if-not-exists --bootstrap-server kafka:29092 --topic payment.balance-reservation --partitions 3 --replication-factor 1
  kafka-topics --create --if-not-exists --bootstrap-server kafka:29092 --topic payment.balance-reservation-response --partitions 3 --replication-factor 1
  kafka-topics --create --if-not-exists --bootstrap-server kafka:29092 --topic payment.instant-fraud-check-request --partitions 3 --replication-factor 1
  kafka-topics --create --if-not-exists --bootstrap-server kafka:29092 --topic payment.instant-fraud-check-response --partitions 3 --replication-factor 1
  kafka-topics --create --if-not-exists --bootstrap-server kafka:29092 --topic payment.instant-settlement-events --partitions 3 --replication-factor 1
  kafka-topics --create --if-not-exists --bootstrap-server kafka:29092 --topic payment.status-updates --partitions 3 --replication-factor 1
  kafka-topics --create --if-not-exists --bootstrap-server kafka:29092 --topic ledger.events --partitions 3 --replication-factor 1
  kafka-topics --create --if-not-exists --bootstrap-server kafka:29092 --topic audit.events --partitions 3 --replication-factor 1
'
```

## Step 3: Start Services

Start A1 (Account Ledger) and A6 (Verification of Payee). Each service takes 60-90 seconds to start on systems with limited RAM.

**A1 — Account Ledger (port 8091):**

```bash
java -Xmx256m -jar jrpay-account-ledger/build/libs/jrpay-account-ledger-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=docker \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/ledger \
  --spring.datasource.username=jrpay \
  --spring.datasource.password=jrpay \
  --spring.kafka.bootstrap-servers=localhost:9092
```

Verify: `curl http://localhost:9091/actuator/health` should return `{"status":"UP"}`.

**A6 — VoP Service (port 8096):**

```bash
java -Xmx256m -jar jrpay-vop-service/build/libs/jrpay-vop-service-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=docker \
  --spring.kafka.bootstrap-servers=localhost:9092
```

Verify: `curl http://localhost:9096/actuator/health` should return `{"status":"UP"}`.

## Step 4: Run the Demo

### Create Accounts

```bash
# Debtor account (Kari Hansen, 50,000 NOK)
curl -u admin:admin -X POST http://localhost:8091/api/v1/accounts \
  -H "Content-Type: application/json" \
  -d '{"accountId":"NO9386011117947","holderName":"Kari Hansen","currency":"NOK","initialBalance":50000.00}'

# Creditor account (Ola Nordmann AS, 10,000 NOK)
curl -u admin:admin -X POST http://localhost:8091/api/v1/accounts \
  -H "Content-Type: application/json" \
  -d '{"accountId":"NO1234567890123","holderName":"Ola Nordmann AS","currency":"NOK","initialBalance":10000.00}'
```

### Verify Beneficiary (VoP)

```bash
# Exact match
curl -u admin:admin -X POST http://localhost:8096/api/v1/vop/verify \
  -H "Content-Type: application/json" \
  -d '{"beneficiaryIban":"NO1234567890123","statedName":"Ola Nordmann AS","requestId":"demo-001"}'

# Privacy test: wrong name — actual name NEVER revealed
curl -u admin:admin -X POST http://localhost:8096/api/v1/vop/verify \
  -H "Content-Type: application/json" \
  -d '{"beneficiaryIban":"NO1234567890123","statedName":"Wrong Person","requestId":"demo-002"}'
```

### Execute Payment

```bash
curl -u admin:admin -X POST http://localhost:8091/api/v1/accounts/transfer \
  -H "Content-Type: application/json" \
  -d '{"transactionId":"PAY-001","debtorAccountId":"NO9386011117947","creditorAccountId":"NO1234567890123","amount":5000.00,"currency":"NOK","description":"April electricity"}'
```

### Verify Balances After Payment

```bash
curl -u admin:admin http://localhost:8091/api/v1/accounts/NO9386011117947/balances
# Expected: bookedBalance: 45000.0

curl -u admin:admin http://localhost:8091/api/v1/accounts/NO1234567890123/balances
# Expected: bookedBalance: 15000.0
```

## Step 5: Shut Down

```bash
# Stop services (Ctrl+C in each terminal, or)
pkill -f "AccountLedgerApplication"
pkill -f "VoPServiceApplication"

# Stop infrastructure
cd docker && docker compose down
```

## Authentication

All API endpoints require HTTP Basic authentication. Demo credentials:
- **admin / admin** — full access
- **a3-service / a3-service** — service account for A3
- **a6-service / a6-service** — service account for A6

Unauthenticated requests receive HTTP 401.

## What the Demo Shows

1. **Account management** — create accounts with initial balances in NOK
2. **Verification of Payee** — A6 verifies beneficiary names against A1 account data with fuzzy matching (Jaro-Winkler)
3. **Privacy enforcement** — actual name NEVER revealed on NO_MATCH
4. **Payment execution** — atomic double-entry (debit + credit), conservation of money verified
5. **Security** — all endpoints require authentication, OWASP security headers on every response
6. **Live infrastructure** — Kafka + PostgreSQL running via Docker, Flyway-managed schema
