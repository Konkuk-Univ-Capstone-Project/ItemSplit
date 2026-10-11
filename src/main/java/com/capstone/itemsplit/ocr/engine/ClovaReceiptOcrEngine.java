package com.capstone.itemsplit.ocr.engine;

public class ClovaReceiptOcrEngine implements ReceiptOcrEngine {
    private final ClovaTransport transport;
    private final ClovaReceiptParser parser;

    public ClovaReceiptOcrEngine(ClovaTransport transport, ClovaReceiptParser parser) {
        this.transport = transport;
        this.parser = parser;
    }

    @Override
    public OcrImageResult recognize(OcrImageInput input) {
        return parser.parse(transport.recognize(input));
    }
}
