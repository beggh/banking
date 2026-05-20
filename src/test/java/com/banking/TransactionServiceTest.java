package com.banking;

import com.banking.dto.*;
import com.banking.entity.*;
import com.banking.enums.*;
import com.banking.exception.BankingException;
import com.banking.exception.DuplicateRequestException;
import com.banking.repository.AccountRepository;
import com.banking.repository.AuditLogRepository;
import com.banking.repository.TransactionRepository;
import com.banking.service.AccountService;
import com.banking.service.TransactionService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional               // each test rolls back — DB is clean per test
@DisplayName("Transaction Service Tests")
class TransactionServiceTest {

    @Autowired TransactionService txnService;
    @Autowired AccountService     accountService;
    @Autowired AccountRepository  accountRepo;
    @Autowired TransactionRepository txnRepo;
    @Autowired AuditLogRepository auditRepo;

    // ── helpers ───────────────────────────────────────────────────────────────

    private Account createAccount(String name, long balance) {
        return accountService.createAccount(name, balance);
    }

    private DepositRequest depositReq(UUID accountId, long amount) {
        DepositRequest r = new DepositRequest();
        r.setAccountId(accountId);
        r.setAmount(amount);
        r.setIdempotencyKey(UUID.randomUUID().toString());
        return r;
    }

    private WithdrawalRequest withdrawReq(UUID accountId, long amount) {
        WithdrawalRequest r = new WithdrawalRequest();
        r.setAccountId(accountId);
        r.setAmount(amount);
        r.setIdempotencyKey(UUID.randomUUID().toString());
        return r;
    }

    private TransferRequest transferReq(UUID from, UUID to, long amount) {
        TransferRequest r = new TransferRequest();
        r.setFromAccountId(from);
        r.setToAccountId(to);
        r.setAmount(amount);
        r.setIdempotencyKey(UUID.randomUUID().toString());
        return r;
    }

