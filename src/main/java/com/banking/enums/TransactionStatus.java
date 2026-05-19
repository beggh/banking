package com.banking.enums;

public enum TransactionStatus {
    PENDING,    // created but not yet committed (used if async flow added later)
    SUCCESS,    // balances updated successfully
    FAILED,     // attempted but rejected (insufficient funds, invalid account, etc.)
    REVERSED    // was SUCCESS but has been reversed; balance impact undone
}
