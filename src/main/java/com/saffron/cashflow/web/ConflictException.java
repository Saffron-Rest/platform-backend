package com.saffron.cashflow.web;

import java.util.Map;

public class ConflictException extends RuntimeException {
    private final Map<String, Object> extra;

    public ConflictException(String message) {
        super(message);
        this.extra = null;
    }

    public ConflictException(Map<String, Object> extra) {
        super("Conflict");
        this.extra = extra;
    }

    public Map<String, Object> getExtra() {
        return extra;
    }
}
