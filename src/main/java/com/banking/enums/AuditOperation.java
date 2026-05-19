package com.banking.enums;

public enum AuditOperation {
    CREDIT,           // balance increased
    DEBIT,            // balance decreased
    REVERSAL_CREDIT,  // credit applied as part of reversing a prior debit
    REVERSAL_DEBIT    // debit applied as part of reversing a prior credit
}
