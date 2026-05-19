package com.banking.entity;

import com.banking.enums.AuditOperation;
import com.banking.enums.FailureReason;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Entity
@Table(name = "audit_log",
    indexes = {
        @Index(name = "idx_audit_txn",     columnList = "transaction_id"),
        @Index(name = "idx_audit_account", columnList = "account_id")
    })
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Setter
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    @Setter
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditOperation operation;

    @Setter
    @Column(nullable = false)
    private Long amount;

    @Setter
    @Column(name = "balance_before")
    private Long balanceBefore;

    @Setter
    @Column(name = "balance_after")
    private Long balanceAfter;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason")
    private FailureReason failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    private void prePersist() { createdAt = OffsetDateTime.now(); }
}
