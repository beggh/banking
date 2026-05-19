package com.banking.controller;

import com.banking.exception.BankingException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BankingException.class)
    public ResponseEntity<Map<String, String>> handleBankingException(BankingException ex) {
        int status = switch (ex.getReason()) {
            case ACCOUNT_NOT_FOUND, TRANSACTION_NOT_FOUND -> 404;
            case DUPLICATE_REQUEST                        -> 409;
            default                                       -> 422;
        };
        return ResponseEntity.status(status).body(Map.of(
                "error",  ex.getReason().name(),
                "detail", ex.getMessage()
        ));
    }
}
