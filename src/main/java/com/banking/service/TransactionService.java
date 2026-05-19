package com.banking.service;

import com.banking.dto.*;
import com.banking.entity.*;
import com.banking.enums.*;
import com.banking.exception.BankingException;
import com.banking.exception.DuplicateRequestException;
import com.banking.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class TransactionService {

    private final AccountRepository     accountRepo;
    private final TransactionRepository txnRepo;
    private final AuditLogRepository    auditRepo;
    private final AuditService auditService;

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
        AuditLog auditLog = auditService.buildAudit();

        Account account = lockAccount(req.getAccountId());

        if (account.getStatus() == AccountStatus.CLOSED) {
            return failTxn(txn, req.getAccountId(), AuditOperation.CREDIT, FailureReason.ACCOUNT_CLOSED);
        }

        long before = account.getBalance();
        account.setBalance(before + req.getAmount());
        accountRepo.save(account);

        txn.setStatus(TransactionStatus.SUCCESS);
        txnRepo.save(txn);
//        auditRepo.save(txn.getId(), account.getId(),
//                AuditOperation.CREDIT, req.getAmount(), before, account.getBalance());
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
        accountRepo.save(account);

        txn.setStatus(TransactionStatus.SUCCESS);
        txnRepo.save(txn);
//        auditRepo.save(AuditLog.success(txn.getId(), account.getId(),
//                AuditOperation.DEBIT, req.getAmount(), before, account.getBalance()));
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

        txn.setStatus(TransactionStatus.SUCCESS);
        txnRepo.save(txn);

//        auditRepo.save(AuditLog.success(txn.getId(), source.getId(),
//                AuditOperation.DEBIT,  req.getAmount(), sourceBefore, source.getBalance()));
//        auditRepo.save(AuditLog.success(txn.getId(), target.getId(),
//                AuditOperation.CREDIT, req.getAmount(), targetBefore, target.getBalance()));
        return txn;
    }

    // ─── REVERSAL ─────────────────────────────────────────────────────────────
    //
    // Idempotency key = "reversal:{original_txn_id}".
    // UNIQUE constraint on idempotency_key in DB means a second reversal
    // attempt for the same txn hits checkIdempotency() and is rejected
    // before any balance is touched — enforces exactly-once reversal.

    @Transactional(isolation = Isolation.READ_COMMITTED)
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
                Account acc   = lockAccount(original.getToAccountId());
                if (acc.getBalance() < original.getAmount())
                    return failReversalTxn(reversal, acc.getId(),
                            AuditOperation.REVERSAL_DEBIT, FailureReason.INSUFFICIENT_FUNDS);
                long before = acc.getBalance();
                acc.setBalance(before - original.getAmount());
                accountRepo.save(acc);
                reversal.setStatus(TransactionStatus.SUCCESS);
                txnRepo.save(reversal);
//                auditRepo.save(AuditLog.success(reversal.getId(), acc.getId(),
//                        AuditOperation.REVERSAL_DEBIT, original.getAmount(), before, acc.getBalance()));
            }

            case WITHDRAWAL -> {
                Account acc = lockAccount(original.getFromAccountId());
                if (acc.getStatus() == AccountStatus.CLOSED)
                    return failReversalTxn(reversal, acc.getId(),
                            AuditOperation.REVERSAL_CREDIT, FailureReason.ACCOUNT_CLOSED);
                long before = acc.getBalance();
                acc.setBalance(before + original.getAmount());
                accountRepo.save(acc);
                reversal.setStatus(TransactionStatus.SUCCESS);
                txnRepo.save(reversal);
//                auditRepo.save(AuditLog.success(reversal.getId(), acc.getId(),
//                        AuditOperation.REVERSAL_CREDIT, original.getAmount(), before, acc.getBalance()));
            }

            case TRANSFER -> {
                UUID firstId  = min(original.getFromAccountId(), original.getToAccountId());
                UUID secondId = max(original.getFromAccountId(), original.getToAccountId());
                Account first  = lockAccount(firstId);
                Account second = lockAccount(secondId);
                Account source = first.getId().equals(original.getFromAccountId()) ? first : second;
                Account target = first.getId().equals(original.getToAccountId())   ? first : second;

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
//                auditRepo.save(AuditLog.success(reversal.getId(), source.getId(),
//                        AuditOperation.REVERSAL_CREDIT, original.getAmount(), sourceBefore, source.getBalance()));
//                auditRepo.save(AuditLog.success(reversal.getId(), target.getId(),
//                        AuditOperation.REVERSAL_DEBIT,  original.getAmount(), targetBefore, target.getBalance()));
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
        txnRepo.save(txn);
//        auditRepo.save(auditService.failure(txn.getId(), accountId, op, txn.getAmount(), reason));
        throw new BankingException(reason);
    }

    private Transaction failReversalTxn(Transaction reversal, UUID accountId,
                                         AuditOperation op, FailureReason reason) {
        reversal.setStatus(TransactionStatus.FAILED);
        reversal.setFailureReason(reason);
        txnRepo.save(reversal);
//        auditRepo.save(AuditLog.failure(reversal.getId(), accountId, op, reversal.getAmount(), reason));
        throw new BankingException(reason);
    }

    private static UUID min(UUID a, UUID b) { return a.compareTo(b) <= 0 ? a : b; }
    private static UUID max(UUID a, UUID b) { return a.compareTo(b) >= 0 ? a : b; }
}
