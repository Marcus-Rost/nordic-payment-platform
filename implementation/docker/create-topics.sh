#!/bin/sh
# Create all Kafka topics for the JRPay platform.
# Idempotent: --if-not-exists guards repeated runs.
#
# Backend topics: per architecture/INTERFACE_CONTRACTS.md.
# GUI-tier additions: per gui/architecture/ARCHITECTURE_DESIGN.md §7.8.

set -e
KAFKA_BROKER="${KAFKA_BOOTSTRAP_SERVERS:-kafka:29092}"

echo "Waiting for Kafka at $KAFKA_BROKER ..."
until kafka-broker-api-versions --bootstrap-server "$KAFKA_BROKER" >/dev/null 2>&1; do
    sleep 2
done

create() {
    topic=$1
    partitions=${2:-3}
    kafka-topics --create --if-not-exists \
        --bootstrap-server "$KAFKA_BROKER" \
        --topic "$topic" \
        --partitions "$partitions" \
        --replication-factor 1
}

echo "Creating backend topics ..."

# Payment instruction topics (A3/A4 → A2)
create payment.instructions
create payment.instant-instructions

# Balance reservation (A2 ↔ A1)
create payment.balance-reservation
create payment.balance-reservation-response

# Fraud check (A2 ↔ A5)
create payment.fraud-check-request
create payment.instant-fraud-check-request
create payment.fraud-check-response
create payment.instant-fraud-check-response

# Settlement (A2 → A1)
create payment.settlement-events
create payment.instant-settlement-events

# Status updates (A2 → A3/A4/notifications)
create payment.status-updates

# Account operations
create account.freeze-commands
create ledger.events

# Audit
create audit.events
create audit.checkpoints

# Operational
create payment.investigation 1
create payment.reconciliation-results 1

echo "Creating GUI-tier topics (Phase 2 additions) ..."

# Notifications
create notifications.outbound

# R2P + mandate flows
create r2p.arrived
create consent.expiring 1
create gdpr.status-changed 1

# Status banner + CSP
create status.events 1
create csp.violation 1

# Customer onboarding binding
create account.customer-registered 1

# PII reveal forensic stream (5-yr retention in production; default here)
create audit.pii-revealed

# Fast revocation propagation
create session.revoked 1

# A5 case management
create sanctions.hits

echo "All topics created. Listing:"
kafka-topics --list --bootstrap-server "$KAFKA_BROKER"
