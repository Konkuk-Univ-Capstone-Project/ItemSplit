package com.capstone.itemsplit.storage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class StorageSelectionTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(LocalStorageService.class, S3StorageConfiguration.class, S3StorageService.class)
		.withPropertyValues("app.storage.local.root-path=./build/test-storage");

	@Test
	void localStorageIsUsedWhenTheTypeIsNotSet() {
		contextRunner.run(context -> assertThat(context.getBean(StorageService.class)).isInstanceOf(LocalStorageService.class));
	}

	@Test
	void s3StorageReplacesLocalStorageWhenSelected() {
		contextRunner
			.withPropertyValues("app.storage.type=s3", "app.storage.s3.bucket=itemsplit-receipts-test")
			.run(context -> assertThat(context.getBean(StorageService.class)).isInstanceOf(S3StorageService.class));
	}

	@Test
	void startupFailsWhenS3IsSelectedWithoutABucket() {
		contextRunner
			.withPropertyValues("app.storage.type=s3")
			.run(context -> assertThat(context).hasFailed()
				.getFailure().rootCause().hasMessageContaining("S3_BUCKET"));
	}

}
