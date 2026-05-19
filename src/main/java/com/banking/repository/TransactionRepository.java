package com.banking.repository;
import com.banking.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);
    @Query("SELECT t FROM Transaction t WHERE t.fromAccountId = :id OR t.toAccountId = :id ORDER BY t.createdAt DESC")
    List<Transaction> findByAccountId(@Param("id") UUID id);
    @Query("SELECT t FROM Transaction t WHERE t.originalTransactionId = :id")
    Optional<Transaction> findByOriginalTransactionId(@Param("id") UUID id);
}
