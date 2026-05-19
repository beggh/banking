package com.banking.dto;
import jakarta.validation.constraints.*;
import lombok.Getter;

import java.util.UUID;
public class TransferRequest {
    @Getter
    @NotNull private UUID fromAccountId;
    @Getter
    @NotNull private UUID toAccountId;
    @Getter
    @NotNull @Positive private Long amount;
    @Getter
    @NotBlank private String idempotencyKey;
}
