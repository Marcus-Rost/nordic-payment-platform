package com.jrpay.fraud.casework.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.jrpay.fraud.casework.CaseworkIntegrationTestBase;
import com.jrpay.fraud.casework.audit.AdminAuditEvent;
import com.jrpay.fraud.casework.audit.AdminAuditPublisher;
import com.jrpay.fraud.casework.config.AuthenticatedUser;
import com.jrpay.fraud.casework.domain.StreamTokenEntity;
import com.jrpay.fraud.casework.domain.StreamTokenRepository;
import com.jrpay.fraud.casework.sca.StepUpValidator;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class StreamTokenServiceIntegrationTest extends CaseworkIntegrationTestBase {

    @Autowired StreamTokenService service;
    @Autowired StreamTokenRepository tokens;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean AuthenticatedUser caller;
    @MockitoBean AdminAuditPublisher audit;
    @MockitoBean StepUpValidator stepUpValidator;

    private final UUID operatorId = UUID.fromString("00000000-0000-0000-0000-0000a5050003");

    @BeforeEach
    void setup() {
        when(caller.requireUserId()).thenReturn(operatorId);
        when(caller.currentUserId()).thenReturn(Optional.of(operatorId));
        when(caller.hasScope(anyString())).thenReturn(true);
        when(audit.publish(any(AdminAuditEvent.class))).thenReturn("01HTESTSTREAM00000000XXXX");
        jdbc.execute("TRUNCATE TABLE stream_tokens CASCADE");
    }

    @Test
    void mintFraudTokenAndConsumeOnce() {
        var issued = service.mint(StreamTokenEntity.Scope.FRAUD, null);
        assertThat(issued.token()).isNotBlank();
        assertThat(service.consume(issued.token(), StreamTokenEntity.Scope.FRAUD))
                .isPresent();
        assertThat(service.consume(issued.token(), StreamTokenEntity.Scope.FRAUD))
                .isEmpty();
    }

    @Test
    void scopeMismatchIsRejected() {
        var issued = service.mint(StreamTokenEntity.Scope.FRAUD, null);
        assertThat(service.consume(issued.token(), StreamTokenEntity.Scope.SANCTIONS))
                .isEmpty();
    }

    @Test
    void resourceScopeIsRecorded() {
        UUID caseId = UUID.randomUUID();
        var issued = service.mint(StreamTokenEntity.Scope.FRAUD, caseId.toString());
        var consumed = service.consume(issued.token(), StreamTokenEntity.Scope.FRAUD)
                .orElseThrow();
        assertThat(consumed.getResourceId()).isEqualTo(caseId.toString());
    }
}
