package com.capstone.itemsplit.settlement;
import com.capstone.itemsplit.common.exception.*;
public class SettlementRequestException extends ApiException {
    private final String reason;
    public SettlementRequestException(ErrorCode code,String reason,String message) {super(code,message);this.reason=reason;}
    public String reason() {return reason;}
}
