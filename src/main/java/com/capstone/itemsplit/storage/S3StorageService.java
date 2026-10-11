package com.capstone.itemsplit.storage;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/** Stores files as private objects in one S3 bucket. The stored path is the object key. */
@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "s3")
public class S3StorageService implements StorageService {

	private final S3Client s3Client;
	private final String bucket;

	public S3StorageService(S3Client s3Client, @Value("${app.storage.s3.bucket:}") String bucket) {
		if (!StringUtils.hasText(bucket)) {
			throw new IllegalStateException("app.storage.s3.bucket (S3_BUCKET) is required when app.storage.type is s3.");
		}
		this.s3Client = s3Client;
		this.bucket = bucket;
	}

	@Override
	public StoredFile store(String directory, MultipartFile file, String extension, String contentType) throws IOException {
		String key = requireKey(directory) + "/" + StoredFilenames.generate(extension);
		// A failed PutObject leaves no object behind, so there is no partial upload to clean up.
		try (InputStream inputStream = file.getInputStream()) {
			s3Client.putObject(request -> request.bucket(bucket).key(key)
					.contentType(contentType).contentDisposition("inline"),
				RequestBody.fromInputStream(inputStream, file.getSize()));
		} catch (SdkException exception) {
			throw new IOException("Failed to store the object.", exception);
		}
		return new StoredFile(key, StoredFilenames.original(file.getOriginalFilename()), contentType, file.getSize());
	}

	@Override
	public void delete(String storedPath) throws IOException {
		String key = requireKey(storedPath);
		try {
			s3Client.deleteObject(request -> request.bucket(bucket).key(key));
		} catch (SdkException exception) {
			throw new IOException("Failed to delete the object.", exception);
		}
	}

	@Override
	public byte[] read(String storedPath) throws IOException {
		String key = requireKey(storedPath);
		try {
			return s3Client.getObjectAsBytes(request -> request.bucket(bucket).key(key)).asByteArray();
		} catch (NoSuchKeyException exception) {
			throw new FileNotFoundException("The object does not exist.");
		} catch (SdkException exception) {
			throw new IOException("Failed to read the object.", exception);
		}
	}

	private String requireKey(String value) throws IOException {
		if (!StringUtils.hasText(value) || value.startsWith("/") || value.endsWith("/")) {
			throw new IOException("Invalid storage path.");
		}
		for (String segment : value.split("/")) {
			if (segment.isEmpty() || segment.equals("..") || segment.equals(".")) {
				throw new IOException("Invalid storage path.");
			}
		}
		return value;
	}

}
