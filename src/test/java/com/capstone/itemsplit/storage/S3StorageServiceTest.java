package com.capstone.itemsplit.storage;

import java.io.IOException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs against LocalStack, so no AWS account is needed. Skipped when Docker is not running. */
@Testcontainers(disabledWithoutDocker = true)
class S3StorageServiceTest {

	private static final String BUCKET = "itemsplit-receipts-test";

	@Container
	static final LocalStackContainer localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8"))
		.withServices(LocalStackContainer.Service.S3);

	private static S3Client s3Client;
	private static S3StorageService storage;

	@BeforeAll
	static void createBucket() {
		s3Client = S3Client.builder()
			.endpointOverride(localstack.getEndpoint())
			.region(Region.of(localstack.getRegion()))
			.credentialsProvider(StaticCredentialsProvider.create(
				AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
			.forcePathStyle(true)
			.build();
		s3Client.createBucket(request -> request.bucket(BUCKET));
		storage = new S3StorageService(s3Client, BUCKET);
	}

	@Test
	void storedObjectCanBeReadAndCarriesTheVerifiedType() throws Exception {
		StorageService.StoredFile stored = storage.store("receipts/1",
			new MockMultipartFile("file", "../photo.png", "image/png", new byte[] {1, 2, 3}), "jpg", "image/jpeg");

		assertThat(stored.storedPath()).startsWith("receipts/1/").endsWith(".jpg").doesNotContain("photo");
		assertThat(stored.originalFilename()).isEqualTo("photo.png");
		assertThat(stored.contentType()).isEqualTo("image/jpeg");
		assertThat(stored.size()).isEqualTo(3);
		assertThat(storage.read(stored.storedPath())).containsExactly(new byte[] {1, 2, 3});

		HeadObjectResponse head = s3Client.headObject(request -> request.bucket(BUCKET).key(stored.storedPath()));
		assertThat(head.contentType()).isEqualTo("image/jpeg");
		assertThat(head.contentDisposition()).isEqualTo("inline");
	}

	@Test
	void deletedObjectCannotBeReadAndRepeatedDeletionIsHarmless() throws Exception {
		StorageService.StoredFile stored = storage.store("receipts/2", upload(), "png", "image/png");

		storage.delete(stored.storedPath());
		assertThatThrownBy(() -> storage.read(stored.storedPath())).isInstanceOf(IOException.class);
		storage.delete(stored.storedPath());
	}

	@Test
	void rejectsKeysThatLeaveTheGivenDirectory() {
		for (String invalid : new String[] {"", "/receipts/1/a.png", "receipts/../other/a.png", "receipts//a.png", "receipts/1/"}) {
			assertThatThrownBy(() -> storage.read(invalid)).isInstanceOf(IOException.class);
			assertThatThrownBy(() -> storage.delete(invalid)).isInstanceOf(IOException.class);
		}
		assertThatThrownBy(() -> storage.store("../outside", upload(), "png", "image/png")).isInstanceOf(IOException.class);
	}

	@Test
	void failureToReachTheBucketIsReportedAsIOException() {
		S3StorageService missingBucket = new S3StorageService(s3Client, "bucket-that-does-not-exist");

		assertThatThrownBy(() -> missingBucket.store("receipts/3", upload(), "png", "image/png")).isInstanceOf(IOException.class);
		assertThatThrownBy(() -> missingBucket.read("receipts/3/a.png")).isInstanceOf(IOException.class);
	}

	private MockMultipartFile upload() {
		return new MockMultipartFile("file", "receipt.png", "image/png", new byte[] {1, 2, 3});
	}

}
