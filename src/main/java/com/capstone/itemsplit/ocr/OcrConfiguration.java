package com.capstone.itemsplit.ocr;

import com.capstone.itemsplit.ocr.engine.*;
import com.capstone.itemsplit.ocr.storage.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="app.ocr.enabled",havingValue="true")
public class OcrConfiguration {
    public OcrConfiguration(Environment env) {
        if(env.acceptsProfiles(Profiles.of("prod")) &&
          ("fake".equalsIgnoreCase(env.getProperty("app.ocr.provider")) || "fake".equalsIgnoreCase(env.getProperty("app.ocr.image-source"))
           || env.getProperty("app.ocr.demo-enabled",Boolean.class,false))) {
            throw new IllegalStateException("Fake OCR/storage and the demo API cannot run under prod");
        }
        String provider = env.getProperty("app.ocr.provider", "clova");
        if (!java.util.Set.of("fake", "clova").contains(provider)) {
            throw new IllegalStateException("app.ocr.provider must be fake or clova");
        }
        if (env.acceptsProfiles(Profiles.of("ocr")) && env.acceptsProfiles(Profiles.of("ocr-demo"))) {
            throw new IllegalStateException("ocr and ocr-demo profiles cannot be combined");
        }
        if ("clova".equals(provider) && (env.getProperty("app.ocr.demo-enabled", Boolean.class, false)
                || !"storage".equals(env.getProperty("app.ocr.image-source", "storage")))) {
            throw new IllegalStateException("Real CLOVA requires stored images and demo-enabled=false");
        }
    }
    @Bean @ConditionalOnProperty(name="app.ocr.image-source",havingValue="storage",matchIfMissing=true)
    ReceiptImageReader storedImageReader(com.capstone.itemsplit.storage.StorageService storage) {
        return new StoredReceiptImageReader(storage);
    }
    @Bean @ConditionalOnProperty(name="app.ocr.provider",havingValue="fake")
    ReceiptOcrEngine fakeOcrEngine(ObjectMapper mapper) {
        return new ClovaReceiptOcrEngine(new FakeClovaTransport(),new ClovaReceiptParser(mapper));
    }
    @Bean @ConditionalOnProperty(name="app.ocr.provider",havingValue="clova",matchIfMissing=true)
    ReceiptOcrEngine clovaOcrEngine(ObjectMapper mapper,
            @Value("${app.ocr.clova.invoke-url:}") String invokeUrl,
            @Value("${app.ocr.clova.secret:}") String secret,
            @Value("${app.ocr.connect-timeout:3s}") String connectTimeout,
            @Value("${app.ocr.read-timeout:15s}") String readTimeout) {
        try {
            URI uri = URI.create(invokeUrl);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null || !uri.getPath().endsWith("/receipt")) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            // Do not include the configured URL in startup errors; it contains an Invoke Key.
            throw new IllegalStateException("CLOVA_OCR_INVOKE_URL must be an HTTPS receipt endpoint");
        }
        if (secret.isBlank() || secret.contains("\r") || secret.contains("\n")) {
            throw new IllegalStateException("CLOVA_OCR_SECRET must be configured as a valid header value");
        }
        return new ClovaReceiptOcrEngine(new HttpClovaTransport(invokeUrl, secret,
                timeout(connectTimeout), timeout(readTimeout), mapper), new ClovaReceiptParser(mapper));
    }
    private static Duration timeout(String value) {
        Duration duration = DurationStyle.detectAndParse(value);
        if (duration.toMillis() < 1 || duration.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalStateException("OCR timeouts must be positive and fit in milliseconds");
        }
        return duration;
    }
    @Bean("ocrExecutor")
    ThreadPoolTaskExecutor ocrExecutor(@Value("${app.ocr.worker-threads:2}") int threads) {
        if(threads<1 || threads>16) throw new IllegalArgumentException("OCR worker threads must be between 1 and 16");
        ThreadPoolTaskExecutor executor=new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);executor.setMaxPoolSize(threads);executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("receipt-ocr-");executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);return executor;
    }
}
