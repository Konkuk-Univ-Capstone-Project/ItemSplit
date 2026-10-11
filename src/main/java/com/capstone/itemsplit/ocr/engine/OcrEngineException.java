package com.capstone.itemsplit.ocr.engine;

public class OcrEngineException extends RuntimeException {
    private final String code;
    private final boolean retryable;

    public OcrEngineException(String code, boolean retryable) {
        super(code);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() { return code; }
    public boolean retryable() { return retryable; }
}
