package com.banking.service;

import com.banking.entity.Account;
import com.banking.enums.AccountStatus;
import com.banking.exception.BankingException;
import com.banking.enums.FailureReason;
import com.banking.repository.AccountRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepo;

    public AccountService(AccountRepository accountRepo) {
        this.accountRepo = accountRepo;
    }

    public List<Account> listAccounts() {
        return accountRepo.findAll(Sort.by(Sort.Direction.ASC, "createdAt"));
    }

    @Transactional
    public Account createAccount(String ownerName, Long initialBalancePaise) {
        Account account = new Account();
        account.setOwnerName(ownerName);
        account.setBalance(initialBalancePaise != null ? initialBalancePaise : 0L);
        return accountRepo.save(account);
    }

    public Account getAccount(UUID id) {
        return accountRepo.findById(id)
                .orElseThrow(() -> new BankingException(FailureReason.ACCOUNT_NOT_FOUND));
    }

    @Transactional
    public Account updateStatus(UUID id, AccountStatus status) {
        Account account = getAccount(id);
        account.setStatus(status);
        return accountRepo.save(account);
    }
}
