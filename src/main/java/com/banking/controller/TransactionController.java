package com.banking.controller;

import com.banking.dto.*;
import com.banking.entity.Transaction;
import com.banking.exception.DuplicateRequestException;
import com.banking.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {
    @Autowired
    private final TransactionService txnService;

    public TransactionController(TransactionService txnService) {
        this.txnService = txnService;
    }

    @PostMapping("/deposit")
    public ResponseEntity<Transaction> deposit(@Validated @RequestBody DepositRequest req) {
        try {
            return ResponseEntity.status(201).body(txnService.deposit(req));
        } catch (DuplicateRequestException e) {
            return ResponseEntity.ok(e.getExisting());
        }
    }

    @PostMapping("/withdraw")
    public ResponseEntity<Transaction> withdraw(@Valid @RequestBody WithdrawalRequest req) {
        try {
            return ResponseEntity.status(201).body(txnService.withdraw(req));
        } catch (DuplicateRequestException e) {
            return ResponseEntity.ok(e.getExisting());
        }
    }

    @PostMapping("/transfer")
    public ResponseEntity<Transaction> transfer(@Valid @RequestBody TransferRequest req) {
        try {
            return ResponseEntity.status(201).body(txnService.transfer(req));
        } catch (DuplicateRequestException e) {
            return ResponseEntity.ok(e.getExisting());
        }
    }

    @PostMapping("/reverse")
    public ResponseEntity<Transaction> reverse(@Valid @RequestBody ReversalRequest req) {
        try {
            return ResponseEntity.status(201).body(txnService.reverse(req));
        } catch (DuplicateRequestException e) {
            return ResponseEntity.ok(e.getExisting());
        }
    }
}
