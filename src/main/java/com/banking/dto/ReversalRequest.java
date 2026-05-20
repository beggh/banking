package com.banking.dto;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;
@Data
public class ReversalRequest {
    @NotNull private UUID transactionId;
    private String reason;
}
