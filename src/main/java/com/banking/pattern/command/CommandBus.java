package com.banking.pattern.command;

import com.banking.entity.Transaction;
import com.banking.pattern.template.DepositHandler;
import com.banking.pattern.template.ReversalHandler;
import com.banking.pattern.template.TransferHandler;
import com.banking.pattern.template.WithdrawalHandler;
import org.springframework.stereotype.Component;

@Component
public class CommandBus {

    private final DepositHandler    depositHandler;
    private final WithdrawalHandler withdrawalHandler;
    private final TransferHandler   transferHandler;
    private final ReversalHandler   reversalHandler;

    public CommandBus(DepositHandler depositHandler,
                      WithdrawalHandler withdrawalHandler,
                      TransferHandler transferHandler,
                      ReversalHandler reversalHandler) {
        this.depositHandler    = depositHandler;
        this.withdrawalHandler = withdrawalHandler;
        this.transferHandler   = transferHandler;
        this.reversalHandler   = reversalHandler;
    }

    public Transaction dispatch(BankingCommand command) {
        if (command instanceof DepositCommand dc)    return depositHandler.handle(dc);
        if (command instanceof WithdrawalCommand wc) return withdrawalHandler.handle(wc);
        if (command instanceof TransferCommand tc)   return transferHandler.handle(tc);
        if (command instanceof ReversalCommand rc)   return reversalHandler.handle(rc);
        throw new IllegalArgumentException("Unknown command type: " + command.getClass().getSimpleName());
    }
}
