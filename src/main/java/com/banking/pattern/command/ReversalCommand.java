package com.banking.pattern.command;

import java.util.UUID;

public class ReversalCommand implements BankingCommand {

    private final UUID transactionId;

    public ReversalCommand(UUID transactionId) {
        this.transactionId = transactionId;
    }

    public UUID getTransactionId() { return transactionId; }
}
