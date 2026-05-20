package com.banking.entity;

import com.banking.enums.AccountStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a bank account.
 *
 * Balance is stored in the smallest currency unit (paise / cents) as BIGINT
 * to avoid floating-point rounding errors entirely.
 * e.g. ₹1,234.56 is stored as 123456.
 *
 * Concurrency: rows are locked with SELECT FOR UPDATE inside service
 * transactions to prevent lost updates under concurrent transfers.
 * Accounts are always locked in ascending UUID order to prevent deadlocks.
 */
@Getter
@Entity
@Table(name = "account")
public class Account {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Setter
    @Column(name = "owner_name", nullable = false)
    private String ownerName;

    /**
     * Balance in smallest currency unit (paise).
     * Never goes below 0 — enforced at service layer + DB CHECK constraint.
     */
    @Setter
    @Column(nullable = false)
    private Long balance;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version_no")
    private Integer versionNo;

    @PrePersist
    private void prePersist() {
        if (id == null)        id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
        if (status == null)    status = AccountStatus.ACTIVE;
        if (balance == null)   balance = 0L;
    }

    @Override
    public String toString() {
        return "Account{" +
                "id=" + id +
                ", ownerName='" + ownerName + '\'' +
                ", balance=" + balance +
                ", status=" + status +
                ", createdAt=" + createdAt +
                '}';
    }

}
