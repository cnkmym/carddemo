package com.carddemo.batch.interest.util;

public final class BatchAbendException extends RuntimeException {

    private final int abendCode;

    public BatchAbendException(int abendCode, String message) {
        super(message);
        this.abendCode = abendCode;
    }

    public int abendCode() {
        return abendCode;
    }
}
