package com.capstone.itemsplit.ping;

import com.capstone.itemsplit.common.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ping")
public class PingController {

	@GetMapping
	public ApiResponse<PingResponse> ping() {
		return ApiResponse.success(new PingResponse("pong"));
	}

	@PostMapping("/echo")
	public ApiResponse<PingResponse> echo(@Valid @RequestBody PingRequest request) {
		return ApiResponse.success(new PingResponse(request.message()));
	}

	public record PingRequest(
		@NotBlank(message = "message must not be blank")
		@Size(max = 50, message = "message must be 50 characters or fewer")
		String message
	) {
	}

	public record PingResponse(String message) {
	}

}
