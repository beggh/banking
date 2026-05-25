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
import com.banking.pattern.command.ReversalCommand;
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
@Transactional(isolation = Isolation.READ_COMMITTED, noRollbackFor = BankingException.class)
public class ReversalHandler extends BaseTransactionHandler<ReversalCommand> {

    public ReversalHandler(AccountRepository accountRepo,
                           TransactionRepository txnRepo,
                           AuditLogRepository auditRepo,
                           AuditService auditService,
                           TransactionFactory factory) {
        super(accountRepo, txnRepo, auditRepo, auditService, factory);
    }

    @Override
    protected String idempotencyKey(ReversalCommand command) {
        return "reversal:" + command.getTransactionId();
    }

    @Override
    protected Transaction buildTransaction(ReversalCommand command) {
        // Placeholder — from/to/amount are filled in doProcess after loading the original
        return factory.build(TransactionType.REVERSAL, null, null, null, idempotencyKey(command));
    }

    @Override
    protected void doProcess(Transaction reversal, ReversalCommand command) {
        Transaction original = txnRepo.findById(command.getTransactionId())
                .orElseThrow(() -> new BankingException(FailureReason.TRANSACTION_NOT_FOUND));

        if (original.getStatus() == TransactionStatus.REVERSED)
            throw new BankingException(FailureReason.ALREADY_REVERSED);
        if (original.getStatus() != TransactionStatus.SUCCESS)
            throw new BankingException(FailureReason.REVERSAL_NOT_ALLOWED);

        // Fill placeholder fields from the original transaction
        reversal.setFromAccountId(original.getToAccountId());
        reversal.setToAccountId(original.getFromAccountId());
        reversal.setAmount(original.getAmount());
        reversal.setOriginalTransactionId(original.getId());

        switch (original.getType()) {

            case DEPOSIT -> {
                Account account = lockAccount(original.getToAccountId());

                if (account.getBalance() < original.getAmount()) {
                    reversal.setStatus(TransactionStatus.FAILED);
                    reversal.setFailureReason(FailureReason.INSUFFICIENT_FUNDS);
                    txnRepo.save(reversal);
                    AuditLog audit = auditService.buildAudit(account, reversal,
                            AuditOperation.REVERSAL_DEBIT,
                            account.getBalance(), account.getBalance(), FailureReason.INSUFFICIENT_FUNDS);
                    auditRepo.save(audit);
                    throw new BankingException(FailureReason.INSUFFICIENT_FUNDS);
                }

                long before = account.getBalance();
                account.setBalance(before - original.getAmount());
                accountRepo.save(account);

                reversal.setStatus(TransactionStatus.SUCCESS);
                txnRepo.save(reversal);

                AuditLog audit = auditService.buildAudit(account, reversal,
                        AuditOperation.REVERSAL_DEBIT, before, account.getBalance(), null);
                auditRepo.save(audit);
            }

            case WITHDRAWAL -> {
                Account account = lockAccount(original.getFromAccountId());

                if (account.getStatus() == AccountStatus.CLOSED) {
                    reversal.setStatus(TransactionStatus.FAILED);
                    reversal.setFailureReason(FailureReason.ACCOUNT_CLOSED);
                    txnRepo.save(reversal);
                    AuditLog audit = auditService.buildAudit(account, reversal,
                            AuditOperation.REVERSAL_CREDIT,
                            account.getBalance(), account.getBalance(), FailureReason.ACCOUNT_CLOSED);
                    auditRepo.save(audit);
                    throw new BankingException(FailureReason.ACCOUNT_CLOSED);
                }

                long before = account.getBalance();
                account.setBalance(before + original.getAmount());
                accountRepo.save(account);

                reversal.setStatus(TransactionStatus.SUCCESS);
                txnRepo.save(reversal);

                AuditLog audit = auditService.buildAudit(account, reversal,
                        AuditOperation.REVERSAL_CREDIT, before, account.getBalance(), null);
                auditRepo.save(audit);
            }

            case TRANSFER -> {
                UUID firstId  = min(original.getFromAccountId(), original.getToAccountId());
                UUID secondId = max(original.getFromAccountId(), original.getToAccountId());

                Account first  = lockAccount(firstId);
                Account second = lockAccount(secondId);

                Account source = first.getId().equals(original.getFromAccountId()) ? first : second;
                Account target = first.getId().equals(original.getToAccountId())   ? first : second;

                if (target.getBalance() < original.getAmount()) {
                    reversal.setStatus(TransactionStatus.FAILED);
                    reversal.setFailureReason(FailureReason.INSUFFICIENT_FUNDS);
                    txnRepo.save(reversal);
                    AuditLog audit = auditService.buildAudit(target, reversal,
                            AuditOperation.REVERSAL_DEBIT,
                            target.getBalance(), target.getBalance(), FailureReason.INSUFFICIENT_FUNDS);
                    auditRepo.save(audit);
                    throw new BankingException(FailureReason.INSUFFICIENT_FUNDS);
                }

                long sourceBefore = source.getBalance();
                long targetBefore = target.getBalance();

                source.setBalance(sourceBefore + original.getAmount());
                target.setBalance(targetBefore - original.getAmount());

                accountRepo.save(source);
                accountRepo.save(target);

                reversal.setStatus(TransactionStatus.SUCCESS);
                txnRepo.save(reversal);

                AuditLog debit  = auditService.buildAudit(target, reversal,
                        AuditOperation.REVERSAL_DEBIT, targetBefore, target.getBalance(), null);
                AuditLog credit = auditService.buildAudit(source, reversal,
                        AuditOperation.REVERSAL_CREDIT, sourceBefore, source.getBalance(), null);
                auditRepo.save(debit);
                auditRepo.save(credit);
            }

            default -> throw new BankingException(FailureReason.REVERSAL_NOT_ALLOWED);
        }

        original.setStatus(TransactionStatus.REVERSED);
        txnRepo.save(original);
    }
}
