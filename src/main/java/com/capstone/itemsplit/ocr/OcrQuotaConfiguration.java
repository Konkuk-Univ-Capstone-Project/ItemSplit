package com.capstone.itemsplit.ocr;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OcrQuotaConfiguration {
    @Bean("ocrQuotaClock")
    @ConditionalOnMissingBean(name = "ocrQuotaClock")
    Clock ocrQuotaClock() { return Clock.systemUTC(); }
}
