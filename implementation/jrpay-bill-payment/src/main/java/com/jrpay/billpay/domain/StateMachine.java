package com.jrpay.billpay.domain;

import java.util.Map;
import java.util.Set;

/**
 * Enum-based state machine with static transition table.
 * All valid transitions are declared here — anything not in the table is illegal.
 * This is the architectural decision: enum table + CAS SQL + DB CHECK constraint.
 */
public final class StateMachine {

    private static final Map<RequestStatus, Map<RequestTrigger, RequestStatus>> TRANSITIONS = Map.ofEntries(
            Map.entry(RequestStatus.PENDING, Map.of(
                    RequestTrigger.CONSUMER_ACCEPT, RequestStatus.ACCEPTED,
                    RequestTrigger.CONSUMER_DECLINE, RequestStatus.DECLINED,
                    RequestTrigger.CONSUMER_DEFER, RequestStatus.DEFERRED,
                    RequestTrigger.CONSUMER_AMEND, RequestStatus.AMENDMENT_REQUESTED,
                    RequestTrigger.MANDATE_AUTO_ACCEPT, RequestStatus.ACCEPTED,
                    RequestTrigger.TIMER_EXPIRED, RequestStatus.EXPIRED
            )),
            Map.entry(RequestStatus.AMENDMENT_REQUESTED, Map.of(
                    RequestTrigger.MERCHANT_ACCEPT_AMENDMENT, RequestStatus.REPLACED,
                    RequestTrigger.MERCHANT_REJECT_AMENDMENT, RequestStatus.PENDING
            )),
            Map.entry(RequestStatus.ACCEPTED, Map.of(
                    RequestTrigger.PAYMENT_INITIATED, RequestStatus.PAYMENT_INITIATED
            )),
            Map.entry(RequestStatus.DEFERRED, Map.of(
                    RequestTrigger.TIMER_EXPIRED, RequestStatus.PENDING // reactivation
            )),
            Map.entry(RequestStatus.AMENDED, Map.of(
                    RequestTrigger.MERCHANT_ACCEPT_AMENDMENT, RequestStatus.PENDING,
                    RequestTrigger.MERCHANT_REJECT_AMENDMENT, RequestStatus.PENDING
            )),
            Map.entry(RequestStatus.PAYMENT_INITIATED, Map.of(
                    RequestTrigger.KAFKA_SETTLED, RequestStatus.SETTLED,
                    RequestTrigger.KAFKA_REJECTED, RequestStatus.REJECTED,
                    RequestTrigger.KAFKA_HELD, RequestStatus.PAYMENT_HELD
            )),
            Map.entry(RequestStatus.REJECTED, Map.of(
                    // REJECTED is semi-terminal — payer can retry, but this request is done
            )),
            Map.entry(RequestStatus.SETTLED, Map.of(
                    RequestTrigger.KAFKA_RETURN_INITIATED, RequestStatus.RETURN_IN_PROGRESS
            )),
            Map.entry(RequestStatus.RETURN_IN_PROGRESS, Map.of(
                    RequestTrigger.KAFKA_RETURNED, RequestStatus.PAYMENT_RETURNED
            )),
            Map.entry(RequestStatus.PAYMENT_HELD, Map.of(
                    RequestTrigger.KAFKA_SETTLED, RequestStatus.SETTLED,
                    RequestTrigger.KAFKA_REJECTED, RequestStatus.REJECTED
            ))
    );

    private StateMachine() {}

    /**
     * Attempt a state transition. Returns the new state if valid.
     *
     * @throws IllegalStateException if the transition is not allowed
     */
    public static RequestStatus transition(RequestStatus current, RequestTrigger trigger) {
        Map<RequestTrigger, RequestStatus> allowed = TRANSITIONS.get(current);
        if (allowed == null || !allowed.containsKey(trigger)) {
            throw new IllegalStateException(
                    "Invalid transition: " + current + " + " + trigger);
        }
        return allowed.get(trigger);
    }

    /**
     * Check if a transition is valid without throwing.
     */
    public static boolean isValidTransition(RequestStatus current, RequestTrigger trigger) {
        Map<RequestTrigger, RequestStatus> allowed = TRANSITIONS.get(current);
        return allowed != null && allowed.containsKey(trigger);
    }

    /**
     * Get all valid triggers for the current state.
     */
    public static Set<RequestTrigger> validTriggers(RequestStatus current) {
        Map<RequestTrigger, RequestStatus> allowed = TRANSITIONS.get(current);
        return allowed != null ? allowed.keySet() : Set.of();
    }
}
