package com.capstone.itemsplit.ocr;
import com.capstone.itemsplit.common.exception.*;
public class OcrRequestException extends ApiException {
    private final String reason;
    public OcrRequestException(ErrorCode code,String reason,String message) {super(code,message);this.reason=reason;}
    public String reason(){return reason;}
}
