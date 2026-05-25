package com.banking.pattern.template;

import com.banking.entity.Account;
import com.banking.entity.AuditLog;
import com.banking.entity.Transaction;
import com.banking.enums.AccountStatus;
import com.banking.enums.AuditOperation;
import com.banking.enums.FailureReason;
import com.banking.enums.TransactionStatus;
import com.banking.enums.TransactionType;
import com.banking.pattern.command.DepositCommand;
import com.banking.pattern.factory.TransactionFactory;
import com.banking.repository.AccountRepository;
import com.banking.repository.AuditLogRepository;
import com.banking.repository.TransactionRepository;
import com.banking.service.AuditService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(isolation = Isolation.READ_COMMITTED)
public class DepositHandler extends BaseTransactionHandler<DepositCommand> {

    public DepositHandler(AccountRepository accountRepo,
                          TransactionRepository txnRepo,
                          AuditLogRepository auditRepo,
                          AuditService auditService,
                          TransactionFactory factory) {
        super(accountRepo, txnRepo, auditRepo, auditService, factory);
    }

    @Override
    protected String idempotencyKey(DepositCommand command) {
        return command.getIdempotencyKey();
    }

    @Override
    protected Transaction buildTransaction(DepositCommand command) {
        return factory.build(TransactionType.DEPOSIT,
                null, command.getAccountId(), command.getAmount(), command.getIdempotencyKey());
    }

    @Override
    protected void doProcess(Transaction txn, DepositCommand command) {
        Account account = lockAccount(command.getAccountId());

        if (account.getStatus() == AccountStatus.CLOSED)
            failTxn(txn, account.getId(), AuditOperation.CREDIT, FailureReason.ACCOUNT_CLOSED);

        long before = account.getBalance();
        account.setBalance(before + command.getAmount());
        accountRepo.save(account);

        txn.setStatus(TransactionStatus.SUCCESS);
        txnRepo.save(txn);

        AuditLog audit = auditService.buildAudit(account, txn, AuditOperation.CREDIT,
                before, account.getBalance(), null);
        auditRepo.save(audit);
    }
}
