package com.capstone.itemsplit.storage;

import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Component
@ConditionalOnProperty(name="app.storage.type",havingValue="s3")
public class S3ReadUrls implements StorageReadUrls {
    private final S3Presigner presigner;
    private final String bucket;
    public S3ReadUrls(S3Presigner presigner,@Value("${app.storage.s3.bucket}") String bucket) {
        this.presigner=presigner;this.bucket=bucket;
    }
    @Override public SignedUrl create(String key) {
        var signed=presigner.presignGetObject(request->request.signatureDuration(Duration.ofMinutes(5))
            .getObjectRequest(object->object.bucket(bucket).key(key).responseCacheControl("private, no-store").responseContentDisposition("inline")));
        return new SignedUrl(signed.url().toString(),signed.expiration());
    }
}
