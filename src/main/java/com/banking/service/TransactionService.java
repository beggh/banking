package com.banking.service;

import com.banking.dto.*;
import com.banking.entity.*;
import com.banking.enums.*;
import com.banking.exception.BankingException;
import com.banking.exception.DuplicateRequestException;
import com.banking.repository.*;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class TransactionService {

    private final AccountRepository     accountRepo;
    private final TransactionRepository txnRepo;
    private final AuditLogRepository    auditRepo;
    private final AuditService auditService;

    public List<Transaction> listAll() {
        return txnRepo.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    public TransactionService(AccountRepository accountRepo,
                               TransactionRepository txnRepo,
                               AuditLogRepository auditRepo,
                              AuditService auditService) {
        this.accountRepo = accountRepo;
        this.txnRepo     = txnRepo;
        this.auditRepo   = auditRepo;
        this.auditService = auditService;

    }

    // ─── DEPOSIT ──────────────────────────────────────────────────────────────

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Transaction deposit(DepositRequest req) {
        checkIdempotency(req.getIdempotencyKey());

        Transaction txn = buildTxn(TransactionType.DEPOSIT,
                null, req.getAccountId(), req.getAmount(), req.getIdempotencyKey());

        Account account = lockAccount(req.getAccountId());

        if (account.getStatus() == AccountStatus.CLOSED) {
            return failTxn(txn, req.getAccountId(), AuditOperation.CREDIT, FailureReason.ACCOUNT_CLOSED);
        }
        
        long before = account.getBalance();
        account.setBalance(before + req.getAmount());
        long after = account.getBalance();
        accountRepo.save(account);

        txn.setStatus(TransactionStatus.SUCCESS);
        txnRepo.save(txn);
        AuditLog auditLog =  auditService.buildAudit(account, txn, AuditOperation.CREDIT, before, after, null);
        auditRepo.save(auditLog);
        return txn;
    }

    // ─── WITHDRAWAL ───────────────────────────────────────────────────────────

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Transaction withdraw(WithdrawalRequest req) {
        checkIdempotency(req.getIdempotencyKey());

        Transaction txn = buildTxn(TransactionType.WITHDRAWAL,
                req.getAccountId(), null, req.getAmount(), req.getIdempotencyKey());

        Account account = lockAccount(req.getAccountId());

        if (account.getStatus() == AccountStatus.CLOSED) {
            return failTxn(txn, req.getAccountId(), AuditOperation.DEBIT, FailureReason.ACCOUNT_CLOSED);
        }
        if (account.getStatus() == AccountStatus.FROZEN) {
            return failTxn(txn, req.getAccountId(), AuditOperation.DEBIT, FailureReason.ACCOUNT_FROZEN);
        }
        if (account.getBalance() < req.getAmount()) {
            return failTxn(txn, req.getAccountId(), AuditOperation.DEBIT, FailureReason.INSUFFICIENT_FUNDS);
        }

        long before = account.getBalance();
        account.setBalance(before - req.getAmount());
        long after = account.getBalance();
        accountRepo.save(account);

        txn.setStatus(TransactionStatus.SUCCESS);
        txnRepo.save(txn);
        AuditLog auditLog =  auditService.buildAudit(account, txn, AuditOperation.DEBIT, before, after, null);
        auditRepo.save(auditLog);
        return txn;
    }

    // ─── TRANSFER ─────────────────────────────────────────────────────────────
    //
    // Deadlock prevention: always lock the two accounts in ascending UUID order.
    // If A→B and B→A run concurrently, both acquire the lower-UUID lock first,
    // so neither starves the other.

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Transaction transfer(TransferRequest req) {
        if (req.getFromAccountId().equals(req.getToAccountId())) {
            Transaction txn = buildTxn(TransactionType.TRANSFER,
                    req.getFromAccountId(), req.getToAccountId(),
                    req.getAmount(), req.getIdempotencyKey());
            txn.setStatus(TransactionStatus.FAILED);
            txn.setFailureReason(FailureReason.SAME_ACCOUNT_TRANSFER);
            txnRepo.save(txn);
            throw new BankingException(FailureReason.SAME_ACCOUNT_TRANSFER);
        }

        checkIdempotency(req.getIdempotencyKey());

        Transaction txn = buildTxn(TransactionType.TRANSFER,
                req.getFromAccountId(), req.getToAccountId(),
                req.getAmount(), req.getIdempotencyKey());

        UUID firstId  = min(req.getFromAccountId(), req.getToAccountId());
        UUID secondId = max(req.getFromAccountId(), req.getToAccountId());

        Account first  = lockAccount(firstId);
        Account second = lockAccount(secondId);

        Account source = first.getId().equals(req.getFromAccountId()) ? first : second;
        Account target = first.getId().equals(req.getToAccountId())   ? first : second;

        if (source.getStatus() == AccountStatus.CLOSED)
            return failTxn(txn, source.getId(), AuditOperation.DEBIT, FailureReason.ACCOUNT_CLOSED);
        if (source.getStatus() == AccountStatus.FROZEN)
            return failTxn(txn, source.getId(), AuditOperation.DEBIT, FailureReason.ACCOUNT_FROZEN);
        if (source.getBalance() < req.getAmount())
            return failTxn(txn, source.getId(), AuditOperation.DEBIT, FailureReason.INSUFFICIENT_FUNDS);
        if (target.getStatus() == AccountStatus.CLOSED)
            return failTxn(txn, target.getId(), AuditOperation.CREDIT, FailureReason.ACCOUNT_CLOSED);

        long sourceBefore = source.getBalance();
        long targetBefore = target.getBalance();

        source.setBalance(sourceBefore - req.getAmount());
        target.setBalance(targetBefore + req.getAmount());

        accountRepo.save(source);
        accountRepo.save(target);
        System.out.println(target.toString());

        txn.setStatus(TransactionStatus.SUCCESS);
        txnRepo.save(txn);

        AuditLog auditLogDebit =  auditService.buildAudit(source, txn, AuditOperation.DEBIT, sourceBefore, source.getBalance(), null);
        AuditLog auditLogCredit =  auditService.buildAudit(target, txn, AuditOperation.CREDIT, targetBefore, target.getBalance(), null);

        auditRepo.save(auditLogDebit);
        auditRepo.save(auditLogCredit);
        return txn;
    }

    // ─── REVERSAL ─────────────────────────────────────────────────────────────
    //
    // Idempotency key = "reversal:{original_txn_id}".
    // UNIQUE constraint on idempotency_key in DB means a second reversal
    // attempt for the same txn hits checkIdempotency() and is rejected
    // before any balance is touched — enforces exactly-once reversal.

    @Transactional(isolation = Isolation.READ_COMMITTED, noRollbackFor = BankingException.class)
    public Transaction reverse(ReversalRequest req) {
        String reversalKey = "reversal:" + req.getTransactionId();
        checkIdempotency(reversalKey);

        Transaction original = txnRepo.findById(req.getTransactionId())
                .orElseThrow(() -> new BankingException(FailureReason.TRANSACTION_NOT_FOUND));

        if (original.getStatus() == TransactionStatus.REVERSED)
            throw new BankingException(FailureReason.ALREADY_REVERSED);
        if (original.getStatus() != TransactionStatus.SUCCESS)
            throw new BankingException(FailureReason.REVERSAL_NOT_ALLOWED);

        Transaction reversal = buildTxn(
                TransactionType.REVERSAL,
                original.getToAccountId(),
                original.getFromAccountId(),
                original.getAmount(),
                reversalKey);
        reversal.setOriginalTransactionId(original.getId());

        switch (original.getType()) {

            case DEPOSIT -> {
                Account sourceReversal   = lockAccount(original.getToAccountId());
                
                if (sourceReversal.getBalance() < original.getAmount())
                    return failReversalTxn(reversal, sourceReversal.getId(),
                            AuditOperation.REVERSAL_DEBIT, FailureReason.INSUFFICIENT_FUNDS);
                long before = sourceReversal.getBalance();
                sourceReversal.setBalance(before - original.getAmount());
                accountRepo.save(sourceReversal);
                reversal.setStatus(TransactionStatus.SUCCESS);
                txnRepo.save(reversal);
                AuditLog auditLogDebit =  auditService.buildAudit(sourceReversal, reversal, AuditOperation.REVERSAL_DEBIT, before, sourceReversal.getBalance(), null);
                auditRepo.save(auditLogDebit);
            }

            case WITHDRAWAL -> {
                Account sourceReversal = lockAccount(original.getFromAccountId());
                if (sourceReversal.getStatus() == AccountStatus.CLOSED)
                    return failReversalTxn(reversal, sourceReversal.getId(),
                            AuditOperation.REVERSAL_CREDIT, FailureReason.ACCOUNT_CLOSED);
                long before = sourceReversal.getBalance();
                sourceReversal.setBalance(before + original.getAmount());
                accountRepo.save(sourceReversal);
                reversal.setStatus(TransactionStatus.SUCCESS);
                txnRepo.save(reversal);
                AuditLog auditLogDebit =  auditService.buildAudit(sourceReversal, reversal, AuditOperation.REVERSAL_CREDIT, before, sourceReversal.getBalance(), null);
                auditRepo.save(auditLogDebit);
            }

            case TRANSFER -> {
                UUID firstId = min(original.getFromAccountId(), original.getToAccountId());
                UUID secondId = max(original.getFromAccountId(), original.getToAccountId());
                Account first = lockAccount(firstId);
                Account second = lockAccount(secondId);
                Account source = first.getId().equals(original.getFromAccountId()) ? first : second;
                Account target = first.getId().equals(original.getToAccountId()) ? first : second;

                if (target.getBalance() < original.getAmount())
                    return failReversalTxn(reversal, target.getId(),
                            AuditOperation.REVERSAL_DEBIT, FailureReason.INSUFFICIENT_FUNDS);

                long sourceBefore = source.getBalance();
                long targetBefore = target.getBalance();
                source.setBalance(sourceBefore + original.getAmount());
                target.setBalance(targetBefore - original.getAmount());
                accountRepo.save(source);
                accountRepo.save(target);
                reversal.setStatus(TransactionStatus.SUCCESS);
                txnRepo.save(reversal);
                AuditLog auditLogDebit =  auditService.buildAudit(target, reversal, AuditOperation.REVERSAL_DEBIT, targetBefore, target.getBalance(), null);
                auditRepo.save(auditLogDebit);

                AuditLog auditLogCredit =  auditService.buildAudit(source, reversal, AuditOperation.REVERSAL_CREDIT, sourceBefore, source.getBalance(), null);
                auditRepo.save(auditLogCredit);
            }

            default -> throw new BankingException(FailureReason.REVERSAL_NOT_ALLOWED);
        }

        original.setStatus(TransactionStatus.REVERSED);
        txnRepo.save(original);
        return reversal;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void checkIdempotency(String key) {
        txnRepo.findByIdempotencyKey(key).ifPresent(existing -> {
            throw new DuplicateRequestException(existing);
        });
    }

    private Account lockAccount(UUID id) {
        return accountRepo.findByIdForUpdate(id)
                .orElseThrow(() -> new BankingException(FailureReason.ACCOUNT_NOT_FOUND));
    }

    private Transaction buildTxn(TransactionType type, UUID from, UUID to,
                                   Long amount, String idempotencyKey) {
        Transaction txn = new Transaction();
        txn.setType(type);
        txn.setFromAccountId(from);
        txn.setToAccountId(to);
        txn.setAmount(amount);
        txn.setIdempotencyKey(idempotencyKey);
        txn.setStatus(TransactionStatus.PENDING);
        return txn;
    }

    private Transaction failTxn(Transaction txn, UUID accountId,
                                  AuditOperation op, FailureReason reason) {
        txn.setStatus(TransactionStatus.FAILED);
        txn.setFailureReason(reason);
        Account account = lockAccount(accountId);
        txnRepo.save(txn);
        AuditLog auditLog =  auditService.buildAudit(account, txn, op, account.getBalance(), account.getBalance(), reason);
        auditRepo.save(auditLog);
        throw new BankingException(reason);
    }

    private Transaction failReversalTxn(Transaction reversal, UUID accountId,
                                         AuditOperation op, FailureReason reason) {
        reversal.setStatus(TransactionStatus.FAILED);
        reversal.setFailureReason(reason);
        txnRepo.save(reversal);
        Account account = lockAccount(accountId);
        AuditLog auditLog =  auditService.buildAudit(account, reversal, op, account.getBalance(), account.getBalance(), reason);
        throw new BankingException(reason);
    }

    private static UUID min(UUID a, UUID b) { return a.compareTo(b) <= 0 ? a : b; }
    private static UUID max(UUID a, UUID b) { return a.compareTo(b) >= 0 ? a : b; }
}
