# Banking Service

## Running

```bash
# 1. Start Postgres
docker run -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=banking -p 5432:5432 postgres:16

# 2. Apply schema
psql -U postgres -d banking -f src/main/resources/schema.sql

# 3. Run
mvn spring-boot:run
```

---

## Balance representation

Balances are stored as **BIGINT in paise (1 INR = 100 paise)**. Rs 1,234.56 -> 123456.

**Why not FLOAT?** Floating-point cannot represent most decimal fractions exactly — 0.1 + 0.2 = 0.30000000000000004. Storing in the smallest integer unit eliminates rounding errors entirely.

**Floor:** Balance never goes below zero — enforced at service layer + DB CHECK constraint.

---

## Concurrency

**Pessimistic locking (SELECT FOR UPDATE)** is used inside every balance-changing transaction. Optimistic locking was considered but rejected: under contention it produces retries that are harder to reason about for financial correctness.

**Deadlock prevention:** Transfers always acquire locks in **ascending UUID order** regardless of transfer direction. Both concurrent threads acquire the same first lock, so they serialize naturally and deadlock is impossible.

---

## Reversal design

A reversal is a Transaction with type=REVERSAL and original_transaction_id pointing to the original. No separate table needed.

**Exactly-once enforcement:**
- Reversal idempotency_key is always "reversal:{original_txn_id}"
- UNIQUE constraint on idempotency_key means a second attempt finds the key already exists
- Original transaction status is flipped to REVERSED as a guard

Only SUCCESS transactions can be reversed. FAILED and REVERSED transactions are rejected.

---

## Idempotency

Every operation requires a caller-supplied idempotencyKey. Stored with UNIQUE constraint. On retry, the existing transaction is returned (HTTP 200) — no double-processing.

---

## Assumptions

1. Single currency, no FX conversion
2. Balance floor = 0 (no overdraft)
3. Reversal of a reversal is not supported
4. FROZEN accounts can receive deposits but cannot send money
5. CLOSED accounts reject all operations
6. API amounts are always in paise; display conversion is the caller's responsibility
