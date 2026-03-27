package com.portfolio.ecosystem.service;

import com.portfolio.ecosystem.config.TenantContext;
import com.portfolio.ecosystem.dto.AccountResponse;
import com.portfolio.ecosystem.dto.TransactionRequest;
import com.portfolio.ecosystem.entity.Account;
import com.portfolio.ecosystem.entity.Transaction;
import com.portfolio.ecosystem.repository.AccountRepository;
import com.portfolio.ecosystem.repository.TransactionRepository;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import com.portfolio.ecosystem.dto.TransactionCompletedEvent;
import com.portfolio.ecosystem.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final RabbitTemplate rabbitTemplate;

    // prefix for redis lock keys - keeps them namespaced and easy to search
    private static final String LOCK_PREFIX = "account:lock:";
    private static final long LOCK_TIMEOUT_MS = 3000;

    @Transactional
    public Transaction processTransaction(TransactionRequest request, String idempotencyKey, boolean isCredit) {
        // Short-circuit if we've already processed this exact request.
        // This is critical for distributed clients that retry on network timeout.
        Optional<Transaction> existingTx = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existingTx.isPresent()) {
            log.info("Idempotent replay detected for key={}, returning cached result", idempotencyKey);
            return existingTx.get();
        }

        UUID accountId = request.getAccountId();
        String lockKey = LOCK_PREFIX + accountId.toString();

        // Try to grab a distributed lock. If another request is mid-flight for this
        // account we reject immediately - the client should retry with backoff.
        // 3s TTL is plenty for our DB round-trip; avoids lock leaks on crash.
        Boolean lockAcquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "LOCKED", LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        if (Boolean.FALSE.equals(lockAcquired)) {
            throw new IllegalStateException("Account is currently processing another transaction. Please retry.");
        }

        try {
            Account account = accountRepository.findById(accountId)
                    .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

            BigDecimal amount = request.getAmount();

            if (!isCredit) {
                if (account.getBalance().compareTo(amount) < 0) {
                    throw new IllegalArgumentException(
                            "Insufficient funds. Balance=" + account.getBalance() + " requested=" + amount);
                }
                account.setBalance(account.getBalance().subtract(amount));
            } else {
                account.setBalance(account.getBalance().add(amount));
            }

            accountRepository.save(account);

            Transaction tx = new Transaction();
            tx.setAccountId(accountId);
            tx.setTenantId(TenantContext.getCurrentTenant() != null
                    ? TenantContext.getCurrentTenant()
                    : UUID.randomUUID());
            tx.setAmount(isCredit ? amount : amount.negate());
            tx.setIdempotencyKey(idempotencyKey);
            tx.setStatus("SUCCESS");

            Transaction saved = transactionRepository.save(tx);

            log.info("Transaction saved id={} accountId={} amount={} status={}", saved.getId(), accountId, amount,
                    saved.getStatus());

            // Fire-and-forget event to RabbitMQ. Any downstream failure
            // will be handled by the DLQ consumer - we don't want to roll
            // back the DB write just because a notification failed.
            TransactionCompletedEvent event = new TransactionCompletedEvent(
                    saved.getId(),
                    saved.getAccountId(),
                    saved.getAmount(),
                    saved.getStatus());
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_NAME, RabbitMQConfig.ROUTING_KEY, event);

            return saved;
        } finally {
            // always release the lock whether we succeeded or threw
            redisTemplate.delete(lockKey);
        }
    }

    @Transactional(readOnly = true)
    public AccountResponse getBalance(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));
        return new AccountResponse(account.getId(), account.getBalance());
    }

    @Transactional(readOnly = true)
    public java.util.List<Transaction> getTransactions(UUID accountId) {
        return transactionRepository.findByAccountIdOrderByCreatedAtDesc(accountId);
    }
}
