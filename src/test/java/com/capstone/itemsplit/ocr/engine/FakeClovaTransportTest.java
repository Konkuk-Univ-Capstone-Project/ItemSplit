package com.capstone.itemsplit.ocr.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
import static com.capstone.itemsplit.ocr.engine.ReceiptOcrEngine.*;

class FakeClovaTransportTest {
    private final ReceiptOcrEngine engine = new ClovaReceiptOcrEngine(new FakeClovaTransport(), new ClovaReceiptParser(new ObjectMapper()));

    @Test void normalFixtureGoesThroughRealParser() {
        var result = engine.recognize(input("NORMAL"));
        assertThat(result.lines()).hasSize(2);
        assertThat(result.lines().get(0).quantity().value()).isEqualTo(2);
        assertThat(result.lines().get(0).unitPrice().value()).isEqualTo(8000L);
        assertThat(result.lines().get(0).lineAmount().value()).isEqualTo(16000L);
        assertThat(result.lines().get(1).quantity().value()).isEqualTo(2);
        assertThat(result.lines().get(1).unitPrice().value()).isEqualTo(1000L);
        assertThat(result.lines().get(1).lineAmount().value()).isEqualTo(2000L);
        assertThat(result.totalAmount().value()).isEqualTo(18000L);
    }

    @Test void missingFixtureNeverManufacturesValues() {
        var result = engine.recognize(input("MISSING_VALUES"));
        assertThat(result.lines().getFirst().quantity().value()).isNull();
        assertThat(result.lines().getFirst().unitPrice().value()).isNull();
    }

    @Test void totalMismatchRemainsUntouchedForServiceWarning() {
        var result = engine.recognize(input("TOTAL_MISMATCH"));
        assertThat(result.totalAmount().value()).isEqualTo(20000L);
        assertThat(result.lines().getFirst().lineAmount().value()).isEqualTo(16000L);
    }

    @Test void scenariosProduceTypedErrors() {
        assertError(input("RECOGNITION_FAILED"),"RECOGNITION_FAILED",false);
        assertError(input("MALFORMED_RESPONSE"),"PROVIDER_ERROR",true);
        assertError(input("TIMEOUT"),"TIMEOUT",true);
    }

    @Test void unknownBytesAndUnavailableImagesCannotSilentlyReturnNormalResults() {
        assertError(new OcrImageInput("random-image".getBytes(StandardCharsets.UTF_8),"image/png","test.png"),"UNSUPPORTED_INPUT",false);
        assertError(input("UNKNOWN"),"UNSUPPORTED_INPUT",false);
        assertError(input("IMAGE_UNAVAILABLE"),"UNSUPPORTED_INPUT",false);
    }

    private OcrImageInput input(String scenario) {
        return new OcrImageInput(("fake-clova:"+scenario).getBytes(StandardCharsets.UTF_8),"image/png","test.png");
    }

    private void assertError(OcrImageInput input,String code,boolean retryable) {
        assertThatThrownBy(() -> engine.recognize(input)).isInstanceOfSatisfying(OcrEngineException.class, e -> {
            assertThat(e.code()).isEqualTo(code);
            assertThat(e.retryable()).isEqualTo(retryable);
        });
    }
}
