package dev.queuedesk.risk;

import dev.queuedesk.domain.StoredInstruction;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;

// Builds frozen feature snapshots from durable state (RISK-01). Source queries
// hit `instruction` (sender/amount/created_at) only; the dedup truth in
// `processed_message` needs no join because retries/redeliveries never create
// instruction rows. History scopes to the instruction's run_id: each run is an
// independent synthetic actor history, so held-out suites (RS01-RS06) cannot
// leak velocity across their disjoint seed sets. The as-of instant freezes at
// extraction time.
@Component
public class FeatureExtractor {
    private final JdbcClient jdbc;
    private final RiskProperties properties;

    public FeatureExtractor(JdbcClient jdbc, RiskProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public FeatureSnapshot extract(StoredInstruction instruction, Instant asOf) {
        String sender = instruction.payload().senderParticipant();
        String receiver = instruction.payload().receiverParticipant();
        String runId = instruction.payload().runId();
        int window = properties.velocityWindowMinutes();
        // Prior UNIQUE instructions from this sender: strictly older than this
        // instruction by (created_at, instruction_id) inside the window, oldest
        // first. The history cutoff is the instruction's own admission instant
        // — never the evaluation instant — so features cannot see instructions
        // admitted later but evaluated earlier (no future leak), and identical
        // (seed, count) fixtures always compute identical features regardless
        // of worker timing. asOf still freezes at evaluation for audit.
        Instant admitted = instruction.receivedAt();
        Instant cutoff = admitted.minusSeconds((long) window * 60L);
        List<Prior> priors = jdbc.sql("""
                SELECT amount_minor, receiver_participant, created_at
                  FROM instruction
                 WHERE sender_participant = ?
                   AND run_id = ?
                   AND created_at >= ?
                   AND (created_at, instruction_id) < (?, ?)
                 ORDER BY created_at ASC, instruction_id ASC""")
                .params(sender, runId, java.time.OffsetDateTime.ofInstant(cutoff, java.time.ZoneOffset.UTC),
                        java.time.OffsetDateTime.ofInstant(admitted, java.time.ZoneOffset.UTC),
                        instruction.instructionId())
                .query((rs, row) -> new Prior(rs.getLong("amount_minor"),
                        rs.getString("receiver_participant"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant()))
                .list();
        boolean receiverSeenBefore = priors.stream().anyMatch(p -> p.receiver().equals(receiver));
        Long elapsed = null;
        boolean missingElapsed = true;
        if (!priors.isEmpty()) {
            elapsed = Math.max(0, asOf.toEpochMilli() - priors.get(priors.size() - 1).createdAt().toEpochMilli());
            missingElapsed = false;
        }
        double ratio = FeatureSnapshot.NO_BASELINE;
        boolean missingBaseline = true;
        if (!priors.isEmpty()) {
            long median = median(priors.stream().map(Prior::amountMinor).sorted().toList());
            ratio = median <= 0 ? Double.POSITIVE_INFINITY : (double) instruction.payload().amountMinor() / median;
            missingBaseline = false;
        }
        return new FeatureSnapshot(instruction.payload().amountMinor(), priors.size(), elapsed,
                receiverSeenBefore, ratio, asOf, properties.featureVersion(), missingElapsed, missingBaseline);
    }

    private record Prior(long amountMinor, String receiver, Instant createdAt) {}

    private static long median(List<Long> sorted) {
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }
}
