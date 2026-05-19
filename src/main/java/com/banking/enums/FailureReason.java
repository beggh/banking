package com.banking.enums;

public enum FailureReason {
    INSUFFICIENT_FUNDS,
    ACCOUNT_NOT_FOUND,
    ACCOUNT_FROZEN,
    ACCOUNT_CLOSED,
    INVALID_AMOUNT,         // zero or negative
    SAME_ACCOUNT_TRANSFER,
    TRANSACTION_NOT_FOUND,
    ALREADY_REVERSED,       // original txn status is already REVERSED
    REVERSAL_NOT_ALLOWED,   // tried to reverse a FAILED or PENDING txn
    DUPLICATE_REQUEST       // idempotency_key already exists
}
