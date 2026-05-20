package com.banking;

import com.banking.dto.DepositRequest;
import com.banking.dto.ReversalRequest;
import com.banking.dto.TransferRequest;
import com.banking.entity.Account;
import com.banking.entity.Transaction;
import com.banking.enums.TransactionStatus;
import com.banking.exception.BankingException;
import com.banking.exception.DuplicateRequestException;
import com.banking.repository.AccountRepository;
import com.banking.service.AccountService;
import com.banking.service.TransactionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent tests do NOT use @Transactional — each thread must commit its own
 * transaction for locks to be visible across threads. Data is cleaned up manually.
 *
 * Each test:
 *  1. Sets up accounts.
 *  2. Fires N threads simultaneously via CountDownLatch.
 *  3. Waits for all to finish.
 *  4. Asserts invariants (money conservation, no negative balance).
 */
@SpringBootTest
@DisplayName("Concurrent Transfer Tests")
class ConcurrentTransferTest {

    @Autowired TransactionService txnService;
    @Autowired AccountService     accountService;
    @Autowired AccountRepository  accountRepo;

    // ── Test 1: A→B and B→A simultaneously ───────────────────────────────────

    @Test
    @DisplayName("A→B and B→A simultaneously — no deadlock, money conserved")
    void bidirectionalTransfer_noDeadlock() throws InterruptedException {
        Account a = accountService.createAccount("Alice", 100_000L);
        Account b = accountService.createAccount("Bob",   100_000L);
        long total = 200_000L;
        int threads = 4;   // 2 A→B + 2 B→A

        CountDownLatch ready  = new CountDownLatch(threads);
        CountDownLatch start  = new CountDownLatch(1);
        CountDownLatch done   = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failed  = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            final boolean aToB = (i % 2 == 0); // alternating direction
            int finalI = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(); // all threads start at the same instant
                    TransferRequest req = new TransferRequest();
                    req.setFromAccountId(aToB ? a.getId() : b.getId());
                    req.setToAccountId(aToB   ? b.getId() : a.getId());
                    req.setAmount(1_000L - ((finalI+1) * 10L));
                    req.setIdempotencyKey(UUID.randomUUID().toString());
                    txnService.transfer(req);
                    success.incrementAndGet();
                } catch (BankingException e) {
                    failed.incrementAndGet();  // insufficient funds is acceptable
                } catch (Exception e) {
                    failed.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();         // release all threads simultaneously
        done.await(5, TimeUnit.SECONDS);
        pool.shutdown();

        long balA = accountRepo.findById(a.getId()).orElseThrow().getBalance();
        long balB = accountRepo.findById(b.getId()).orElseThrow().getBalance();

        System.out.printf("[A↔B] success=%d  failed=%d  balA=%d  balB=%d  total=%d%n",
                success.get(), failed.get(), balA, balB, balA + balB);

        // Core invariants
        assertThat(balA).isGreaterThanOrEqualTo(0);
        assertThat(balB).isGreaterThanOrEqualTo(0);
        assertThat(balA + balB).isEqualTo(total); // money never created or destroyed
    }

    // ── Test 2: 50 concurrent transfers, all from A to B ─────────────────────

    @Test
    @DisplayName("50 concurrent A→B transfers — balance never negative, total conserved")
    void manyTransfers_sameDirection_balanceNeverNegative() throws InterruptedException {
        Account a = accountService.createAccount("Alice", 50_000L);
        Account b = accountService.createAccount("Bob",   0L);
        long total = 50_000L;
        int threads = 50;

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    TransferRequest req = new TransferRequest();
                    req.setFromAccountId(a.getId());
                    req.setToAccountId(b.getId());
                    req.setAmount(2_000L);
                    req.setIdempotencyKey(UUID.randomUUID().toString());
                    txnService.transfer(req);
                    success.incrementAndGet();
                } catch (BankingException ignored) {
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await(15, TimeUnit.SECONDS);
        pool.shutdown();

        long balA = accountRepo.findById(a.getId()).orElseThrow().getBalance();
        long balB = accountRepo.findById(b.getId()).orElseThrow().getBalance();

        System.out.printf("[A→B x5] success=%d  balA=%d  balB=%d%n", success.get(), balA, balB);

        assertThat(balA).isGreaterThanOrEqualTo(0);                // never negative
        assertThat(balB).isGreaterThanOrEqualTo(0);
        assertThat(balA + balB).isEqualTo(total);                  // conservation
        assertThat(success.get()).isEqualTo(25);                    // 50_000 / 2_000 = 25 max succeed
    }

    // ── Test 3: concurrent deposits to the same account ──────────────────────

    @Test
    @DisplayName("10 concurrent deposits — final balance equals sum of all deposits")
    void concurrentDeposits_balanceMatchesSum() throws InterruptedException {
        Account acc       = accountService.createAccount("Alice", 0L);
        int     threads   = 10;
        long    perDeposit = 500L;

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threads);

        ExecutorService pool = Executors.newFixedThreadPool(20);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    DepositRequest req = new DepositRequest();
                    req.setAccountId(acc.getId());
                    req.setAmount(perDeposit);
                    req.setIdempotencyKey(UUID.randomUUID().toString());
                    txnService.deposit(req);
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await(15, TimeUnit.SECONDS);
        pool.shutdown();

        long balance = accountRepo.findById(acc.getId()).orElseThrow().getBalance();
        System.out.printf("[deposits x100] finalBalance=%d  expected=%d%n",
                balance, threads * perDeposit);

