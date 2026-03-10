package com.portfolio.ecosystem.service;

import com.portfolio.ecosystem.dto.TransactionRequest;
import com.portfolio.ecosystem.entity.Account;
import com.portfolio.ecosystem.entity.Transaction;
import com.portfolio.ecosystem.repository.AccountRepository;
import com.portfolio.ecosystem.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private TransactionService transactionService;

    private UUID accountId;
    private TransactionRequest request;

    @BeforeEach
    void setUp() {
        accountId = UUID.randomUUID();
        request = new TransactionRequest();
        request.setAccountId(accountId);
        request.setAmount(BigDecimal.valueOf(100.00));

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void processTransaction_whenIdempotencyKeyExists_returnExisting() {
        String ikey = "test-key-1";
        Transaction existing = new Transaction();
        existing.setIdempotencyKey(ikey);

        when(transactionRepository.findByIdempotencyKey(ikey)).thenReturn(Optional.of(existing));

        Transaction result = transactionService.processTransaction(request, ikey, false);

        assertEquals(ikey, result.getIdempotencyKey());
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void processTransaction_whenLockFailed_throwException() {
        String ikey = "test-key-2";
        when(transactionRepository.findByIdempotencyKey(ikey)).thenReturn(Optional.empty());
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        Exception exception = assertThrows(IllegalStateException.class,
                () -> transactionService.processTransaction(request, ikey, false));

        assertTrue(exception.getMessage().contains("Account is currently processing another transaction"));
    }

    @Test
    void processTransaction_debitSuccess() {
        String ikey = "test-key-3";
        when(transactionRepository.findByIdempotencyKey(ikey)).thenReturn(Optional.empty());
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        Account account = new Account();
        account.setId(accountId);
        account.setBalance(BigDecimal.valueOf(500.00));

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        Transaction savedTx = new Transaction();
        savedTx.setAmount(BigDecimal.valueOf(-100.00));
        when(transactionRepository.save(any(Transaction.class))).thenReturn(savedTx);

        Transaction result = transactionService.processTransaction(request, ikey, false);

        assertNotNull(result);
        assertEquals(BigDecimal.valueOf(400.00), account.getBalance());
        verify(redisTemplate).delete(anyString());
    }

    @Test
    void processTransaction_debitInsufficientFunds() {
        String ikey = "test-key-4";
        when(transactionRepository.findByIdempotencyKey(ikey)).thenReturn(Optional.empty());
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        Account account = new Account();
        account.setId(accountId);
        account.setBalance(BigDecimal.valueOf(50.00)); // less than request amount

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        Exception exception = assertThrows(IllegalArgumentException.class,
                () -> transactionService.processTransaction(request, ikey, false));

        assertEquals("Insufficient funds. Balance=50.0 requested=100.0", exception.getMessage());
        verify(redisTemplate).delete(anyString());
    }
}
