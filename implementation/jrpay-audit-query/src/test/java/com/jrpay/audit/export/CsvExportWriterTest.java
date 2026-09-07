package com.jrpay.audit.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.jrpay.audit.domain.AuditEvent;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvExportWriterTest {

    @Test
    void emitsHeaderAndRow() {
        AuditEvent e = AuditEvent.builder()
                .eventId("01H...")
                .occurredAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"))
                .type("login.succeeded")
                .actor("customer:42")
                .actorKind(AuditEvent.ActorKind.customer)
                .outcome(AuditEvent.Outcome.SUCCESS)
                .severity(AuditEvent.Severity.INFO)
                .summary("login OK")
                .priorHash(null)
                .chainHash("a".repeat(64))
                .kafkaTopic("audit.events")
                .kafkaPartition(0)
                .kafkaOffset(1L)
                .build();

        String csv = new String(CsvExportWriter.render(List.of(e)), StandardCharsets.UTF_8);
        // Strip BOM for assertions.
        if (csv.startsWith("﻿")) csv = csv.substring(1);

        String[] lines = csv.split("\r\n");
        assertThat(lines).hasSize(2);
        assertThat(lines[0]).startsWith("\"chainOffset\",\"eventId\",\"occurredAt\"");
        assertThat(lines[1]).contains("\"01H...\"")
                .contains("\"login.succeeded\"")
                .contains("\"customer:42\"")
                .contains("\"SUCCESS\"")
                .contains("\"INFO\"")
                .contains("\"login OK\"");
    }

    @Test
    void escapesQuotesAndCommas() {
        AuditEvent e = AuditEvent.builder()
                .eventId("01H")
                .occurredAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"))
                .type("test")
                .actor("system:test")
                .actorKind(AuditEvent.ActorKind.system)
                .outcome(AuditEvent.Outcome.SUCCESS)
                .severity(AuditEvent.Severity.INFO)
                .summary("hello, \"world\"")
                .priorHash(null)
                .chainHash("a".repeat(64))
                .kafkaTopic("audit.events")
                .kafkaPartition(0)
                .kafkaOffset(1L)
                .build();

        String csv = new String(CsvExportWriter.render(List.of(e)), StandardCharsets.UTF_8);
        assertThat(csv).contains("\"hello, \"\"world\"\"\"");
    }

    @Test
    void prependsUtf8Bom() {
        byte[] bytes = CsvExportWriter.render(List.of());
        assertThat(bytes).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
    }
}
