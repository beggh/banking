# Banking Service

A Spring Boot REST API for a single-currency banking ledger with pessimistic locking, idempotency, and a full audit log.

## Prerequisites

- Java 17+
- Maven 3.8+
- PostgreSQL 14+ running locally (not Docker)

## Database Setup

```bash
createdb banking
```

`application.properties` sets `spring.jpa.hibernate.ddl-auto=create` — Hibernate creates all tables automatically on first startup. No need to run `schema.sql`: all enum fields use `@Enumerated(EnumType.STRING)` and are stored as VARCHAR, so Hibernate handles the full DDL. (`schema.sql` exists as a reference only.)

Default connection: `postgres:postgres@localhost:5432/banking`. Edit `src/main/resources/application.properties` to change credentials.

## Running the App

```bash
mvn spring-boot:run
```

API base: http://localhost:8080
Browser UI: http://localhost:8080/index.html

## API Reference

All amounts are in paise (1 INR = 100 paise). All IDs are UUIDs.

### Accounts

#### Create account

```bash
curl -s -X POST http://localhost:8080/api/accounts \
-H 'Content-Type: application/json' \
-d '{"ownerName": "Alice", "initialBalance": 100000}'
```

#### Get account

```bash
curl -s http://localhost:8080/api/accounts/{id}
```

#### List all accounts

```bash
curl -s http://localhost:8080/api/accounts
```

#### Update account status (`ACTIVE` | `FROZEN` | `CLOSED`)

```bash
curl -s -X PATCH http://localhost:8080/api/accounts/{id}/status \
-H 'Content-Type: application/json' \
-d '{"status": "FROZEN"}'
```

### Transactions

Every mutating request requires a caller-supplied `idempotencyKey`. On retry with the same key, the existing transaction is returned (HTTP 200) — no double-processing.

#### Deposit

```bash
curl -s -X POST http://localhost:8080/api/transactions/deposit \
-H 'Content-Type: application/json' \
-d '{
"accountId": "<uuid>",
"amount": 50000,
"idempotencyKey": "dep-001"
}'
```

#### Withdraw

```bash
curl -s -X POST http://localhost:8080/api/transactions/withdraw \
-H 'Content-Type: application/json' \
-d '{
"accountId": "<uuid>",
"amount": 10000,
"idempotencyKey": "wd-001"
}'
```

#### Transfer

```bash
curl -s -X POST http://localhost:8080/api/transactions/transfer \
-H 'Content-Type: application/json' \
-d '{
"fromAccountId": "<uuid>",
"toAccountId": "<uuid>",
"amount": 5000,
"idempotencyKey": "txfr-001"
}'
```

#### Reverse a transaction

```bash
curl -s -X POST http://localhost:8080/api/transactions/reverse \
-H 'Content-Type: application/json' \
-d '{
"transactionId": "<original-txn-uuid>"
}'
```

#### List all transactions

```bash
curl -s http://localhost:8080/api/transactions
```

### Audit

#### Audit log for an account

```bash
curl -s http://localhost:8080/api/audit/account/{accountId}
```

#### Audit log for a transaction

```bash
curl -s http://localhost:8080/api/audit/transaction/{transactionId}
```

## Running Tests

The test suite requires a live PostgreSQL banking database (same as the app).

```bash
# Unit / service tests
mvn test -Dtest=TransactionServiceTest

# Concurrency tests
mvn test -Dtest=ConcurrentTransferTest
```

### What the concurrency tests verify

| Test | Scenario | Assertion |
|------|----------|-----------|
| `bidirectionalTransfer_noDeadlock` | 4 threads — 2 doing A→B, 2 doing B→A simultaneously | No deadlock; balA + balB equals starting total |
| `manyTransfers_sameDirection_balanceNeverNegative` | 50 threads each try to transfer 2,000 paise from A (50,000 balance) | Exactly 25 succeed; balance never goes negative; total conserved |
| `concurrentDeposits_balanceMatchesSum` | 10 threads deposit 500 paise each to the same account | Final balance = 5,000 (all 10 credited, no lost update) |
| `concurrentReversal_exactlyOneSucceeds` | 4 threads all try to reverse the same transaction | Reversal applied exactly once; balance back to pre-deposit value |
| `concurrentIdempotency_creditedOnce` | 5 threads send a deposit with the same idempotency key | Account credited exactly once despite 5 concurrent calls |
| `ringTransfer_noDeadlock` | 18 threads doing A→B, B→C, and C→A simultaneously | No deadlock; balA + balB + balC equals starting total |

