package com.banking.dto;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.Getter;

import java.util.UUID;
public class ReversalRequest {
    @Getter
    @NotNull private UUID transactionId;
    @Getter
    private String reason;
}