    private ReversalRequest reversalReq(UUID txnId) {
        ReversalRequest r = new ReversalRequest();
        r.setTransactionId(txnId);
        return r;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DEPOSIT
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Deposit")
    class DepositTests {

        @Test
        @DisplayName("happy path — balance increases, audit row written")
        void deposit_success() {
            Account acc = createAccount("Alice", 0L);

            Transaction txn = txnService.deposit(depositReq(acc.getId(), 50_000L));

            assertThat(txn.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
            assertThat(txn.getType()).isEqualTo(TransactionType.DEPOSIT);

            long balance = accountRepo.findById(acc.getId()).orElseThrow().getBalance();
            assertThat(balance).isEqualTo(50_000L);

            List<AuditLog> logs = auditRepo.findByTransactionIdOrderByCreatedAtAsc(txn.getId());
            assertThat(logs).hasSize(1);
            assertThat(logs.get(0).getOperation()).isEqualTo(AuditOperation.CREDIT);
            assertThat(logs.get(0).getBalanceBefore()).isEqualTo(0L);
            assertThat(logs.get(0).getBalanceAfter()).isEqualTo(50_000L);
        }

        @Test
        @DisplayName("deposit to FROZEN account succeeds (only outgoing is blocked)")
        void deposit_frozenAccount_succeeds() {
            Account acc = createAccount("Bob", 0L);
            accountService.updateStatus(acc.getId(), AccountStatus.FROZEN);

            Transaction txn = txnService.deposit(depositReq(acc.getId(), 10_000L));

            assertThat(txn.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
            assertThat(accountRepo.findById(acc.getId()).orElseThrow().getBalance()).isEqualTo(10_000L);
        }

        @Test
        @DisplayName("deposit to CLOSED account fails and is audited")
        void deposit_closedAccount_fails() {
            Account acc = createAccount("Charlie", 1_000L);
            accountService.updateStatus(acc.getId(), AccountStatus.CLOSED);

            assertThatThrownBy(() -> txnService.deposit(depositReq(acc.getId(), 500L)))
                    .isInstanceOf(BankingException.class)
                    .satisfies(e -> assertThat(((BankingException) e).getReason())
                            .isEqualTo(FailureReason.ACCOUNT_CLOSED));

            // balance unchanged
            assertThat(accountRepo.findById(acc.getId()).orElseThrow().getBalance()).isEqualTo(1_000L);

            // FAILED txn persisted
            List<Transaction> txns = txnRepo.findAll().stream()
                    .filter(t -> t.getType() == TransactionType.DEPOSIT && t.getStatus() == TransactionStatus.FAILED)
                    .toList();
            assertThat(txns).isNotEmpty();
        }

        @Test
        @DisplayName("duplicate idempotency key returns original transaction")
        void deposit_duplicateIdempotencyKey() {
            Account acc = createAccount("Dave", 0L);
            DepositRequest req = depositReq(acc.getId(), 20_000L);

            Transaction first  = txnService.deposit(req);
            // same request replayed
            assertThatThrownBy(() -> txnService.deposit(req))
                    .isInstanceOf(DuplicateRequestException.class)
                    .satisfies(e -> assertThat(((DuplicateRequestException) e).getExisting().getId())
                            .isEqualTo(first.getId()));

            // balance credited only once
            assertThat(accountRepo.findById(acc.getId()).orElseThrow().getBalance()).isEqualTo(20_000L);
        }

        @Test
        @DisplayName("deposit to non-existent account throws ACCOUNT_NOT_FOUND")
        void deposit_accountNotFound() {
            DepositRequest req = depositReq(UUID.randomUUID(), 1_000L);
            assertThatThrownBy(() -> txnService.deposit(req))
                    .isInstanceOf(BankingException.class)
                    .satisfies(e -> assertThat(((BankingException) e).getReason())
                            .isEqualTo(FailureReason.ACCOUNT_NOT_FOUND));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // WITHDRAWAL
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Withdrawal")
    class WithdrawalTests {

        @Test
        @DisplayName("happy path — balance decreases, audit row written")
        void withdraw_success() {
            Account acc = createAccount("Eve", 100_000L);

            Transaction txn = txnService.withdraw(withdrawReq(acc.getId(), 30_000L));

            assertThat(txn.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
            assertThat(accountRepo.findById(acc.getId()).orElseThrow().getBalance()).isEqualTo(70_000L);

            List<AuditLog> logs = auditRepo.findByTransactionIdOrderByCreatedAtAsc(txn.getId());
            assertThat(logs).hasSize(1);
            assertThat(logs.get(0).getOperation()).isEqualTo(AuditOperation.DEBIT);
            assertThat(logs.get(0).getBalanceBefore()).isEqualTo(100_000L);
            assertThat(logs.get(0).getBalanceAfter()).isEqualTo(70_000L);
        }

        @Test
        @DisplayName("exact balance withdrawal succeeds — balance reaches zero")
        void withdraw_exactBalance() {
            Account acc = createAccount("Frank", 5_000L);
            Transaction txn = txnService.withdraw(withdrawReq(acc.getId(), 5_000L));
            assertThat(txn.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
            assertThat(accountRepo.findById(acc.getId()).orElseThrow().getBalance()).isEqualTo(0L);
        }

        @Test
        @DisplayName("insufficient funds — FAILED with audit row, balance unchanged")
        void withdraw_insufficientFunds() {
            Account acc = createAccount("Grace", 1_000L);

            assertThatThrownBy(() -> txnService.withdraw(withdrawReq(acc.getId(), 2_000L)))
                    .isInstanceOf(BankingException.class)
                    .satisfies(e -> assertThat(((BankingException) e).getReason())
                            .isEqualTo(FailureReason.INSUFFICIENT_FUNDS));

            assertThat(accountRepo.findById(acc.getId()).orElseThrow().getBalance()).isEqualTo(1_000L);
        }

        @Test
        @DisplayName("withdrawal from FROZEN account rejected")
        void withdraw_frozenAccount() {
            Account acc = createAccount("Heidi", 50_000L);
            accountService.updateStatus(acc.getId(), AccountStatus.FROZEN);

            assertThatThrownBy(() -> txnService.withdraw(withdrawReq(acc.getId(), 1_000L)))
                    .isInstanceOf(BankingException.class)
                    .satisfies(e -> assertThat(((BankingException) e).getReason())
                            .isEqualTo(FailureReason.ACCOUNT_FROZEN));

            assertThat(accountRepo.findById(acc.getId()).orElseThrow().getBalance()).isEqualTo(50_000L);
        }

        @Test
        @DisplayName("withdrawal from CLOSED account rejected")
        void withdraw_closedAccount() {
            Account acc = createAccount("Ivan", 10_000L);
            accountService.updateStatus(acc.getId(), AccountStatus.CLOSED);

            assertThatThrownBy(() -> txnService.withdraw(withdrawReq(acc.getId(), 5_000L)))
                    .isInstanceOf(BankingException.class)
                    .satisfies(e -> assertThat(((BankingException) e).getReason())
                            .isEqualTo(FailureReason.ACCOUNT_CLOSED));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TRANSFER
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Transfer")
    class TransferTests {

        @Test
        @DisplayName("happy path — source debited, target credited, two audit rows")
        void transfer_success() {
            Account alice = createAccount("Alice", 100_000L);
            Account bob   = createAccount("Bob", 50_000L);

            Transaction txn = txnService.transfer(transferReq(alice.getId(), bob.getId(), 25_000L));

            assertThat(txn.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
            assertThat(accountRepo.findById(alice.getId()).orElseThrow().getBalance()).isEqualTo(75_000L);
            assertThat(accountRepo.findById(bob.getId()).orElseThrow().getBalance()).isEqualTo(75_000L);

            // Two audit rows: one DEBIT on Alice, one CREDIT on Bob
            List<AuditLog> logs = auditRepo.findByTransactionIdOrderByCreatedAtAsc(txn.getId());
            assertThat(logs).hasSize(2);

            AuditLog debit  = logs.stream().filter(l -> l.getOperation() == AuditOperation.DEBIT).findFirst().orElseThrow();
            AuditLog credit = logs.stream().filter(l -> l.getOperation() == AuditOperation.CREDIT).findFirst().orElseThrow();

            assertThat(debit.getAccount()).isEqualTo(alice);
            assertThat(debit.getBalanceBefore()).isEqualTo(100_000L);
            assertThat(debit.getBalanceAfter()).isEqualTo(75_000L);

            assertThat(credit.getAccount()).isEqualTo(bob);
            assertThat(credit.getBalanceBefore()).isEqualTo(50_000L);
            assertThat(credit.getBalanceAfter()).isEqualTo(75_000L);
        }

        @Test
        @DisplayName("money is conserved — total sum unchanged after transfer")
        void transfer_moneyConservation() {
            Account a = createAccount("A", 80_000L);
            Account b = createAccount("B", 20_000L);
            long total = 100_000L;

            txnService.transfer(transferReq(a.getId(), b.getId(), 30_000L));

            long newA = accountRepo.findById(a.getId()).orElseThrow().getBalance();
            long newB = accountRepo.findById(b.getId()).orElseThrow().getBalance();
            assertThat(newA + newB).isEqualTo(total);
        }

        @Test
        @DisplayName("same-account transfer rejected immediately")
        void transfer_sameAccount() {
            Account acc = createAccount("Mallory", 10_000L);
            assertThatThrownBy(() -> txnService.transfer(transferReq(acc.getId(), acc.getId(), 1_000L)))
                    .isInstanceOf(BankingException.class)
                    .satisfies(e -> assertThat(((BankingException) e).getReason())
                            .isEqualTo(FailureReason.SAME_ACCOUNT_TRANSFER));
        }

        @Test
        @DisplayName("source with insufficient funds — both balances unchanged")
        void transfer_insufficientFunds() {
            Account a = createAccount("A", 500L);
            Account b = createAccount("B", 1_000L);

            assertThatThrownBy(() -> txnService.transfer(transferReq(a.getId(), b.getId(), 1_000L)))
                    .isInstanceOf(BankingException.class)
                    .satisfies(e -> assertThat(((BankingException) e).getReason())
                            .isEqualTo(FailureReason.INSUFFICIENT_FUNDS));

            assertThat(accountRepo.findById(a.getId()).orElseThrow().getBalance()).isEqualTo(500L);
            assertThat(accountRepo.findById(b.getId()).orElseThrow().getBalance()).isEqualTo(1_000L);
        }

        @Test
        @DisplayName("source FROZEN — transfer rejected, target balance unchanged")
        void transfer_sourceFrozen() {
            Account src = createAccount("Src", 10_000L);
            Account tgt = createAccount("Tgt", 0L);
            accountService.updateStatus(src.getId(), AccountStatus.FROZEN);

            assertThatThrownBy(() -> txnService.transfer(transferReq(src.getId(), tgt.getId(), 500L)))
                    .isInstanceOf(BankingException.class)
                    .satisfies(e -> assertThat(((BankingException) e).getReason())
                            .isEqualTo(FailureReason.ACCOUNT_FROZEN));

            assertThat(accountRepo.findById(tgt.getId()).orElseThrow().getBalance()).isEqualTo(0L);
        }

        @Test
        @DisplayName("target CLOSED — transfer rejected, source balance unchanged")
        void transfer_targetClosed() {
            Account src = createAccount("Src", 10_000L);
            Account tgt = createAccount("Tgt", 0L);
            accountService.updateStatus(tgt.getId(), AccountStatus.CLOSED);

            assertThatThrownBy(() -> txnService.transfer(transferReq(src.getId(), tgt.getId(), 500L)))
                    .isInstanceOf(BankingException.class)
                    .satisfies(e -> assertThat(((BankingException) e).getReason())
                            .isEqualTo(FailureReason.ACCOUNT_CLOSED));

            assertThat(accountRepo.findById(src.getId()).orElseThrow().getBalance()).isEqualTo(10_000L);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // REVERSAL
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Reversal")
    class ReversalTests {

        @Test
        @DisplayName("reversal of DEPOSIT — balance restored, original marked REVERSED")
        void reversal_deposit() {
            Account acc = createAccount("Alice", 0L);
            Transaction deposit = txnService.deposit(depositReq(acc.getId(), 50_000L));

            Transaction reversal = txnService.reverse(reversalReq(deposit.getId()));

            assertThat(reversal.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
            assertThat(reversal.getType()).isEqualTo(TransactionType.REVERSAL);
            assertThat(reversal.getOriginalTransactionId()).isEqualTo(deposit.getId());

            assertThat(accountRepo.findById(acc.getId()).orElseThrow().getBalance()).isEqualTo(0L);

            Transaction original = txnRepo.findById(deposit.getId()).orElseThrow();
            assertThat(original.getStatus()).isEqualTo(TransactionStatus.REVERSED);
        }

        @Test
        @DisplayName("reversal of WITHDRAWAL — amount credited back")
        void reversal_withdrawal() {
            Account acc = createAccount("Bob", 100_000L);
            Transaction withdrawal = txnService.withdraw(withdrawReq(acc.getId(), 30_000L));

            txnService.reverse(reversalReq(withdrawal.getId()));

            assertThat(accountRepo.findById(acc.getId()).orElseThrow().getBalance()).isEqualTo(100_000L);
        }

        @Test
        @DisplayName("reversal of TRANSFER — both balances restored, total conserved")
        void reversal_transfer() {
            Account alice = createAccount("Alice", 100_000L);
            Account bob   = createAccount("Bob",   50_000L);
            long total    = 150_000L;

            Transaction transfer = txnService.transfer(transferReq(alice.getId(), bob.getId(), 40_000L));
            txnService.reverse(reversalReq(transfer.getId()));

            long newAlice = accountRepo.findById(alice.getId()).orElseThrow().getBalance();
            long newBob   = accountRepo.findById(bob.getId()).orElseThrow().getBalance();

            assertThat(newAlice).isEqualTo(100_000L);
            assertThat(newBob).isEqualTo(50_000L);
            assertThat(newAlice + newBob).isEqualTo(total);
        }

        @Test
        @DisplayName("reversal produces correct REVERSAL_* audit entries")
        void reversal_auditRows() {
            Account alice = createAccount("Alice", 100_000L);
            Account bob   = createAccount("Bob", 0L);
            Transaction transfer = txnService.transfer(transferReq(alice.getId(), bob.getId(), 20_000L));
            Transaction reversal = txnService.reverse(reversalReq(transfer.getId()));

            List<AuditLog> logs = auditRepo.findByTransactionIdOrderByCreatedAtAsc(reversal.getId());
            assertThat(logs).hasSize(2);

            AuditLog credit = logs.stream()
                    .filter(l -> l.getOperation() == AuditOperation.REVERSAL_CREDIT).findFirst().orElseThrow();
            AuditLog debit  = logs.stream()
                    .filter(l -> l.getOperation() == AuditOperation.REVERSAL_DEBIT).findFirst().orElseThrow();

            assertThat(credit.getAccount()).isEqualTo(alice);  // money returned to Alice
            assertThat(debit.getAccount()).isEqualTo(bob);      // debited from Bob
        }

        @Test
        @DisplayName("double reversal attempt — second call throws ALREADY_REVERSED")
        void reversal_doubleReversal_rejected() {
            Account acc     = createAccount("Alice", 50_000L);
            Transaction dep = txnService.deposit(depositReq(acc.getId(), 10_000L));
            txnService.reverse(reversalReq(dep.getId()));

            // Second reversal of the same txn must be rejected
            assertThatThrownBy(() -> txnService.reverse(reversalReq(dep.getId())))
                    .isInstanceOf(DuplicateRequestException.class);

            // Balance must remain unchanged after second attempt
            assertThat(accountRepo.findById(acc.getId()).orElseThrow().getBalance()).isEqualTo(50_000L);
        }

        @Test
        @DisplayName("reversing a FAILED transaction throws REVERSAL_NOT_ALLOWED")
        void reversal_of_failedTxn_rejected() {
            Account acc = createAccount("Alice", 100L);

            // This will FAIL (insufficient funds)
            assertThatThrownBy(() -> txnService.withdraw(withdrawReq(acc.getId(), 500L)));

            Transaction failedTxn = txnRepo.findAll().stream()
                    .filter(t -> t.getStatus() == TransactionStatus.FAILED)
                    .findFirst().orElseThrow();

            assertThatThrownBy(() -> txnService.reverse(reversalReq(failedTxn.getId())))
                    .isInstanceOf(BankingException.class)
                    .satisfies(e -> assertThat(((BankingException) e).getReason())
                            .isEqualTo(FailureReason.REVERSAL_NOT_ALLOWED));
        }

        @Test
        @DisplayName("reversing a non-existent transaction throws TRANSACTION_NOT_FOUND")
        void reversal_txnNotFound() {
            assertThatThrownBy(() -> txnService.reverse(reversalReq(UUID.randomUUID())))
                    .isInstanceOf(BankingException.class)
                    .satisfies(e -> assertThat(((BankingException) e).getReason())
                            .isEqualTo(FailureReason.TRANSACTION_NOT_FOUND));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // AUDIT LOG
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Audit Log")
    class AuditTests {

        @Test
        @DisplayName("failed operation is persisted in audit log with outcome FAILURE")
        void audit_failureIsPersisted() {
            Account acc = createAccount("Alice", 0L);
            assertThatThrownBy(() -> txnService.withdraw(withdrawReq(acc.getId(), 500L)));

            List<AuditLog> logs = auditRepo.findByAccountIdOrderByCreatedAtDesc(acc.getId());
            assertThat(logs).isNotEmpty();

            AuditLog log = logs.get(0);
            assertThat(log.getFailureReason()).isEqualTo(FailureReason.INSUFFICIENT_FUNDS);
            assertThat(log.getBalanceBefore()).isEqualTo(0);
            assertThat(log.getBalanceAfter()).isEqualTo(0);
        }

        @Test
        @DisplayName("account history shows all operations in descending order")
        void audit_accountHistory() {
            Account acc = createAccount("Alice", 100_000L);
            txnService.deposit(depositReq(acc.getId(), 10_000L));
            txnService.deposit(depositReq(acc.getId(), 20_000L));
            txnService.withdraw(withdrawReq(acc.getId(), 5_000L));

            List<AuditLog> logs = auditRepo.findByAccountIdOrderByCreatedAtDesc(acc.getId());
            assertThat(logs).hasSize(3);
        }
    }
}
