package com.capstone.itemsplit.receipt;

import java.util.Arrays;
import java.util.Optional;

/** Image formats accepted for receipts, recognized by the file's leading bytes rather than its name or header. */
public enum ReceiptImageFormat {

    JPEG("image/jpeg", "jpg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
    PNG("image/png", "png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});

    /** Number of leading bytes needed to recognize any accepted format. */
    public static final int SIGNATURE_LENGTH = 8;

    private final String contentType;
    private final String extension;
    private final byte[] signature;

    ReceiptImageFormat(String contentType, String extension, byte[] signature) {
        this.contentType = contentType;
        this.extension = extension;
        this.signature = signature;
    }

    public static Optional<ReceiptImageFormat> detect(byte[] head) {
        return Arrays.stream(values())
            .filter(format -> head.length >= format.signature.length
                && Arrays.equals(head, 0, format.signature.length, format.signature, 0, format.signature.length))
            .findFirst();
    }

    public String contentType() {
        return contentType;
    }

    public String extension() {
        return extension;
    }
}
