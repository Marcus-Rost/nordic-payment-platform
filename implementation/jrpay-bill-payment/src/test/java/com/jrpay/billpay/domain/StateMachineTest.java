package com.jrpay.billpay.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the R2P lifecycle state machine.
 */
class StateMachineTest {

    @Test
    @DisplayName("PENDING → ACCEPTED on CONSUMER_ACCEPT")
    void pendingToAccepted() {
        assertEquals(RequestStatus.ACCEPTED,
                StateMachine.transition(RequestStatus.PENDING, RequestTrigger.CONSUMER_ACCEPT));
    }

    @Test
    @DisplayName("PENDING → DECLINED on CONSUMER_DECLINE")
    void pendingToDeclined() {
        assertEquals(RequestStatus.DECLINED,
                StateMachine.transition(RequestStatus.PENDING, RequestTrigger.CONSUMER_DECLINE));
    }

    @Test
    @DisplayName("PENDING → DEFERRED on CONSUMER_DEFER")
    void pendingToDeferred() {
        assertEquals(RequestStatus.DEFERRED,
                StateMachine.transition(RequestStatus.PENDING, RequestTrigger.CONSUMER_DEFER));
    }

    @Test
    @DisplayName("PENDING → AMENDMENT_REQUESTED on CONSUMER_AMEND")
    void pendingToAmendmentRequested() {
        assertEquals(RequestStatus.AMENDMENT_REQUESTED,
                StateMachine.transition(RequestStatus.PENDING, RequestTrigger.CONSUMER_AMEND));
    }

    @Test
    @DisplayName("PENDING → ACCEPTED on MANDATE_AUTO_ACCEPT")
    void pendingToAutoAccepted() {
        assertEquals(RequestStatus.ACCEPTED,
                StateMachine.transition(RequestStatus.PENDING, RequestTrigger.MANDATE_AUTO_ACCEPT));
    }

    @Test
    @DisplayName("PENDING → EXPIRED on TIMER_EXPIRED")
    void pendingToExpired() {
        assertEquals(RequestStatus.EXPIRED,
                StateMachine.transition(RequestStatus.PENDING, RequestTrigger.TIMER_EXPIRED));
    }

    @Test
    @DisplayName("ACCEPTED → PAYMENT_INITIATED on PAYMENT_INITIATED")
    void acceptedToInitiated() {
        assertEquals(RequestStatus.PAYMENT_INITIATED,
                StateMachine.transition(RequestStatus.ACCEPTED, RequestTrigger.PAYMENT_INITIATED));
    }

    @Test
    @DisplayName("PAYMENT_INITIATED → SETTLED on KAFKA_SETTLED")
    void initiatedToSettled() {
        assertEquals(RequestStatus.SETTLED,
                StateMachine.transition(RequestStatus.PAYMENT_INITIATED, RequestTrigger.KAFKA_SETTLED));
    }

    @Test
    @DisplayName("PAYMENT_INITIATED → REJECTED on KAFKA_REJECTED")
    void initiatedToRejected() {
        assertEquals(RequestStatus.REJECTED,
                StateMachine.transition(RequestStatus.PAYMENT_INITIATED, RequestTrigger.KAFKA_REJECTED));
    }

    @Test
    @DisplayName("PAYMENT_INITIATED → PAYMENT_HELD on KAFKA_HELD")
    void initiatedToHeld() {
        assertEquals(RequestStatus.PAYMENT_HELD,
                StateMachine.transition(RequestStatus.PAYMENT_INITIATED, RequestTrigger.KAFKA_HELD));
    }

    @Test
    @DisplayName("SETTLED → RETURN_IN_PROGRESS on KAFKA_RETURN_INITIATED")
    void settledToReturnInProgress() {
        assertEquals(RequestStatus.RETURN_IN_PROGRESS,
                StateMachine.transition(RequestStatus.SETTLED, RequestTrigger.KAFKA_RETURN_INITIATED));
    }

    @Test
    @DisplayName("RETURN_IN_PROGRESS → PAYMENT_RETURNED on KAFKA_RETURNED")
    void returnInProgressToReturned() {
        assertEquals(RequestStatus.PAYMENT_RETURNED,
                StateMachine.transition(RequestStatus.RETURN_IN_PROGRESS, RequestTrigger.KAFKA_RETURNED));
    }

    @Test
    @DisplayName("AMENDED → PENDING on MERCHANT_ACCEPT_AMENDMENT")
    void amendedToPending() {
        assertEquals(RequestStatus.PENDING,
                StateMachine.transition(RequestStatus.AMENDED, RequestTrigger.MERCHANT_ACCEPT_AMENDMENT));
    }

    @Test
    @DisplayName("DEFERRED → PENDING on TIMER_EXPIRED (reactivation)")
    void deferredToPending() {
        assertEquals(RequestStatus.PENDING,
                StateMachine.transition(RequestStatus.DEFERRED, RequestTrigger.TIMER_EXPIRED));
    }

    @Test
    @DisplayName("Invalid transition → IllegalStateException")
    void invalidTransition() {
        assertThrows(IllegalStateException.class,
                () -> StateMachine.transition(RequestStatus.DECLINED, RequestTrigger.CONSUMER_ACCEPT));
    }

    @Test
    @DisplayName("SETTLED cannot be directly rejected")
    void settledCannotBeRejected() {
        assertFalse(StateMachine.isValidTransition(RequestStatus.SETTLED, RequestTrigger.KAFKA_REJECTED));
    }

    @Test
    @DisplayName("DECLINED is terminal — no valid triggers")
    void declinedIsTerminal() {
        assertTrue(StateMachine.validTriggers(RequestStatus.DECLINED).isEmpty());
    }
}
