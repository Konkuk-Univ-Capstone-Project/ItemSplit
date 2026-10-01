package com.capstone.itemsplit.storage;

import java.io.IOException;

/** A failed upload left a partial file that still requires cleanup. */
public class PartialStorageException extends IOException {

	private final String storedPath;

	public PartialStorageException(String storedPath, Throwable cause) {
		super("Upload failed and its partial file could not be removed.", cause);
		this.storedPath = storedPath;
	}

	public String storedPath() {
		return storedPath;
	}
}
