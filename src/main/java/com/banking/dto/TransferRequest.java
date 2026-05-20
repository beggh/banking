package com.banking.dto;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.Getter;

import java.util.UUID;
@Data
public class TransferRequest {
    @NotNull private UUID fromAccountId;
    @NotNull private UUID toAccountId;
    @NotNull @Positive private Long amount;
    @NotBlank private String idempotencyKey;
}
