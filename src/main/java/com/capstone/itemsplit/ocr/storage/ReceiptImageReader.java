package com.capstone.itemsplit.ocr.storage;

import java.io.IOException;

/** Receives a trusted DB object key, never an arbitrary client URL. S3 integration implements this port. */
public interface ReceiptImageReader {
    byte[] read(String storedPath) throws IOException;
}