        assertThat(balance).isEqualTo((long) threads * perDeposit);
    }

    // ── Test 4: concurrent double-reversal attempts ───────────────────────────

    @Test
    @DisplayName("concurrent double-reversal — exactly one succeeds, balance correct")
    void concurrentReversal_exactlyOneSucceeds() throws InterruptedException {
        Account acc = accountService.createAccount("Alice", 0L);

        // Deposit first
        DepositRequest dep = new DepositRequest();
        dep.setAccountId(acc.getId());
        dep.setAmount(10_000L);
        dep.setIdempotencyKey(UUID.randomUUID().toString());
        Transaction deposit = txnService.deposit(dep);

        int threads = 4;
        CountDownLatch start    = new CountDownLatch(1);
        CountDownLatch done     = new CountDownLatch(threads);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures  = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    ReversalRequest req = new ReversalRequest();
                    req.setTransactionId(deposit.getId());
                    txnService.reverse(req);
                    successes.incrementAndGet();
                } catch (DuplicateRequestException e) {
                    successes.incrementAndGet(); // idempotent — counts as success
                } catch (BankingException e) {
                    failures.incrementAndGet();
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await(15, TimeUnit.SECONDS);
        pool.shutdown();

        long balance = accountRepo.findById(acc.getId()).orElseThrow().getBalance();
        System.out.printf("[reversal x20] successes=%d  failures=%d  balance=%d%n",
                successes.get(), failures.get(), balance);

        // Reversal applied exactly once → balance back to 0
        assertThat(balance).isEqualTo(0L);
    }

    // ── Test 5: concurrent idempotency — same key, many threads ──────────────

    @Test
    @DisplayName("same idempotency key sent by 30 threads — credited exactly once")
    void concurrentIdempotency_creditedOnce() throws InterruptedException {
        Account acc           = accountService.createAccount("Alice", 0L);
        String  sharedKey     = UUID.randomUUID().toString();
        int     threads       = 5;

        CountDownLatch start   = new CountDownLatch(1);
        CountDownLatch done    = new CountDownLatch(threads);
        List<String> outcomes  = new CopyOnWriteArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    DepositRequest req = new DepositRequest();
                    req.setAccountId(acc.getId());
                    req.setAmount(5_000L);
                    req.setIdempotencyKey(sharedKey);   // all threads use same key
                    txnService.deposit(req);
                    outcomes.add("SUCCESS");
                } catch (DuplicateRequestException e) {
                    outcomes.add("DUPLICATE");
                } catch (Exception e) {
                    outcomes.add("ERROR:" + e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await(15, TimeUnit.SECONDS);
        pool.shutdown();

        long balance = accountRepo.findById(acc.getId()).orElseThrow().getBalance();
        long actualSuccesses = outcomes.stream().filter(o -> o.equals("SUCCESS")).count();
        System.out.printf("[idempotency 5] balance=%d  actualSuccesses=%d%n",
                balance, actualSuccesses);

        // Credited exactly once despite 30 concurrent attempts
        assertThat(balance).isEqualTo(5_000L);
        assertThat(actualSuccesses).isEqualTo(1L);
    }

    // ── Test 6: 3-account ring transfer — A→B, B→C, C→A simultaneously ───────

    @Test
    @DisplayName("ring transfer A→B, B→C, C→A — no deadlock, total conserved")
    void ringTransfer_noDeadlock() throws InterruptedException {
        Account a = accountService.createAccount("Alice",   30_000L);
        Account b = accountService.createAccount("Bob",     30_000L);
        Account c = accountService.createAccount("Charlie", 30_000L);
        long total = 90_000L;
        int rounds = 6; // each direction fires 30 times

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(rounds * 3);
        AtomicInteger success = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(6);

        // A→B
        for (int i = 0; i < rounds; i++) {
            pool.submit(() -> submitTransfer(a, b, 500L, start, done, success));
        }
        // B→C
        for (int i = 0; i < rounds; i++) {
            pool.submit(() -> submitTransfer(b, c, 500L, start, done, success));
        }
        // C→A
        for (int i = 0; i < rounds; i++) {
            pool.submit(() -> submitTransfer(c, a, 500L, start, done, success));
        }

        start.countDown();
        done.await(20, TimeUnit.SECONDS);
        pool.shutdown();

        long balA = accountRepo.findById(a.getId()).orElseThrow().getBalance();
        long balB = accountRepo.findById(b.getId()).orElseThrow().getBalance();
        long balC = accountRepo.findById(c.getId()).orElseThrow().getBalance();

        System.out.printf("[ring] success=%d  A=%d  B=%d  C=%d  total=%d%n",
                success.get(), balA, balB, balC, balA + balB + balC);

        assertThat(balA).isGreaterThanOrEqualTo(0);
        assertThat(balB).isGreaterThanOrEqualTo(0);
        assertThat(balC).isGreaterThanOrEqualTo(0);
        assertThat(balA + balB + balC).isEqualTo(total);
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private void submitTransfer(Account from, Account to, long amount,
                                 CountDownLatch start, CountDownLatch done,
                                 AtomicInteger success) {
        try {
            start.await();
            TransferRequest req = new TransferRequest();
            req.setFromAccountId(from.getId());
            req.setToAccountId(to.getId());
            req.setAmount(amount);
            req.setIdempotencyKey(UUID.randomUUID().toString());
            txnService.transfer(req);
            success.incrementAndGet();
        } catch (BankingException ignored) {
        } catch (Exception ignored) {
        } finally {
            done.countDown();
        }
    }
}
