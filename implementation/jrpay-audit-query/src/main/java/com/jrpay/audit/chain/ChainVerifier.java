package com.jrpay.audit.chain;

import com.jrpay.audit.domain.AuditCheckpoint;
import com.jrpay.audit.domain.AuditEvent;
import com.jrpay.audit.repository.AuditCheckpointRepository;
import com.jrpay.audit.repository.AuditEventRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recomputes hashes over a range of audit events and surfaces mismatches.
 * UC-G40 §6.3 — admin-initiated chain verification.
 */
@Service
public class ChainVerifier {

    private static final Logger log = LoggerFactory.getLogger(ChainVerifier.class);

    private final AuditEventRepository events;
    private final AuditCheckpointRepository checkpoints;
    private final ChainHasher hasher;
    private final int maxEvents;

    public ChainVerifier(AuditEventRepository events,
                         AuditCheckpointRepository checkpoints,
                         ChainHasher hasher,
                         @Value("${jrpay.audit.chain.verify-max-events:100000}") int maxEvents) {
        this.events = events;
        this.checkpoints = checkpoints;
        this.hasher = hasher;
        this.maxEvents = maxEvents;
    }

    @Transactional
    public Result verify(long fromOffset, long toOffset, String requestedBy) {
        if (fromOffset < 1) fromOffset = 1;
        if (toOffset < fromOffset) {
            throw new IllegalArgumentException("toOffset must be ≥ fromOffset");
        }
        if (toOffset - fromOffset + 1 > maxEvents) {
            throw new IllegalArgumentException(
                    "Range exceeds verify-max-events=" + maxEvents);
        }
        long t0 = System.currentTimeMillis();
        List<AuditEvent> rows = events.findRange(fromOffset, toOffset);
        if (rows.isEmpty()) {
            return new Result(true, fromOffset, toOffset, 0, 0L, List.of(), null);
        }
        // Seed prior hash from the row immediately before fromOffset (or null
        // when fromOffset = 1).
        String priorHash = null;
        if (fromOffset > 1) {
            priorHash = events.findByChainOffset(fromOffset - 1)
                    .map(AuditEvent::getChainHash)
                    .orElse(null);
        }

        List<Long> mismatches = new ArrayList<>();
        Result.MismatchDiff firstDiff = null;
        for (AuditEvent event : rows) {
            String expected = hasher.hash(event.getPayload(), priorHash, event.getChainOffset());
            if (!expected.equalsIgnoreCase(event.getChainHash())) {
                mismatches.add(event.getChainOffset());
                if (firstDiff == null) {
                    firstDiff = new Result.MismatchDiff(event.getChainOffset(),
                            expected, event.getChainHash());
                }
            }
            // Always advance using the STORED hash so a corrupt row only
            // invalidates itself and downstream when the storage contract is
            // "next.priorHash = previous.chainHash". This matches the
            // ingestion-side convention.
            priorHash = event.getChainHash();
        }
        long durationMs = System.currentTimeMillis() - t0;
        boolean verified = mismatches.isEmpty();

        if (verified) {
            // Commit a checkpoint covering this range so subsequent verifies
            // can short-circuit. UC-G40 §6.2 surfaces these.
            checkpoints.save(new AuditCheckpoint(
                    fromOffset, toOffset, rows.size(),
                    rows.get(rows.size() - 1).getChainHash(),
                    requestedBy == null ? "system:chain-verifier" : "operator:" + requestedBy));
        }
        log.info("chain verify range=[{},{}] events={} verified={} duration_ms={}",
                fromOffset, toOffset, rows.size(), verified, durationMs);
        return new Result(verified, fromOffset, toOffset, rows.size(), durationMs,
                mismatches, firstDiff);
    }

    /** Latest checkpoint or {@code null} when none exists. */
    public AuditCheckpoint latestCheckpoint() {
        var all = checkpoints.findAllByOrderByFromOffsetAsc();
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    public record Result(boolean verified,
                         long fromOffset,
                         long toOffset,
                         int eventCount,
                         long durationMs,
                         List<Long> mismatchOffsets,
                         MismatchDiff firstMismatch) {
        public Instant verifiedAt() { return Instant.now(); }

        public record MismatchDiff(long offset, String expectedHash, String actualHash) { }
    }
}
