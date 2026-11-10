package com.capstone.itemsplit.storage;

import java.time.*;
import java.net.URI;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LocalReadUrlsTest {
    @Test void bindsSignatureToExactKeyAndExpiryAndRejectsTraversal() {
        Instant now=Instant.parse("2026-10-12T00:00:00Z");
        String secret="a-separate-image-signing-secret-at-least-32-bytes";
        var urls=new LocalReadUrls(secret,Clock.fixed(now,ZoneOffset.UTC));
        var signed=urls.create("receipts/1/photo.png");
        assertThat(signed.expiresAt()).isEqualTo(now.plusSeconds(300));
        String sig=signed.url().split("sig=")[1];
        long expires=signed.expiresAt().getEpochSecond();
        urls.verify("receipts/1/photo.png",expires,sig);
        assertThatThrownBy(()->urls.verify("receipts/2/photo.png",expires,sig)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->urls.verify("receipts/1/photo.png",expires+1,sig)).isInstanceOf(RuntimeException.class);
        var expired=new LocalReadUrls(secret,Clock.fixed(now.plusSeconds(300),ZoneOffset.UTC));
        assertThatThrownBy(()->expired.verify("receipts/1/photo.png",expires,sig)).isInstanceOf(RuntimeException.class);
        for(String key:new String[]{"../secret", "receipts/1/../photo.png", ".cleanup-pending", "receipts/1/photo.svg"})
            assertThatThrownBy(()->urls.create(key)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->new LocalReadUrls("short")).isInstanceOf(IllegalStateException.class);
    }
}
