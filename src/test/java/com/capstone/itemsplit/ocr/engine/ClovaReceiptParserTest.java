package com.capstone.itemsplit.ocr.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;

class ClovaReceiptParserTest {
    private final ClovaReceiptParser parser = new ClovaReceiptParser(new ObjectMapper());

    @Test void parsesActualReceiptFieldsWithoutConfusingUnitPriceAndLineAmount() {
        var result = parser.parse(response("""
          "storeInfo":{"name":{"text":"테스트 식당","formatted":{"value":"테스트 식당"},"confidenceScore":0.98,
          "boundingPolys":[{"vertices":[{"x":1,"y":2},{"x":3,"y":4}]}]}},
          "paymentInfo":{"date":{"text":"2026.10.11","formatted":{"year":"2026","month":"10","day":"11"}}},
          "totalPrice":{"price":{"text":"₩18,000","formatted":{"value":"18000"}}},
          "subResults":[{"items":[{"name":{"text":"국밥"},"count":{"text":"2"},
          "price":{"unitPrice":{"text":"8,000원"},"price":{"text":"16,000"}}}]}]
          """));
        assertThat(result.storeName().value()).isEqualTo("테스트 식당");
        assertThat(result.storeName().confidence()).isEqualTo(0.98);
        assertThat(result.storeName().box()).containsExactly(new ReceiptOcrEngine.Point(1,2),new ReceiptOcrEngine.Point(3,4));
        assertThat(result.purchasedAt().value()).isEqualTo(LocalDate.of(2026,10,11));
        assertThat(result.totalAmount().value()).isEqualTo(18000L);
        var line = result.lines().getFirst();
        assertThat(line.quantity().value()).isEqualTo(2);
        assertThat(line.unitPrice().value()).isEqualTo(8000L);
        assertThat(line.lineAmount().value()).isEqualTo(16000L);
        assertThat(line.unitPrice().rawText()).isEqualTo("8,000원");
        assertThat(line.flags()).isEmpty();
    }

    @Test void preservesMissingValuesRatherThanFabricatingOneOrZero() {
        var result = parser.parse(response("\"subResults\":[{\"items\":[{\"name\":{\"text\":\"국밥\"}}]}]"));
        var line = result.lines().getFirst();
        assertThat(line.quantity().value()).isNull();
        assertThat(line.unitPrice().value()).isNull();
        assertThat(line.lineAmount().value()).isNull();
        assertThat(result.totalAmount().value()).isNull();
        assertThat(line.flags()).contains("MISSING_QUANTITY", "MISSING_PRICE");
    }

    @ParameterizedTest @ValueSource(strings={"1.5","2147483648","many"})
    void invalidQuantitiesStayNull(String value) {
        var line = parser.parse(itemResponse(value,"8000","16000")).lines().getFirst();
        assertThat(line.quantity().value()).isNull();
        assertThat(line.flags()).isNotEmpty();
    }

    @ParameterizedTest @ValueSource(strings={"1.5","9223372036854775808","unknown","1,2"})
    void invalidMoneyStaysNull(String value) {
        var line = parser.parse(itemResponse("2",value,"16000")).lines().getFirst();
        assertThat(line.unitPrice().value()).isNull();
        assertThat(line.flags()).contains("UNPARSED_NUMBER");
    }

    @Test void negativeAmountsAndQuantitiesAreFlaggedForReview() {
        var line = parser.parse(itemResponse("-1","-1000","-1000")).lines().getFirst();
        assertThat(line.flags()).contains("DISCOUNT_OR_REFUND");
        assertThat(line.quantity().value()).isNull();
        assertThat(line.unitPrice().value()).isNull();
        assertThat(line.lineAmount().value()).isNull();
    }

    @Test void impossibleDateStaysNull() {
        var result = parser.parse(response("\"paymentInfo\":{\"date\":{\"text\":\"2026-02-30\",\"formatted\":{\"year\":\"2026\",\"month\":\"02\",\"day\":\"30\"}}},\"subResults\":[{\"items\":[{}]}]"));
        assertThat(result.purchasedAt().value()).isNull();
    }

