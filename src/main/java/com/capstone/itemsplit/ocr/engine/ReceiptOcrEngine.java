package com.capstone.itemsplit.ocr.engine;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public interface ReceiptOcrEngine {
    OcrImageResult recognize(OcrImageInput input);

    record OcrImageInput(byte[] content, String contentType, String filename) { }
    record OcrImageResult(OcrField<String> storeName, OcrField<LocalDate> purchasedAt,
                          OcrField<Long> totalAmount, List<OcrLine> lines) { }
    record OcrLine(OcrField<String> name, OcrField<Integer> quantity, OcrField<Long> unitPrice,
                   OcrField<Long> lineAmount, Set<String> flags) { }
    record OcrField<T>(String rawText, T value, Double confidence, List<Point> box) { }
    record Point(double x, double y) { }
}
