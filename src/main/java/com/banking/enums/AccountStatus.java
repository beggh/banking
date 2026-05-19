package com.banking.enums;

public enum AccountStatus {
    ACTIVE,
    FROZEN,   // no outgoing transfers allowed; deposits still accepted
    CLOSED    // no operations allowed
}
