package com.banking.service;

import com.banking.entity.Account;
import com.banking.entity.AuditLog;
import com.banking.entity.Transaction;
import com.banking.enums.AuditOperation;
import com.banking.enums.FailureReason;
import com.banking.repository.AuditLogRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class AuditService {

    private final AuditLogRepository auditRepo;

    public AuditService(AuditLogRepository auditRepo) {
        this.auditRepo = auditRepo;
    }

    public List<AuditLog> getAccountHistory(UUID accountId) {
        return auditRepo.findByAccountIdOrderByCreatedAtDesc(accountId);
    }

    public List<AuditLog> getTransactionAudit(UUID transactionId) {
        return auditRepo.findByTransactionIdOrderByCreatedAtAsc(transactionId);
    }

    public AuditLog buildAudit(Account account, Transaction transaction, AuditOperation operation,
                                long balanceBefore, long balanceAfter, FailureReason failureReason) {
        AuditLog auditLog = new AuditLog();
        auditLog.setAccount(account);
        auditLog.setTransaction(transaction);
        auditLog.setOperation(operation);
        auditLog.setAmount(transaction.getAmount());
        auditLog.setBalanceBefore(balanceBefore);
        auditLog.setBalanceAfter(balanceAfter);
        auditLog.setFailureReason(failureReason);
        return auditLog;
    }
}
