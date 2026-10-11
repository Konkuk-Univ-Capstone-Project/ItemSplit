package com.capstone.itemsplit.ocr;
import com.capstone.itemsplit.auth.AuthenticatedUser;
import com.capstone.itemsplit.common.response.ApiResponse;
import com.capstone.itemsplit.ocr.engine.FakeOcrScenario;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @Profile("ocr-demo & !prod")
@ConditionalOnProperty(name={"app.ocr.enabled","app.ocr.demo-enabled"},havingValue="true")
@RequestMapping("/api/rooms/{roomId}/ocr-demo/receipts")
public class OcrDemoController {
    private final OcrDemoService service;
    @PostMapping ResponseEntity<ApiResponse<OcrDemoService.DemoReceipt>> create(@PathVariable Long roomId,
        @AuthenticationPrincipal AuthenticatedUser user,@Valid @RequestBody DemoRequest request) {
        return ResponseEntity.status(201).body(ApiResponse.success(service.create(roomId,OcrController.userId(user),request.name(),request.scenarios())));
    }
    public record DemoRequest(@NotBlank @Size(max=100) String name,@NotEmpty @Size(max=3) List<@NotNull FakeOcrScenario> scenarios){}
}
