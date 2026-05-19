package com.banking.exception;

import com.banking.entity.Transaction;

public class DuplicateRequestException extends RuntimeException {
    private final Transaction existing;
    public DuplicateRequestException(Transaction existing) {
        super("Duplicate request");
        this.existing = existing;
    }
    public Transaction getExisting() { return existing; }
}
