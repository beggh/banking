package com.banking.pattern.command;

import java.util.UUID;

public interface ReversibleCommand extends BankingCommand {
    ReversalCommand createUndo(UUID originalTransactionId);
}