### Manual Concurrency Test (curl + parallel)

Create two accounts, capture their IDs, then fire parallel transfers:

```bash
# 1. Create accounts
A=$(curl -s -X POST http://localhost:8080/api/accounts \
-H 'Content-Type: application/json' \
-d '{"ownerName":"Alice","initialBalance":100000}' | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")

B=$(curl -s -X POST http://localhost:8080/api/accounts \
-H 'Content-Type: application/json' \
-d '{"ownerName":"Bob","initialBalance":0}' | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")

echo "Alice: $A"
echo "Bob:   $B"

# 2. Fire 20 parallel transfers of 1,000 paise each (only 100 can succeed)
for i in $(seq 1 20); do
curl -s -X POST http://localhost:8080/api/transactions/transfer \
-H 'Content-Type: application/json' \
-d "{\"fromAccountId\":\"$A\",\"toAccountId\":\"$B\",\"amount\":1000,\"idempotencyKey\":\"manual-$i\"}" &
done
wait

# 3. Check balances — must sum to 100,000
curl -s http://localhost:8080/api/accounts/$A | python3 -c "import sys,json; d=json.load(sys.stdin); print(f\"Alice balance: {d['balance']}\")"
curl -s http://localhost:8080/api/accounts/$B | python3 -c "import sys,json; d=json.load(sys.stdin); print(f\"Bob   balance: {d['balance']}\")"
```

## Design & Trade-offs

### Balance representation

Stored as BIGINT in **paise** (smallest currency unit). Rs 1,234.56 → 123456. Floating-point cannot represent most decimal fractions exactly (0.1 + 0.2 = 0.30000000000000004). Integer arithmetic eliminates rounding errors entirely. A CHECK (balance >= 0) constraint in the DB provides a second line of defence.

### Pessimistic locking

Every balance-changing operation issues **SELECT FOR UPDATE** on the account row(s) inside a transaction. Optimistic locking was considered and rejected: under contention it produces retry storms that are harder to reason about for financial correctness. Pessimistic locks serialize writers explicitly.

### Deadlock prevention

Transfers acquire locks in ascending UUID order regardless of transfer direction. A→B and B→A both acquire the lock on the lower UUID first, making circular wait impossible — even in a three-account ring (A→B, B→C, C→A).

### Idempotency

Callers supply an `idempotencyKey` with every request. The key is stored with a **UNIQUE** DB constraint. On a duplicate request the service returns HTTP 200 with the existing transaction — no double-processing. The constraint is the source of truth; the service-layer check is a fast path only.

### Reversal design

A reversal is a Transaction row with type = **REVERSAL** and `original_transaction_id` pointing to the original — no separate table. The reversal's idempotency key is always `"reversal:{original_txn_id}"`, so the UNIQUE constraint on `idempotency_key` prevents a double-reversal even under concurrent attempts. Only `SUCCESS` transactions can be reversed; `FAILED` and `REVERSED` transactions are rejected.

### Audit log

Every balance change writes an `audit_log` row with `balance_before` and `balance_after`. Rows are indexed by `account_id` and `transaction_id` for fast lookup. Failed operations also produce an audit row (with null balances) so nothing is invisible.

### Isolation level

READ_COMMITTED + pessimistic SELECT FOR UPDATE. SERIALIZABLE was considered but adds overhead (predicate locking, serialization failures requiring retries); explicit locks give equivalent correctness guarantees for this access pattern.

## Assumptions

- Single currency (INR), no FX conversion
- No overdraft — balance floor is 0, enforced at service layer and by DB CHECK constraint
- `FROZEN` accounts can receive deposits but cannot send money
- `CLOSED` accounts reject all operations
- All API amounts are in paise; display conversion is the caller's responsibility
- Reversal of a reversal is not supported
