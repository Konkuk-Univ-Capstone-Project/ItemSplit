package com.capstone.itemsplit.ocr.engine;

import com.capstone.itemsplit.ocr.engine.ReceiptOcrEngine.OcrImageInput;

public interface ClovaTransport {
    String recognize(OcrImageInput input);
}
