package com.capstone.itemsplit.ocr.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import static com.capstone.itemsplit.ocr.engine.ReceiptOcrEngine.*;

/** Maps only receipt review fields; provider payloads and payment details are not retained. */
public class ClovaReceiptParser {
    private final ObjectMapper objectMapper;

    public ClovaReceiptParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public OcrImageResult parse(String json) {
        JsonNode root;
        try {
            if (json == null || json.length() > 2 * 1024 * 1024
                    || json.getBytes(StandardCharsets.UTF_8).length > 2 * 1024 * 1024) throw providerError();
            root = objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            // Do not attach parser exceptions: their messages can contain sensitive response text.
            throw providerError();
        }
        if (root == null) throw providerError();
        JsonNode images = root.path("images");
        if (!images.isArray() || images.size() != 1) throw providerError();
        JsonNode image = images.get(0);
        String status = image.path("inferResult").asText();
        if ("FAILURE".equals(status)) throw new OcrEngineException("RECOGNITION_FAILED", false);
        if (!"SUCCESS".equals(status)) throw providerError();
        JsonNode result = image.path("receipt").path("result");
        if (!result.isObject()) throw providerError();
        JsonNode groups = result.path("subResults");
        if (!groups.isMissingNode() && !groups.isNull() && !groups.isArray()) throw providerError();
        List<OcrLine> lines = new ArrayList<>();
        for (JsonNode group : groups) {
            if (!group.isObject()) throw providerError();
            JsonNode items = group.path("items");
            if (!items.isMissingNode() && !items.isNull() && !items.isArray()) throw providerError();
            for (JsonNode item : items) {
                if (!item.isObject()) throw providerError();
                if (lines.size() >= 500) throw providerError();
                lines.add(line(item));
            }
        }
        if (lines.isEmpty()) throw new OcrEngineException("EMPTY_RESULT", false);
        JsonNode date = result.path("paymentInfo").path("date");
        JsonNode total = result.path("totalPrice").path("price");
        return new OcrImageResult(stringField(result.path("storeInfo").path("name")),
                field(date, date(date)), field(total, number(total, false, new LinkedHashSet<>())),
                List.copyOf(lines));
    }

    private OcrLine line(JsonNode item) {
        Set<String> flags = new LinkedHashSet<>();
        OcrField<String> name = stringField(item.path("name"));
        if (name.value() == null) flags.add("MISSING_NAME");
        if (name.value() != null && name.value().toLowerCase(Locale.ROOT).matches(".*(할인|환불|discount|refund).*")) {
            flags.add("DISCOUNT_OR_REFUND");
        }
        JsonNode count = item.path("count");
        Long quantity = number(count, true, flags);
        if (quantity != null && quantity > Integer.MAX_VALUE) {
            flags.add("UNPARSED_NUMBER");
            quantity = null;
        }
        JsonNode unit = item.path("price").path("unitPrice");
        JsonNode amount = item.path("price").path("price");
        Long unitPrice = number(unit, false, flags);
        Long lineAmount = number(amount, false, flags);
        if (quantity == null) flags.add("MISSING_QUANTITY");
        if (unitPrice == null) flags.add("MISSING_PRICE");
        return new OcrLine(name, field(count, quantity == null ? null : quantity.intValue()),
                field(unit, unitPrice), field(amount, lineAmount), Set.copyOf(flags));
    }

    private Long number(JsonNode node, boolean quantity, Set<String> flags) {
        String value = value(node);
        if (value == null || value.isBlank()) return null;
        String cleaned = value.strip().replaceAll("^(?:₩|KRW)\\s*", "")
                .replaceAll("\\s*원$", "").strip();
        // Validate grouping before removing separators: 1,2 must never become 12.
        if (!cleaned.matches("[+-]?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)?")) {
            flags.add("UNPARSED_NUMBER");
            return null;
        }
        try {
            BigDecimal decimal = new BigDecimal(cleaned.replace(",", ""));
            if (decimal.signum() < 0) {
                flags.add("DISCOUNT_OR_REFUND");
                return null;
            }
            if (decimal.stripTrailingZeros().scale() > 0) {
                flags.add(quantity ? "NON_INTEGER_QUANTITY" : "UNPARSED_NUMBER");
                return null;
            }
            long parsed = decimal.longValueExact();
            if (quantity && parsed == 0) {
                flags.add("QUANTITY_OUT_OF_RANGE");
                return null;
            }
            return parsed;
        } catch (ArithmeticException | NumberFormatException e) {
            flags.add("UNPARSED_NUMBER");
            return null;
        }
    }

    private LocalDate date(JsonNode node) {
        JsonNode formatted = node.path("formatted");
        try {
            if (formatted.has("year") || formatted.has("month") || formatted.has("day")) {
                return LocalDate.of(Integer.parseInt(formatted.path("year").asText()),
                        Integer.parseInt(formatted.path("month").asText()),
                        Integer.parseInt(formatted.path("day").asText()));
            }
            String text = rawText(node);
            if (text == null) return null;
            var matcher = java.util.regex.Pattern.compile("^(\\d{4})[-./](\\d{1,2})[-./](\\d{1,2})$")
                    .matcher(text.strip());
            if (!matcher.matches()) return null;
            return LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)));
        } catch (NumberFormatException | DateTimeException e) {
            return null;
        }
    }

    private OcrField<String> stringField(JsonNode node) {
        String value = value(node);
        return field(node, value == null || value.isBlank() || value.strip().length() > 255 ? null : value.strip());
    }

    private String value(JsonNode node) {
        JsonNode formatted = node.path("formatted").path("value");
        if (!formatted.isMissingNode() && !formatted.isNull()) {
            return formatted.isTextual() || formatted.isNumber() ? formatted.asText() : null;
        }
        return rawText(node);
    }

    private String rawText(JsonNode node) {
        JsonNode text = node.path("text");
        return text.isTextual() ? text.textValue() : null;
    }

    private <T> OcrField<T> field(JsonNode node, T value) {
        JsonNode score = node.path("confidenceScore");
        Double confidence = score.isNumber() ? score.doubleValue() : null;
        if (confidence != null && (!Double.isFinite(confidence) || confidence < 0 || confidence > 1)) confidence = null;
        List<Point> box = new ArrayList<>();
        JsonNode polys = node.path("boundingPolys");
        if (polys.isArray()) {
            for (JsonNode poly : polys) {
                JsonNode vertices = poly.path("vertices");
                if (!vertices.isArray()) continue;
                for (JsonNode point : vertices) {
                    JsonNode x = point.path("x"), y = point.path("y");
                    if (box.size() < 64 && x.isNumber() && y.isNumber() && Double.isFinite(x.doubleValue()) && Double.isFinite(y.doubleValue())) {
                        box.add(new Point(x.doubleValue(), y.doubleValue()));
                    }
                }
            }
        }
        String raw = rawText(node);
        if (raw != null && raw.length() > 2000) raw = raw.substring(0, 2000);
        return new OcrField<>(raw, value, confidence, List.copyOf(box));
    }

    private OcrEngineException providerError() {
        return new OcrEngineException("PROVIDER_ERROR", true);
    }
}
