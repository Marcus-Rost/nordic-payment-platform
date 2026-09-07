package com.jrpay.starter.audit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configures the audit logger for all services.
 * If the Kafka module provides an AuditEventPublisher, it is used.
 * Otherwise, audit events are logged locally only (no-op publisher).
 */
@AutoConfiguration
public class AuditAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AuditEventPublisher.class)
    public AuditEventPublisher noOpPublisher() {
        return event -> {
            // No Kafka module on classpath — audit events logged locally only
        };
    }

    @Bean
    @ConditionalOnMissingBean(AuditLogger.class)
    public AuditLogger auditLogger(
            @Value("${spring.application.name:unknown}") String serviceName,
            @Autowired AuditEventPublisher publisher) {
        return new AuditLogger(serviceName, publisher);
    }
}
