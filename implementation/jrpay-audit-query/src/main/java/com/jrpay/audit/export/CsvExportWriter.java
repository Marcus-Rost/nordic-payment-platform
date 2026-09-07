package com.jrpay.audit.export;

import com.jrpay.audit.domain.AuditEvent;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Writes a flat CSV view of the audit-event projection. Columns are
 * deliberately stable + alphabetised within each logical group so that
 * external tooling (Excel, awk pipelines) does not need to chase header
 * order across releases.
 *
 * <p>RFC 4180 quoting: every cell is wrapped in double quotes; embedded
 * quotes are doubled. UTF-8 BOM prepended so Excel renders accents
 * correctly without manual import settings.
 */
public final class CsvExportWriter {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final String[] HEADERS = {
            "chainOffset", "eventId", "occurredAt", "type", "actor", "actorKind",
            "outcome", "severity", "summary",
            "resourceType", "resourceId",
            "correlationId", "customerUserId", "operatorUserId",
            "consentId", "paymentId", "caseId",
            "priorHash", "chainHash"
    };

    private CsvExportWriter() {
    }

    public static byte[] render(List<AuditEvent> events) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            baos.write(UTF8_BOM);
            try (Writer w = new OutputStreamWriter(baos, StandardCharsets.UTF_8)) {
                writeHeader(w);
                for (AuditEvent e : events) {
                    writeRow(w, e);
                }
                w.flush();
            }
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("CSV export failed", e);
        }
    }

    private static void writeHeader(Writer w) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < HEADERS.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(quote(HEADERS[i]));
        }
        sb.append("\r\n");
        w.write(sb.toString());
    }

    private static void writeRow(Writer w, AuditEvent e) throws Exception {
        String[] cells = new String[]{
                str(e.getChainOffset()),
                str(e.getEventId()),
                fmt(e.getOccurredAt()),
                str(e.getType()),
                str(e.getActor()),
                str(e.getActorKind()),
                str(e.getOutcome()),
                str(e.getSeverity()),
                str(e.getSummary()),
                str(e.getResourceType()),
                str(e.getResourceId()),
                str(e.getCorrelationId()),
                str(e.getCustomerUserId()),
                str(e.getOperatorUserId()),
                str(e.getConsentId()),
                str(e.getPaymentId()),
                str(e.getCaseId()),
                str(e.getPriorHash()),
                str(e.getChainHash())
        };
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(quote(cells[i]));
        }
        sb.append("\r\n");
        w.write(sb.toString());
    }

    private static String quote(String value) {
        String safe = value == null ? "" : value.replace("\"", "\"\"");
        return "\"" + safe + "\"";
    }

    private static String str(Object value) {
        return value == null ? "" : Objects.toString(value);
    }

    private static String fmt(OffsetDateTime t) {
        return t == null ? "" : t.toString();
    }
}
