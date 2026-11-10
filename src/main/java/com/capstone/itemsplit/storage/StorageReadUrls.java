package com.capstone.itemsplit.storage;

import java.time.Instant;

/** URLs are issued only after the calling receipt service checks room membership. */
public interface StorageReadUrls {
    SignedUrl create(String key);
    record SignedUrl(String url, Instant expiresAt) { }
}