    @ParameterizedTest @ValueSource(strings={"not-json","{}","{\"images\":{}}","{\"images\":[{\"inferResult\":\"UNKNOWN\"}]}","{\"images\":[{\"inferResult\":\"SUCCESS\",\"receipt\":{\"result\":[]}}]}"})
    void malformedResponsesBecomeTypedErrors(String json) {
        assertError(json,"PROVIDER_ERROR",true);
    }

    @Test void validatesInferResultEvenIfReceiptItemsExist() {
        assertError(itemResponse("2","8000","16000").replace("SUCCESS","FAILURE"),"RECOGNITION_FAILED",false);
        assertError(itemResponse("2","8000","16000").replace("SUCCESS","ERROR"),"PROVIDER_ERROR",true);
    }

    @Test void noItemsIsEmptyResult() {
        assertError(response("\"subResults\":[]"),"EMPTY_RESULT",false);
    }

    @Test void untrustedStringsAndRowCountsCannotOverflowPersistenceLimits() throws Exception {
        var root = new ObjectMapper().readTree(itemResponse("2","8000","16000"));
        var resultNode = root.path("images").get(0).path("receipt").path("result");
        var items = (com.fasterxml.jackson.databind.node.ArrayNode) resultNode.path("subResults").get(0).path("items");
        ((com.fasterxml.jackson.databind.node.ObjectNode) items.get(0).path("name")).put("text","가".repeat(3000));
        var parsed = parser.parse(root.toString()).lines().getFirst();
        assertThat(parsed.name().value()).isNull();
        assertThat(parsed.name().rawText()).hasSize(2000);
        assertThat(parsed.flags()).contains("MISSING_NAME");
        ((com.fasterxml.jackson.databind.node.ObjectNode) items.get(0).path("name")).put("text","item");
        for (int i=1;i<501;i++) items.add(items.get(0).deepCopy());
        assertError(root.toString(),"PROVIDER_ERROR",true);
    }

    @Test void oversizedPayloadIsRejectedWithoutLeakingPayloadInError() {
        String json = response("\"ignored\":\""+"a".repeat(2 * 1024 * 1024)+"\",\"subResults\":[]");
        assertError(json,"PROVIDER_ERROR",true);
    }

    @Test void normalizedNumbersTakePrecedenceOverRawOcrText() {
        var result = parser.parse(response("\"subResults\":[{\"items\":[{\"count\":{\"text\":\"bad OCR\",\"formatted\":{\"value\":\"3\"}},\"price\":{\"unitPrice\":{\"text\":\"100\",\"formatted\":{\"value\":\"1.5\"}}}}]}]"));
        assertThat(result.lines().getFirst().quantity().value()).isEqualTo(3);
        assertThat(result.lines().getFirst().quantity().rawText()).isEqualTo("bad OCR");
        assertThat(result.lines().getFirst().unitPrice().value()).isNull();
    }

    @ParameterizedTest @ValueSource(strings={"2026-10-11","2026.10.11","2026/10/11"})
    void parsesUnformattedDateWithoutAssumingToday(String date) {
        var result = parser.parse(response("\"paymentInfo\":{\"date\":{\"text\":\""+date+"\"}},\"subResults\":[{\"items\":[{}]}]"));
        assertThat(result.purchasedAt().value()).isEqualTo(LocalDate.of(2026,10,11));
    }

    private void assertError(String json,String code,boolean retryable) {
        assertThatThrownBy(() -> parser.parse(json)).isInstanceOfSatisfying(OcrEngineException.class,e -> {
            assertThat(e.code()).isEqualTo(code);
            assertThat(e.retryable()).isEqualTo(retryable);
        });
    }

    private String itemResponse(String count,String unit,String amount) {
        return response("\"subResults\":[{\"items\":[{\"name\":{\"text\":\"item\"},\"count\":{\"text\":\""+count+"\"},\"price\":{\"unitPrice\":{\"text\":\""+unit+"\"},\"price\":{\"text\":\""+amount+"\"}}}]}]");
    }

    private String response(String body) {
        return "{\"version\":\"V2\",\"images\":[{\"inferResult\":\"SUCCESS\",\"receipt\":{\"result\":{"+body+"}}}]}";
    }
}
