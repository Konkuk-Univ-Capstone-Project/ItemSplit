package com.capstone.itemsplit.auth;

import com.capstone.itemsplit.common.exception.ApiException;
import com.capstone.itemsplit.common.exception.ErrorCode;
import com.capstone.itemsplit.common.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

	private final AuthService authService;

	@PostMapping("/signup")
	public ResponseEntity<ApiResponse<SignupResponse>> signup(@Valid @RequestBody SignupRequest request) {
		AuthService.SignupResult signupResult = authService.signup(
			request.email(),
			request.password(),
			request.nickname()
		);

		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(new SignupResponse(
				signupResult.userId(),
				signupResult.email(),
				signupResult.nickname()
			)));
	}

	@PostMapping("/login")
	public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
		AuthService.LoginResult loginResult = authService.login(request.email(), request.password());

		return ApiResponse.success(new LoginResponse(
			loginResult.userId(),
			loginResult.email(),
			loginResult.nickname(),
			loginResult.accessToken(),
			loginResult.tokenType(),
			loginResult.expiresIn()
		));
	}

	@GetMapping("/me")
	public ApiResponse<CurrentUserResponse> me(@AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
		if (authenticatedUser == null) {
			throw new ApiException(ErrorCode.UNAUTHORIZED, "Authentication is required.");
		}

		AuthService.CurrentUserResult currentUserResult = authService.getCurrentUser(authenticatedUser.id());

		return ApiResponse.success(new CurrentUserResponse(
			currentUserResult.userId(),
			currentUserResult.email(),
			currentUserResult.nickname()
		));
	}

	public record SignupRequest(
		@NotBlank(message = "email must not be blank")
		@Email(message = "email must be a valid email address")
		String email,
		@NotBlank(message = "password must not be blank")
		@Size(min = 8, max = 100, message = "password must be between 8 and 100 characters")
		String password,
		@NotBlank(message = "nickname must not be blank")
		@Size(max = 30, message = "nickname must be 30 characters or fewer")
		String nickname
	) {
	}

	public record LoginRequest(
		@NotBlank(message = "email must not be blank")
		@Email(message = "email must be a valid email address")
		String email,
		@NotBlank(message = "password must not be blank")
		String password
	) {
	}

	public record SignupResponse(Long userId, String email, String nickname) {
	}

	public record LoginResponse(
		Long userId,
		String email,
		String nickname,
		String accessToken,
		String tokenType,
		long expiresIn
	) {
	}

	public record CurrentUserResponse(Long userId, String email, String nickname) {
	}

}
