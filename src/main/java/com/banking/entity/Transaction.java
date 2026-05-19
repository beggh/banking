package com.banking.entity;

import com.banking.enums.TransactionStatus;
import com.banking.enums.TransactionType;
import com.banking.enums.FailureReason;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Records every balance-changing attempt — successful or not.
 *
 * Key design decisions:
 *
 * 1. REVERSAL is just another TransactionType, not a separate table.
 *    original_transaction_id links a REVERSAL back to what it undoes.
 *
 * 2. idempotency_key (UNIQUE) prevents duplicate submissions.
 *    For normal ops: caller-supplied UUID per request.
 *    For reversals: always set to "reversal:{original_transaction_id}"
 *    so the same reversal can never be applied twice.
 *
 * 3. FAILED transactions are also persisted so every attempt is auditable.
 */
@Getter
@Entity
@Table(
    name = "transaction",
    indexes = {
        @Index(name = "idx_txn_from_account",   columnList = "from_account_id"),
        @Index(name = "idx_txn_to_account",     columnList = "to_account_id"),
        @Index(name = "idx_txn_idempotency",    columnList = "idempotency_key", unique = true),
        @Index(name = "idx_txn_original",       columnList = "original_transaction_id")
    }
)
public class Transaction {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private TransactionType type;

    /** Nullable: DEPOSIT has no source account. */
    @Setter
    @Column(name = "from_account_id")
    private UUID fromAccountId;

    /** Nullable: WITHDRAWAL has no destination account. */
    @Setter
    @Column(name = "to_account_id")
    private UUID toAccountId;

    /** Always positive, in paise/cents. */
    @Setter
    @Column(nullable = false, updatable = false)
    private Long amount;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    /**
     * UNIQUE — prevents duplicate submissions.
     * Reversal key convention: "reversal:{original_transaction_id}"
     */
    @Setter
    @Column(name = "idempotency_key", nullable = false, unique = true, updatable = false)
    private String idempotencyKey;

    /**
     * Set only when type = REVERSAL.
     * Points to the original SUCCESS transaction being undone.
     */
    @Setter
    @Column(name = "original_transaction_id")
    private UUID originalTransactionId;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason")
    private FailureReason failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    private void prePersist() {
        if (id == null)        id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }
}
