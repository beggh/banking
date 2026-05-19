package com.banking.controller;

import com.banking.entity.AuditLog;
import com.banking.service.AuditService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/account/{accountId}")
    public ResponseEntity<List<AuditLog>> accountHistory(@PathVariable UUID accountId) {
        return ResponseEntity.ok(auditService.getAccountHistory(accountId));
    }

    @GetMapping("/transaction/{transactionId}")
    public ResponseEntity<List<AuditLog>> transactionAudit(@PathVariable UUID transactionId) {
        return ResponseEntity.ok(auditService.getTransactionAudit(transactionId));
    }
}
