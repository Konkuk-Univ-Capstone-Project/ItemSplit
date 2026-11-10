package com.capstone.itemsplit.settlement;
import com.capstone.itemsplit.auth.AuthenticatedUser;
import com.capstone.itemsplit.common.exception.*;
import com.capstone.itemsplit.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/rooms/{roomId}") @RequiredArgsConstructor
@ConditionalOnProperty(name="app.settlement.snapshots.enabled",havingValue="true")
public class SettlementSnapshotController {
    private final SettlementSnapshotService service;
    @GetMapping("/settlement-state")
    public ApiResponse<SettlementSnapshotService.StateView> state(@PathVariable Long roomId,@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.state(roomId,userId(user)));
    }
    @GetMapping("/settlements/confirmation-preview")
    public ApiResponse<SettlementSnapshotService.PreviewView> preview(@PathVariable Long roomId,@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.preview(roomId,userId(user)));
    }
    @PostMapping("/settlement-snapshots")
    public ResponseEntity<ApiResponse<SettlementSnapshotService.SnapshotView>> confirm(@PathVariable Long roomId,
        @AuthenticationPrincipal AuthenticatedUser user,@RequestBody SettlementSnapshotService.ConfirmRequest request) {
        var result=service.confirm(roomId,userId(user),request);
        return ResponseEntity.status(result.created()?201:200).body(ApiResponse.success(result.snapshot()));
    }
    @GetMapping("/settlement-snapshots/{snapshotId}")
    public ApiResponse<SettlementSnapshotService.SnapshotView> detail(@PathVariable Long roomId,@PathVariable Long snapshotId,
        @AuthenticationPrincipal AuthenticatedUser user) {return ApiResponse.success(service.detail(roomId,snapshotId,userId(user)));}
    private Long userId(AuthenticatedUser user) {
        if(user==null) throw new ApiException(ErrorCode.UNAUTHORIZED);
        return user.id();
    }
}
