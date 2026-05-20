package com.banking.repository;
import com.banking.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.UUID;
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findByTransactionIdOrderByCreatedAtAsc(UUID transactionId);

    @Query("SELECT a FROM AuditLog a WHERE a.account.id = :id ORDER BY a.createdAt ASC")
    List<AuditLog> findByAccountIdOrderByCreatedAtAsc(@Param("id") UUID id);

    @Query("SELECT a FROM AuditLog a WHERE a.account.id = :id ORDER BY a.createdAt DESC")
    List<AuditLog> findByAccountIdOrderByCreatedAtDesc(UUID id);
}
