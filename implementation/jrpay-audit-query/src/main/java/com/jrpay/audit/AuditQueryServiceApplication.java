package com.jrpay.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * JRPay Audit Query Service — backs {@code admin-audit.yaml} (admin search +
 * checkpoints + verify + export) and the {@code customer-privacy.yaml}
 * consent-log slice.
 *
 * <p>Hosts:
 * <ul>
 *   <li>Kafka projection of {@code audit.events} into a Postgres-indexed
 *       store with a SHA-256 hash chain (SEC-07 tamper evidence).
 *   <li>Search + filter + free-text endpoints with PII-masked rendering.
 *   <li>On-demand chain verification (UC-G40 §6.3) over arbitrary offset
 *       ranges, returning mismatch offsets + first-mismatch diff.
 *   <li>Async export jobs (CSV / signed-JSON) with single-use, signed
 *       download URLs per `SIGNED_URL_CONVENTIONS.md`.
 *   <li>Customer consent-log surface filtered by the caller's user-id.
 * </ul>
 */
@SpringBootApplication
@EnableKafka
@EnableAsync
@ConfigurationPropertiesScan("com.jrpay.audit")
public class AuditQueryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditQueryServiceApplication.class, args);
    }
}
