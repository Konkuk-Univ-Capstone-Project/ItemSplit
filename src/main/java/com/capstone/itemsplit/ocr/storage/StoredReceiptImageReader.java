package com.capstone.itemsplit.ocr.storage;

import com.capstone.itemsplit.storage.StorageService;
import java.io.IOException;

/** Uses the same local/S3 implementation and object keys as receipt uploads and deletion. */
public class StoredReceiptImageReader implements ReceiptImageReader {
    private final StorageService storage;

    public StoredReceiptImageReader(StorageService storage) {
        this.storage = storage;
    }

    @Override
    public byte[] read(String storedPath) throws IOException {
        return storage.read(storedPath);
    }
}
