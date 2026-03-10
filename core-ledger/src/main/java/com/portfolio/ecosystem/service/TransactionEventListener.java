package com.portfolio.ecosystem.service;

import com.portfolio.ecosystem.dto.TransactionCompletedEvent;
import com.portfolio.ecosystem.config.RabbitMQConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Listens for TransactionCompletedEvents off the RabbitMQ queue.
 *
 * Spring AMQP will automatically retry failed messages based on the retry
 * config in RabbitMQConfig. After maxAttempts, the message goes to the DLQ
 * where ops can inspect/replay it.
 *
 * The simulated failure below is intentional - it lets us visually confirm
 * the retry + DLQ wiring is working end-to-end. In production this block
 * would be replaced with the actual downstream call (email, webhook, etc.)
 */
@Service
@Slf4j
public class TransactionEventListener {

    // tracks retries per-message in tests; the AMQP retry interceptor also
    // tracks separately on the broker side
    private final AtomicInteger retryCounter = new AtomicInteger();

    @RabbitListener(queues = RabbitMQConfig.TRANSACTION_QUEUE)
    public void handleTransactionCompleted(TransactionCompletedEvent event) {
        log.info("Got event off queue -> txId={} accountId={} amount={}",
                event.getTransactionId(), event.getAccountId(), event.getAmount());

        // Simulate a transient downstream failure (e.g. third-party API timeout)
        // to prove exponential backoff works. Fails twice, succeeds on attempt 3.
        int attempt = retryCounter.getAndIncrement();
        if (attempt < 2) {
            log.warn("Simulated transient failure on attempt {} for tx={}, retrying...", attempt + 1,
                    event.getTransactionId());
            throw new RuntimeException("Downstream service unavailable (simulated)");
        }

        // Permanently bad messages (e.g. corrupt payload) go straight to DLQ
        // without burning retry slots
        if ("FAILED".equals(event.getStatus())) {
            log.error("Non-retryable failure for tx={}, routing to DLQ", event.getTransactionId());
            throw new AmqpRejectAndDontRequeueException("Permanent failure - message sent to DLQ");
        }

        // happy path - do the actual downstream work here
        log.info("Successfully processed tx={} account={} amount={}",
                event.getTransactionId(), event.getAccountId(), event.getAmount());

        retryCounter.set(0);
    }
}
