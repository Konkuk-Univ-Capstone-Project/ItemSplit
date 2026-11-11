package com.capstone.itemsplit.settlement;

import com.capstone.itemsplit.auth.AuthenticatedUser;
import com.capstone.itemsplit.common.exception.*;
import com.capstone.itemsplit.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@ConditionalOnProperty(name="app.settlement.snapshots.enabled",havingValue="true")
public class SettlementSharingController {
    private final SettlementSharingService service;
    @PostMapping("/api/rooms/{roomId}/settlement-snapshots/{snapshotId}/share-token")
    public ApiResponse<SettlementSharingService.IssuedShare> issue(@PathVariable Long roomId,@PathVariable Long snapshotId,
        @AuthenticationPrincipal AuthenticatedUser user) {
        if(user==null) throw new ApiException(ErrorCode.UNAUTHORIZED);
        return ApiResponse.success(service.issue(roomId,snapshotId,user.id()));
    }
    @GetMapping("/api/shared/settlements/{token}")
    public ApiResponse<SettlementSharingService.PublicSnapshot> read(@PathVariable String token) {
        return ApiResponse.success(service.read(token));
    }
}
