package com.capstone.itemsplit.auth;

import com.capstone.itemsplit.common.exception.ApiException;
import com.capstone.itemsplit.common.exception.ErrorCode;
import com.capstone.itemsplit.domain.user.User;
import com.capstone.itemsplit.domain.user.UserService;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

	private final UserService userService;
	private final PasswordEncoder passwordEncoder;
	private final JwtTokenProvider jwtTokenProvider;

	@Transactional
	public SignupResult signup(String email, String password, String nickname) {
		String normalizedEmail = normalizeEmail(email);
		String normalizedNickname = nickname.trim();

		if (userService.existsByEmail(normalizedEmail)) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "Email is already in use.");
		}

		User user = userService.createUser(
			normalizedEmail,
			passwordEncoder.encode(password),
			normalizedNickname
		);

		return SignupResult.from(user);
	}

	public LoginResult login(String email, String password) {
		User user = userService.getByEmail(normalizeEmail(email));

		if (!passwordEncoder.matches(password, user.getPassword())) {
			throw new ApiException(ErrorCode.UNAUTHORIZED, "Email or password is incorrect.");
		}

		JwtTokenProvider.TokenInfo tokenInfo = jwtTokenProvider.createAccessToken(user);
		return LoginResult.from(user, tokenInfo);
	}

	public CurrentUserResult getCurrentUser(Long userId) {
		return CurrentUserResult.from(userService.getById(userId));
	}

	private String normalizeEmail(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}

	public record SignupResult(Long userId, String email, String nickname) {

		private static SignupResult from(User user) {
			return new SignupResult(user.getId(), user.getEmail(), user.getNickname());
		}

	}

	public record LoginResult(
		Long userId,
		String email,
		String nickname,
		String accessToken,
		String tokenType,
		long expiresIn
	) {

		private static LoginResult from(User user, JwtTokenProvider.TokenInfo tokenInfo) {
			return new LoginResult(
				user.getId(),
				user.getEmail(),
				user.getNickname(),
				tokenInfo.accessToken(),
				tokenInfo.tokenType(),
				tokenInfo.expiresIn()
			);
		}

	}

	public record CurrentUserResult(Long userId, String email, String nickname) {

		private static CurrentUserResult from(User user) {
			return new CurrentUserResult(user.getId(), user.getEmail(), user.getNickname());
		}

	}

}
