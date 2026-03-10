package com.portfolio.ecosystem.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;
import java.util.UUID;

@Data
public class TransactionRequest {
    @NotNull
    private UUID accountId;
    
    @NotNull
    @DecimalMin(value = "0.01", message = "Amount must be strictly positive")
    private BigDecimal amount;
}

