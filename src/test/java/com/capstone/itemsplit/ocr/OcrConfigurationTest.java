package com.capstone.itemsplit.ocr;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
class OcrConfigurationTest {
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
