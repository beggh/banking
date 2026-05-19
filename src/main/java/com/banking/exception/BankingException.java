package com.banking.exception;

import com.banking.enums.FailureReason;

/** Thrown when a business rule rejects an operation. */
public class BankingException extends RuntimeException {

    private final FailureReason reason;

    public BankingException(FailureReason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public BankingException(FailureReason reason, String detail) {
        super(reason.name() + ": " + detail);
        this.reason = reason;
    }

    public FailureReason getReason() { return reason; }
}
