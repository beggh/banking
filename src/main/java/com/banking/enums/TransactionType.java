package com.banking.enums;

public enum TransactionType {
    DEPOSIT,      // money enters the system into an account
    WITHDRAWAL,   // money leaves the system from an account
    TRANSFER,     // money moves between two accounts within the system
    REVERSAL      // compensating transaction that undoes a prior completed txn
}
