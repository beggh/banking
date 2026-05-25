package com.banking.pattern.command;

import java.util.UUID;

public class TransferCommand implements ReversibleCommand {

    private final UUID fromAccountId;
    private final UUID toAccountId;
    private final long amount;
    private final String idempotencyKey;

    public TransferCommand(UUID fromAccountId, UUID toAccountId, long amount, String idempotencyKey) {
        this.fromAccountId = fromAccountId;
        this.toAccountId = toAccountId;
        this.amount = amount;
        this.idempotencyKey = idempotencyKey;
    }

    public UUID getFromAccountId()    { return fromAccountId; }
    public UUID getToAccountId()      { return toAccountId; }
    public long getAmount()           { return amount; }
    public String getIdempotencyKey() { return idempotencyKey; }

    @Override
    public ReversalCommand createUndo(UUID originalTransactionId) {
        return new ReversalCommand(originalTransactionId);
    }
}
