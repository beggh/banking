package com.banking.pattern.template;

import com.banking.entity.Account;
import com.banking.entity.AuditLog;
import com.banking.entity.Transaction;
import com.banking.enums.AccountStatus;
import com.banking.enums.AuditOperation;
import com.banking.enums.FailureReason;
import com.banking.enums.TransactionStatus;
import com.banking.enums.TransactionType;
import com.banking.exception.BankingException;
import com.banking.pattern.command.TransferCommand;
import com.banking.pattern.factory.TransactionFactory;
import com.banking.repository.AccountRepository;
import com.banking.repository.AuditLogRepository;
import com.banking.repository.TransactionRepository;
import com.banking.service.AuditService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
@Transactional(isolation = Isolation.READ_COMMITTED)
public class TransferHandler extends BaseTransactionHandler<TransferCommand> {

    public TransferHandler(AccountRepository accountRepo,
                           TransactionRepository txnRepo,
                           AuditLogRepository auditRepo,
                           AuditService auditService,
                           TransactionFactory factory) {
        super(accountRepo, txnRepo, auditRepo, auditService, factory);
    }

    // Same-account guard runs before idempotency check
    @Override
    protected void preCheck(TransferCommand command) {
        if (command.getFromAccountId().equals(command.getToAccountId())) {
            Transaction txn = factory.build(TransactionType.TRANSFER,
                    command.getFromAccountId(), command.getToAccountId(),
                    command.getAmount(), command.getIdempotencyKey());
            txn.setStatus(TransactionStatus.FAILED);
            txn.setFailureReason(FailureReason.SAME_ACCOUNT_TRANSFER);
            txnRepo.save(txn);
            throw new BankingException(FailureReason.SAME_ACCOUNT_TRANSFER);
        }
    }

    @Override
    protected String idempotencyKey(TransferCommand command) {
        return command.getIdempotencyKey();
    }

    @Override
    protected Transaction buildTransaction(TransferCommand command) {
        return factory.build(TransactionType.TRANSFER,
                command.getFromAccountId(), command.getToAccountId(),
                command.getAmount(), command.getIdempotencyKey());
    }

    @Override
    protected void doProcess(Transaction txn, TransferCommand command) {
        // Deadlock prevention: always lock in ascending UUID order
        UUID firstId  = min(command.getFromAccountId(), command.getToAccountId());
        UUID secondId = max(command.getFromAccountId(), command.getToAccountId());

        Account first  = lockAccount(firstId);
        Account second = lockAccount(secondId);

        Account source = first.getId().equals(command.getFromAccountId()) ? first : second;
        Account target = first.getId().equals(command.getToAccountId())   ? first : second;

        if (source.getStatus() == AccountStatus.CLOSED)
            failTxn(txn, source.getId(), AuditOperation.DEBIT, FailureReason.ACCOUNT_CLOSED);
        if (source.getStatus() == AccountStatus.FROZEN)
            failTxn(txn, source.getId(), AuditOperation.DEBIT, FailureReason.ACCOUNT_FROZEN);
        if (source.getBalance() < command.getAmount())
            failTxn(txn, source.getId(), AuditOperation.DEBIT, FailureReason.INSUFFICIENT_FUNDS);
        if (target.getStatus() == AccountStatus.CLOSED)
            failTxn(txn, target.getId(), AuditOperation.CREDIT, FailureReason.ACCOUNT_CLOSED);

        long sourceBefore = source.getBalance();
        long targetBefore = target.getBalance();

        source.setBalance(sourceBefore - command.getAmount());
        target.setBalance(targetBefore + command.getAmount());

        accountRepo.save(source);
        accountRepo.save(target);

        txn.setStatus(TransactionStatus.SUCCESS);
        txnRepo.save(txn);

        AuditLog debit  = auditService.buildAudit(source, txn, AuditOperation.DEBIT,
                sourceBefore, source.getBalance(), null);
        AuditLog credit = auditService.buildAudit(target, txn, AuditOperation.CREDIT,
                targetBefore, target.getBalance(), null);
        auditRepo.save(debit);
        auditRepo.save(credit);
    }
}
