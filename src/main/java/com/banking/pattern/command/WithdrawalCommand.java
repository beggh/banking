package com.banking.pattern.command;

import java.util.UUID;

public class WithdrawalCommand implements ReversibleCommand {

    private final UUID accountId;
    private final long amount;
    private final String idempotencyKey;

    public WithdrawalCommand(UUID accountId, long amount, String idempotencyKey) {
        this.accountId = accountId;
        this.amount = amount;
        this.idempotencyKey = idempotencyKey;
    }

    public UUID getAccountId()        { return accountId; }
    public long getAmount()           { return amount; }
    public String getIdempotencyKey() { return idempotencyKey; }

    @Override
    public ReversalCommand createUndo(UUID originalTransactionId) {
        return new ReversalCommand(originalTransactionId);
    }
}
