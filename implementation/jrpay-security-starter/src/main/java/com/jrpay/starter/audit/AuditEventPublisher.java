package com.jrpay.starter.audit;

import java.util.Map;

/**
 * Interface for publishing audit events to an external system (Kafka).
 * Implemented by jrpay-security-starter-kafka module.
 * If no implementation is on the classpath, audit events are logged locally only.
 */
public interface AuditEventPublisher {

    void publish(Map<String, Object> event);
}
