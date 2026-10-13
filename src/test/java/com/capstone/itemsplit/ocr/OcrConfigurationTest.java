package com.capstone.itemsplit.ocr;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
class OcrConfigurationTest {
    private ApplicationContextRunner clovaContext() {
        return new ApplicationContextRunner().withUserConfiguration(OcrConfiguration.class)
            .withBean(com.fasterxml.jackson.databind.ObjectMapper.class, com.fasterxml.jackson.databind.ObjectMapper::new)
            .withBean(com.capstone.itemsplit.storage.StorageService.class,
                () -> org.mockito.Mockito.mock(com.capstone.itemsplit.storage.StorageService.class))
            .withPropertyValues("app.ocr.enabled=true", "app.ocr.provider=clova",
                "app.ocr.clova.invoke-url=https://example.apigw.ntruss.com/document/receipt",
                "app.ocr.clova.secret=test-secret");
    }
    @Test void registersRealEngineWithoutMakingStartupRequest() {
        clovaContext().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(com.capstone.itemsplit.ocr.engine.ReceiptOcrEngine.class);
        });
    }
    @Test void realProfileResolvesCredentialsAndRegistersEngine() {
        new ApplicationContextRunner()
            .withInitializer(new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
            .withUserConfiguration(OcrConfiguration.class)
            .withBean(com.fasterxml.jackson.databind.ObjectMapper.class, com.fasterxml.jackson.databind.ObjectMapper::new)
            .withBean(com.capstone.itemsplit.storage.StorageService.class,
                () -> org.mockito.Mockito.mock(com.capstone.itemsplit.storage.StorageService.class))
            .withPropertyValues("spring.profiles.active=ocr",
                "CLOVA_OCR_INVOKE_URL=https://example.apigw.ntruss.com/document/receipt", "CLOVA_OCR_SECRET=test-secret")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(com.capstone.itemsplit.ocr.engine.ReceiptOcrEngine.class);
                assertThat(context.getEnvironment().getProperty("app.ocr.provider")).isEqualTo("clova");
                assertThat(context.getEnvironment().getProperty("app.ocr.clova.secret")).isEqualTo("test-secret");
            });
    }
    @ParameterizedTest
    @ValueSource(strings={"app.ocr.clova.secret=", "app.ocr.clova.invoke-url=",
        "app.ocr.clova.invoke-url=http://example.com/receipt", "app.ocr.clova.invoke-url=invalid",
        "app.ocr.provider=unknown", "app.ocr.connect-timeout=0s", "app.ocr.read-timeout=-1s",
        "app.ocr.demo-enabled=true"})
    void refusesInvalidRealProviderConfiguration(String property) {
        clovaContext().withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }
    @Test void rejectsCombiningRealAndDemoProfiles() {
        clovaContext().withPropertyValues("spring.profiles.active=local,ocr,ocr-demo")
            .run(context -> assertThat(context).hasFailed());
    }
    @Test void ocrIsOptIn() {
        new ApplicationContextRunner().withUserConfiguration(OcrConfiguration.class)
            .run(context->assertThat(context).doesNotHaveBean(OcrConfiguration.class));
    }
    @ParameterizedTest
    @ValueSource(strings={"fake", "FAKE", "FaKe"})
    void productionRejectsFakeProvider(String fake) {
        new ApplicationContextRunner().withUserConfiguration(OcrConfiguration.class)
            .withPropertyValues("spring.profiles.active=prod","app.ocr.enabled=true","app.ocr.provider="+fake,"app.ocr.image-source="+fake)
            .run(context->assertThat(context.getStartupFailure()).hasRootCauseMessage("Fake OCR/storage and the demo API cannot run under prod"));
    }
}
