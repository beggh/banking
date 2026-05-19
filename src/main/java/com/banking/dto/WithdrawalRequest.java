package com.banking.dto;
import jakarta.validation.constraints.*;
import java.util.UUID;
public class WithdrawalRequest {
    @NotNull private UUID accountId;
    @NotNull @Positive private Long amount;
    @NotBlank private String idempotencyKey;
    public UUID getAccountId() { return accountId; }
    public Long getAmount() { return amount; }
    public String getIdempotencyKey() { return idempotencyKey; }
}
