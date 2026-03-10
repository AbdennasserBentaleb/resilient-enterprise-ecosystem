package com.portfolio.ecosystem.service;

import com.portfolio.ecosystem.dto.TransactionRequest;
import com.portfolio.ecosystem.entity.Account;
import com.portfolio.ecosystem.repository.AccountRepository;
import com.portfolio.ecosystem.repository.TransactionRepository;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Slf4j
@SpringBootTest
@Testcontainers
class TransactionConcurrencyIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"));

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitmq = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.12-alpine"));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    private UUID accountId;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setTenantId(tenantId);
        account.setBalance(new BigDecimal("1000.00"));
        account = accountRepository.save(account);
        accountId = account.getId();
    }

    @Test
    void testConcurrentDebits_shouldNotDoubleSpend() throws InterruptedException {
        int threadCount = 20;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successfulTransactions = new AtomicInteger(0);
        AtomicInteger lockRejections = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    latch.await(); // wait for start signal
                    TransactionRequest request = new TransactionRequest();
                    request.setAccountId(accountId);
                    request.setAmount(new BigDecimal("10.00")); // debit 10
                    
                    transactionService.processTransaction(request, UUID.randomUUID().toString(), false);
                    successfulTransactions.incrementAndGet();
                } catch (IllegalStateException e) {
                    // Lock rejected - expected under high contention
                    lockRejections.incrementAndGet();
                } catch (Exception e) {
                    log.error("Unexpected error", e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Fire all threads
        latch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);

        // Verify bounds
        Account finalAccount = accountRepository.findById(accountId).orElseThrow();
        
        log.info("Successful txs: {}", successfulTransactions.get());
        log.info("Lock rejections: {}", lockRejections.get());
        
        // Final balance should be 1000 - (successCount * 10)
        BigDecimal expectedBalance = new BigDecimal("1000.00")
                .subtract(new BigDecimal("10.00").multiply(new BigDecimal(successfulTransactions.get())));
                
        assertEquals(expectedBalance.stripTrailingZeros(), finalAccount.getBalance().stripTrailingZeros(), 
                "Balance must strictly match successful deductions");
                
        assertEquals(threadCount, successfulTransactions.get() + lockRejections.get(),
                "All threads should either succeed or be rejected by lock");
    }
}
