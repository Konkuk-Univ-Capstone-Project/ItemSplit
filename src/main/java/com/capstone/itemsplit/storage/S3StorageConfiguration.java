package com.capstone.itemsplit.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
@ConditionalOnProperty(name = "app.storage.type", havingValue = "s3")
class S3StorageConfiguration {

    @Bean
    software.amazon.awssdk.services.s3.presigner.S3Presigner s3Presigner(@Value("${app.storage.s3.region:ap-northeast-2}") String region) {
        return software.amazon.awssdk.services.s3.presigner.S3Presigner.builder().region(Region.of(region)).build();
    }
	// Credentials come from the SDK's default chain (instance role, environment, local profile), never from this project.
	@Bean
	S3Client s3Client(@Value("${app.storage.s3.region:ap-northeast-2}") String region) {
		return S3Client.builder().region(Region.of(region)).build();
	}

}
