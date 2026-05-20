package com.banking.controller;

import com.banking.entity.Account;
import com.banking.enums.AccountStatus;
import com.banking.service.AccountService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    public ResponseEntity<List<Account>> list() {
        return ResponseEntity.ok(accountService.listAccounts());
    }

    @PostMapping
    public ResponseEntity<Account> create(@RequestBody Map<String, Object> body) {
        String ownerName = (String) body.get("ownerName");
        Long initialBalance = body.containsKey("initialBalance")
                ? Long.parseLong(body.get("initialBalance").toString()) : 0L;
        return ResponseEntity.status(201).body(accountService.createAccount(ownerName, initialBalance));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Account> get(@PathVariable UUID id) {
        return ResponseEntity.ok(accountService.getAccount(id));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<Account> updateStatus(@PathVariable UUID id,
                                                 @RequestBody Map<String, String> body) {
        AccountStatus status = AccountStatus.valueOf(body.get("status"));
        return ResponseEntity.ok(accountService.updateStatus(id, status));
    }
}
