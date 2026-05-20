package com.banking.dto;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.UUID;
@Data
public class WithdrawalRequest {
    @NotNull private UUID accountId;
    @NotNull @Positive private Long amount;
    @NotBlank private String idempotencyKey;
}
