package com.jrpay.psd2.customer.payments.vop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jrpay.psd2.customer.CustomerProblemException;
import com.jrpay.psd2.customer.audit.CustomerAuditEvent;
import com.jrpay.psd2.customer.audit.CustomerAuditPublisher;
import com.jrpay.psd2.customer.config.AuthenticatedUser;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VoPServiceTest {

    private VoPClient client;
    private AuthenticatedUser caller;
    private CustomerAuditPublisher audit;
    private VoPService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        client = mock(VoPClient.class);
        caller = mock(AuthenticatedUser.class);
        audit = mock(CustomerAuditPublisher.class);
        when(caller.requireUserId()).thenReturn(userId);
        when(caller.currentUserId()).thenReturn(Optional.of(userId));
        when(caller.currentSubject()).thenReturn("customer:" + userId);
        when(audit.publish(any(CustomerAuditEvent.class))).thenReturn("evt");
        when(client.check(anyString(), anyString())).thenReturn(new VoPClient.Result("MATCH", null));
        service = new VoPService(client, caller, audit, 3, 60);
    }

    @Test
    void invalidIbanRejected() {
        assertThatThrownBy(() -> service.check("NO0000000000000", "Ola"))
                .isInstanceOfSatisfying(CustomerProblemException.class, e ->
                        assertThat(e.getType()).contains("invalid-iban"));
    }

    @Test
    void rateLimitTriggersAfterMaxPerWindow() {
        for (int i = 0; i < 3; i++) {
            assertThat(service.check("NO9386011117947", "Ola").result()).isEqualTo("MATCH");
        }
        assertThatThrownBy(() -> service.check("NO9386011117947", "Ola"))
                .isInstanceOfSatisfying(CustomerProblemException.class, e ->
                        assertThat(e.getType()).contains("rate-limited"));
    }

    @Test
    void resultPropagatedFromClient() {
        when(client.check(anyString(), anyString())).thenReturn(new VoPClient.Result("CLOSE_MATCH", "Ola Nordmann"));
        var r = service.check("NO9386011117947", "ola n");
        assertThat(r.result()).isEqualTo("CLOSE_MATCH");
        assertThat(r.actualName()).isEqualTo("Ola Nordmann");
    }
}
