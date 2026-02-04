package com.portfolio.ecosystem.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;

/**
 * Consumes CDC events from Kafka that Debezium published from Postgres WAL.
 *
 * Debezium wraps every row change in a standard envelope:
 * { "payload": { "op": "c|u|d|r", "before": {...}, "after": {...} } }
 *
 * We only care about INSERT ("c") and UPDATE ("u") ops on the transactions
 * table, and we write a single human-readable line per event to the audit file.
 *
 * The file is appended to in real time - no batching, no buffering. This
 * trades throughput for simplicity; fine for our volume.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AuditLogListener {

    private final ObjectMapper objectMapper;

    @Value("${audit.log.directory}")
    private String logDir;

    @Value("${audit.log.filename}")
    private String logFilename;

    @KafkaListener(topics = "ledger-server.public.transactions", groupId = "${spring.kafka.consumer.group-id}")
    public void consumeTransactionEvent(String rawMessage) {
        try {
            JsonNode root = objectMapper.readTree(rawMessage);
            JsonNode payload = root.path("payload");

            if (payload.isMissingNode() || payload.isNull()) {
                log.debug("Received message with no payload - skipping");
                return;
            }

            String op = payload.path("op").asText();

            // "r" = snapshot read during connector startup - not interesting
            if ("r".equals(op)) {
                return;
            }

            JsonNode after = payload.path("after");
            if (after.isMissingNode() || after.isNull()) {
                return;
            }

            // Format consistent with what our compliance team asked for
            String entry = String.format("[%s] OP:%s id:%s account:%s amount:%s status:%s key:%s",
                    LocalDateTime.now(),
                    op,
                    after.path("id").asText("-"),
                    after.path("account_id").asText("-"),
                    after.path("amount").asText("-"),
                    after.path("status").asText("-"),
                    after.path("ide_key").asText("-"));

            writeAuditEntry(entry);

        } catch (Exception e) {
            // Swallow parse errors so a bad message doesn't block the partition.
            // The raw message is in the consumer group offset so ops can replay if needed.
            log.error("Failed to parse Debezium event, skipping: {}", e.getMessage());
        }
    }

    private void writeAuditEntry(String entry) {
        File dir = new File(logDir);
        if (!dir.exists() && !dir.mkdirs()) {
            log.error("Could not create audit log directory at {}", logDir);
            return;
        }

        File logFile = new File(dir, logFilename);
        try (PrintWriter writer = new PrintWriter(new FileWriter(logFile, true))) {
            writer.println(entry);
            log.info("Audit entry written: {}", entry);
        } catch (IOException e) {
            log.error("Write failed for audit log at {}: {}", logFile.getAbsolutePath(), e.getMessage());
        }
    }
}
