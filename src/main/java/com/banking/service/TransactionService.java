package com.banking.service;

import com.banking.dto.DepositRequest;
import com.banking.dto.ReversalRequest;
import com.banking.dto.TransferRequest;
import com.banking.dto.WithdrawalRequest;
import com.banking.entity.Transaction;
import com.banking.pattern.command.CommandBus;
import com.banking.pattern.command.DepositCommand;
import com.banking.pattern.command.ReversalCommand;
import com.banking.pattern.command.TransferCommand;
import com.banking.pattern.command.WithdrawalCommand;
import com.banking.repository.TransactionRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TransactionService {

    private final TransactionRepository txnRepo;
    private final CommandBus commandBus;

    public TransactionService(TransactionRepository txnRepo, CommandBus commandBus) {
        this.txnRepo     = txnRepo;
        this.commandBus  = commandBus;
    }

    public List<Transaction> listAll() {
        return txnRepo.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    public Transaction deposit(DepositRequest req) {
        return commandBus.dispatch(new DepositCommand(
                req.getAccountId(), req.getAmount(), req.getIdempotencyKey()));
    }

    public Transaction withdraw(WithdrawalRequest req) {
        return commandBus.dispatch(new WithdrawalCommand(
                req.getAccountId(), req.getAmount(), req.getIdempotencyKey()));
    }

    public Transaction transfer(TransferRequest req) {
        return commandBus.dispatch(new TransferCommand(
                req.getFromAccountId(), req.getToAccountId(),
                req.getAmount(), req.getIdempotencyKey()));
    }

    public Transaction reverse(ReversalRequest req) {
        return commandBus.dispatch(new ReversalCommand(req.getTransactionId()));
    }
}
