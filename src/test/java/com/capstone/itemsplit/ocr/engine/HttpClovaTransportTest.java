package com.capstone.itemsplit.ocr.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class HttpClovaTransportTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger calls = new AtomicInteger();
    private HttpServer server;
    private int status = 200;
    private String response = "{}";
    private long delayMs;
    private String body;
    private String contentType;
    private String secret;
    private String method;

    @BeforeEach void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/receipt", exchange -> {
            calls.incrementAndGet();
            body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            secret = exchange.getRequestHeaders().getFirst("X-OCR-SECRET");
            method = exchange.getRequestMethod();
            try {
                if (delayMs > 0) Thread.sleep(delayMs);
                if (status == 302) exchange.getResponseHeaders().add("Location", "/redirect-target");
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.createContext("/redirect-target", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach void stopServer() { server.stop(0); }

    @Test void uploadsOneFileWithV2MetadataAndParsesReceipt() throws Exception {
        try (var fixture = getClass().getResourceAsStream("/ocr/fixtures/normal.json")) {
            response = new String(fixture.readAllBytes(), StandardCharsets.UTF_8);
        }
        var engine = new ClovaReceiptOcrEngine(transport(2000), new ClovaReceiptParser(mapper));
        var result = engine.recognize(input());
        assertThat(result.lines()).isNotEmpty();
        assertThat(result.totalAmount().value()).isEqualTo(18000L);
        assertThat(calls).hasValue(1);
        assertThat(method).isEqualTo("POST");
        assertThat(secret).isEqualTo("test-secret");
        assertThat(contentType).startsWith("multipart/form-data;boundary=");
        assertThat(body).contains("name=\"file\"", "filename=\"receipt.png\"", "Content-Type: image/png", "IMAGE_BYTES");
        assertThat(body).doesNotContain("private-original-name", "test-secret");
        int jsonStart = body.indexOf('{');
        int jsonEnd = body.indexOf("\r\n--", jsonStart);
        var message = mapper.readTree(body.substring(jsonStart, jsonEnd));
        assertThat(message.path("version").asText()).isEqualTo("V2");
        assertThat(UUID.fromString(message.path("requestId").asText())).isNotNull();
        assertThat(message.path("timestamp").asLong()).isPositive();
        assertThat(message.path("images").size()).isEqualTo(1);
        assertThat(message.path("images").get(0).path("format").asText()).isEqualTo("png");
        assertThat(message.path("images").get(0).has("data")).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"400,UNSUPPORTED_INPUT,false", "401,CONFIG_ERROR,false", "403,CONFIG_ERROR,false",
            "404,CONFIG_ERROR,false", "413,UNSUPPORTED_INPUT,false", "415,UNSUPPORTED_INPUT,false",
            "429,RATE_LIMITED,true", "500,PROVIDER_ERROR,true", "503,PROVIDER_ERROR,true",
            "302,CONFIG_ERROR,false", "408,TIMEOUT,true"})
    void mapsErrorsWithoutRetryingOrLeakingProviderDetails(int status, String code, boolean retryable) {
        this.status = status;
        response = "sensitive provider text test-secret";
        assertError(() -> transport(2000).recognize(input()), code, retryable);
        assertThat(calls).hasValue(1);
    }

    @Test void readTimeoutIsRetryableButDoesNotAutomaticallyRepeatRequest() {
        delayMs = 500;
        assertError(() -> transport(100).recognize(input()), "TIMEOUT", true);
        assertThat(calls).hasValue(1);
    }

    @Test void limitsProviderResponseBeforeParsing() {
        response = "x".repeat(2 * 1024 * 1024 + 1);
        assertError(() -> transport(2000).recognize(input()), "PROVIDER_ERROR", true);
    }

    @Test void rejectsUnsupportedInputWithoutCallingProvider() {
        assertError(() -> transport(2000).recognize(new ReceiptOcrEngine.OcrImageInput(new byte[]{1}, "image/webp", "x.webp")),
                "UNSUPPORTED_INPUT", false);
        assertError(() -> transport(2000).recognize(new ReceiptOcrEngine.OcrImageInput(new byte[0], "image/png", "x.png")),
                "UNSUPPORTED_INPUT", false);
        assertThat(calls).hasValue(0);
    }

    private HttpClovaTransport transport(long timeoutMs) {
        return new HttpClovaTransport("http://127.0.0.1:" + server.getAddress().getPort() + "/receipt",
                "test-secret", Duration.ofSeconds(1), Duration.ofMillis(timeoutMs), mapper);
    }

    private ReceiptOcrEngine.OcrImageInput input() {
        return new ReceiptOcrEngine.OcrImageInput("IMAGE_BYTES".getBytes(StandardCharsets.UTF_8), "image/png", "private-original-name.png");
    }

    private void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code, boolean retryable) {
        assertThatThrownBy(call).isInstanceOfSatisfying(OcrEngineException.class, e -> {
            assertThat(e.code()).isEqualTo(code);
            assertThat(e.retryable()).isEqualTo(retryable);
            assertThat(e).hasMessage(code).hasNoCause();
        });
    }
}
