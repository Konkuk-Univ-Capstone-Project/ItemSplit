package com.capstone.itemsplit.ocr;

import com.capstone.itemsplit.ocr.engine.*;
import com.capstone.itemsplit.ocr.storage.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
    }
    @Bean @ConditionalOnProperty(name="app.ocr.image-source",havingValue="storage",matchIfMissing=true)
    ReceiptImageReader storedImageReader(com.capstone.itemsplit.storage.StorageService storage) {
        return new StoredReceiptImageReader(storage);
    }
    @Bean @ConditionalOnProperty(name="app.ocr.provider",havingValue="fake")
    ReceiptOcrEngine fakeOcrEngine(ObjectMapper mapper) {
        return new ClovaReceiptOcrEngine(new FakeClovaTransport(),new ClovaReceiptParser(mapper));
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
