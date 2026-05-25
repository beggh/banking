package com.banking.pattern.factory;

import com.banking.entity.Transaction;
import com.banking.enums.TransactionStatus;
import com.banking.enums.TransactionType;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class TransactionFactory {

    public Transaction build(TransactionType type, UUID from, UUID to,
                             Long amount, String idempotencyKey) {
        Transaction txn = new Transaction();
        txn.setType(type);
        txn.setFromAccountId(from);
        txn.setToAccountId(to);
        txn.setAmount(amount);
        txn.setIdempotencyKey(idempotencyKey);
        txn.setStatus(TransactionStatus.PENDING);
        return txn;
    }
}
