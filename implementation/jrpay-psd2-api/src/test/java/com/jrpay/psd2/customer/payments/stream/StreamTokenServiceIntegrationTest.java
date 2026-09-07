package com.jrpay.psd2.customer.payments.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.jrpay.psd2.customer.Psd2CustomerIntegrationTestBase;
import com.jrpay.psd2.customer.audit.CustomerAuditEvent;
import com.jrpay.psd2.customer.audit.CustomerAuditPublisher;
import com.jrpay.psd2.customer.config.AuthenticatedUser;
import com.jrpay.psd2.customer.domain.StreamTokenRepository;
import com.jrpay.psd2.customer.payments.vop.VoPClient;
import com.jrpay.psd2.customer.sca.ScaInitiator;
import com.jrpay.psd2.customer.sca.StepUpValidator;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class StreamTokenServiceIntegrationTest extends Psd2CustomerIntegrationTestBase {

    @Autowired StreamTokenService service;
    @Autowired StreamTokenRepository tokens;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean AuthenticatedUser caller;
    @MockitoBean CustomerAuditPublisher audit;
    @MockitoBean VoPClient vopClient;
    @MockitoBean ScaInitiator scaInitiator;
    @MockitoBean StepUpValidator stepUpValidator;

    private final UUID userId = UUID.fromString("00000000-0000-0000-0000-0000c0440001");

    @BeforeEach
    void setup() {
        when(caller.requireUserId()).thenReturn(userId);
        when(caller.currentUserId()).thenReturn(Optional.of(userId));
        when(caller.hasScope(anyString())).thenReturn(true);
        when(audit.publish(any(CustomerAuditEvent.class))).thenReturn("01HTESTSTRM00000000000XXX");
        jdbc.execute("TRUNCATE TABLE stream_tokens CASCADE");
    }

    @Test
    void mintReturnsTokenWith300SecondTtl() {
        var issued = service.mint(null);
        assertThat(issued.token()).isNotBlank();
        assertThat(issued.expiresInSeconds()).isEqualTo(300L);
        assertThat(tokens.count()).isEqualTo(1L);
    }

    @Test
    void consumeReturnsBoundUserAndMarksConsumed() {
        var issued = service.mint(null);
        Optional<UUID> result = service.consume(issued.token());
        assertThat(result).contains(userId);
        // Second consume fails (single-use).
        assertThat(service.consume(issued.token())).isEmpty();
    }

    @Test
    void consumeRejectsUnknownToken() {
        assertThat(service.consume("notatoken")).isEmpty();
    }

    @Test
    void mintWithPaymentScopeRecordsScope() {
        var issued = service.mint("pay-1");
        assertThat(service.peekScope(issued.token())).contains("pay-1");
    }
}
