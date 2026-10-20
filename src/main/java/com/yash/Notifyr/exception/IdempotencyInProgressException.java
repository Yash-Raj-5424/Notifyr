package com.yash.Notifyr.exception;

public class IdempotencyInProgressException extends RuntimeException {
    public IdempotencyInProgressException(String idempotencyKey) {
        super("A request with idempotency key '" + idempotencyKey
                + "' is already being processed. Retry shortly.");
    }
}
