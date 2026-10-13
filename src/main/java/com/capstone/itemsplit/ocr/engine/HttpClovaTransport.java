package com.capstone.itemsplit.ocr.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import static com.capstone.itemsplit.ocr.engine.ReceiptOcrEngine.OcrImageInput;

/** One HTTP attempt per image. Provider payloads and credentials never become exception details. */
public class HttpClovaTransport implements ClovaTransport {
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private final RestClient client;
    private final ObjectMapper mapper;

    public HttpClovaTransport(String invokeUrl, String secret, Duration connectTimeout,
                              Duration readTimeout, ObjectMapper mapper) {
        var factory = new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        client = RestClient.builder().baseUrl(invokeUrl).requestFactory(factory)
                .defaultHeader("X-OCR-SECRET", secret).build();
        this.mapper = mapper;
    }

    @Override public String recognize(OcrImageInput input) {
        if (input == null || input.content() == null || input.content().length == 0
                || input.content().length > 10_485_760) throw new OcrEngineException("UNSUPPORTED_INPUT", false);
        String format;
        if ("image/jpeg".equals(input.contentType())) format = "jpg";
        else if ("image/png".equals(input.contentType())) format = "png";
        else throw new OcrEngineException("UNSUPPORTED_INPUT", false);

        try {
            String message = mapper.writeValueAsString(Map.of("version", "V2", "requestId", UUID.randomUUID().toString(),
                    "timestamp", System.currentTimeMillis(), "images", List.of(Map.of("format", format, "name", "receipt"))));
            var parts = new LinkedMultiValueMap<String, Object>();
            var messageHeaders = new HttpHeaders();
            messageHeaders.setContentType(MediaType.APPLICATION_JSON);
            parts.add("message", new HttpEntity<>(message, messageHeaders));
            var fileHeaders = new HttpHeaders();
            fileHeaders.setContentType(MediaType.parseMediaType(input.contentType()));
            // Do not send the user's original filename or allow it into multipart headers.
            var file = new ByteArrayResource(input.content()) {
                @Override public String getFilename() { return "receipt." + format; }
            };
            parts.add("file", new HttpEntity<>(file, fileHeaders));
            return client.post().contentType(MediaType.MULTIPART_FORM_DATA).body(parts).exchange((request, response) -> {
                int status = response.getStatusCode().value();
                if (status != 200) throw statusError(status);
                byte[] bytes = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) throw new OcrEngineException("PROVIDER_ERROR", true);
                return new String(bytes, StandardCharsets.UTF_8);
            });
        } catch (OcrEngineException e) {
            throw e;
        } catch (ResourceAccessException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof SocketTimeoutException) throw new OcrEngineException("TIMEOUT", true);
            }
            throw new OcrEngineException("PROVIDER_ERROR", true);
        } catch (IOException | RuntimeException e) {
            // In particular, never retain RestClient exceptions containing the URL or response body.
            throw new OcrEngineException("PROVIDER_ERROR", true);
        }
    }

    private OcrEngineException statusError(int status) {
        return switch (status) {
            case 408, 504 -> new OcrEngineException("TIMEOUT", true);
            case 429 -> new OcrEngineException("RATE_LIMITED", true);
            case 400, 413, 415, 422 -> new OcrEngineException("UNSUPPORTED_INPUT", false);
            default -> status >= 500 ? new OcrEngineException("PROVIDER_ERROR", true)
                    : new OcrEngineException("CONFIG_ERROR", false);
        };
    }
}
