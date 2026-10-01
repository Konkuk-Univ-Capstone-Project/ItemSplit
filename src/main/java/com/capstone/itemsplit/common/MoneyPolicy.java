package com.capstone.itemsplit.common;

import com.capstone.itemsplit.common.exception.ApiException;
import com.capstone.itemsplit.common.exception.ErrorCode;

public final class MoneyPolicy {
    public static final long PRICE_MAX = 1_000_000L;
    public static final int QUANTITY_MAX = 999;
    public static final long RECEIPT_MAX = 10_000_000L;
    public static final long ROOM_MAX = 100_000_000L;
    private MoneyPolicy() { }

    public static long itemTotal(long price, int quantity) {
        if (price < 1 || price > PRICE_MAX || quantity < 1 || quantity > QUANTITY_MAX) {
            throw invalid("단가는 1~1,000,000원, 수량은 1~999의 정수여야 합니다.");
        }
        long total = price * quantity;
        if (total > RECEIPT_MAX) throw invalid("품목 금액은 10,000,000원을 넘을 수 없습니다.");
        return total;
    }

    public static void declaredTotal(Long total) {
        if (total != null && (total < 1 || total > RECEIPT_MAX)) {
            throw invalid("입력 총액은 1~10,000,000원이어야 합니다.");
        }
    }

    public static long addWithin(long current, long addition, long max, String message) {
        if (addition < 0 || current < 0 || current > max || addition > max - current) throw invalid(message);
        return current + addition;
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, message);
    }
}
