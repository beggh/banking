package com.banking.pattern.template;

import com.banking.entity.Account;
import com.banking.entity.AuditLog;
import com.banking.entity.Transaction;
import com.banking.enums.AuditOperation;
import com.banking.enums.FailureReason;
import com.banking.enums.TransactionStatus;
import com.banking.exception.BankingException;
import com.banking.exception.DuplicateRequestException;
import com.banking.pattern.command.BankingCommand;
import com.banking.pattern.factory.TransactionFactory;
import com.banking.repository.AccountRepository;
import com.banking.repository.AuditLogRepository;
import com.banking.repository.TransactionRepository;
import com.banking.service.AuditService;

import java.util.UUID;

public abstract class BaseTransactionHandler<C extends BankingCommand> {

    protected final AccountRepository     accountRepo;
    protected final TransactionRepository txnRepo;
    protected final AuditLogRepository    auditRepo;
    protected final AuditService          auditService;
    protected final TransactionFactory    factory;

    protected BaseTransactionHandler(AccountRepository accountRepo,
                                     TransactionRepository txnRepo,
                                     AuditLogRepository auditRepo,
                                     AuditService auditService,
                                     TransactionFactory factory) {
        this.accountRepo  = accountRepo;
        this.txnRepo      = txnRepo;
        this.auditRepo    = auditRepo;
        this.auditService = auditService;
        this.factory      = factory;
    }

    // ─── Template Method ──────────────────────────────────────────────────────

    public final Transaction handle(C command) {
        preCheck(command);
        checkIdempotency(command);
        Transaction txn = buildTransaction(command);
        doProcess(txn, command);
        return txn;
    }

    // ─── Hook ─────────────────────────────────────────────────────────────────

    /** Called before idempotency check. Override for pre-processing (e.g. same-account guard). */
    protected void preCheck(C command) {}

    // ─── Abstract Steps ───────────────────────────────────────────────────────

    protected abstract String      idempotencyKey(C command);
    protected abstract Transaction buildTransaction(C command);
    protected abstract void        doProcess(Transaction txn, C command);

    // ─── Common Helpers ───────────────────────────────────────────────────────

    protected void checkIdempotency(C command) {
        txnRepo.findByIdempotencyKey(idempotencyKey(command)).ifPresent(existing -> {
            throw new DuplicateRequestException(existing);
        });
    }

    protected Account lockAccount(UUID id) {
        return accountRepo.findByIdForUpdate(id)
                .orElseThrow(() -> new BankingException(FailureReason.ACCOUNT_NOT_FOUND));
    }

    protected Transaction failTxn(Transaction txn, UUID accountId,
                                  AuditOperation op, FailureReason reason) {
        txn.setStatus(TransactionStatus.FAILED);
        txn.setFailureReason(reason);
        Account account = lockAccount(accountId);
        txnRepo.save(txn);
        AuditLog audit = auditService.buildAudit(account, txn, op,
                account.getBalance(), account.getBalance(), reason);
        auditRepo.save(audit);
        throw new BankingException(reason);
    }

    protected static UUID min(UUID a, UUID b) { return a.compareTo(b) <= 0 ? a : b; }
    protected static UUID max(UUID a, UUID b) { return a.compareTo(b) >= 0 ? a : b; }
}
