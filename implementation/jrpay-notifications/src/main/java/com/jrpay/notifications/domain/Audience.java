package com.jrpay.notifications.domain;

/**
 * Mirrors {@code shared-schemas.yaml#/components/schemas/...} audience scope.
 * Customers receive {@code customer-notifications.yaml} surfaces; admins the
 * admin-fraud / admin-sanctions / admin-payments queues.
 */
public enum Audience {
    customer, admin
}
