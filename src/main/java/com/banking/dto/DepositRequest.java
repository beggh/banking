package com.banking.dto;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.UUID;

@Data
public class DepositRequest {
    @NotNull private UUID accountId;
    @NotNull @Positive private Long amount;
    @NotBlank private String idempotencyKey;
}
