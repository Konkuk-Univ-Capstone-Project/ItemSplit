package com.capstone.itemsplit.auth;

import com.capstone.itemsplit.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class JwtSecretPolicyTest {

	private static final String VALID_SECRET = "q8VnX2rT5yLw0pZc3hKj7mBd9sFg1aEu4oNi6tRxYvW=";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withBean(JwtTokenProvider.class)
		.withPropertyValues("app.jwt.access-token-expiration=60000");

	@Test
	@DisplayName("prod 프로필은 JWT 키가 없거나 공백이면 시작하지 않는다")
	void 운영에서_키가_없으면_시작하지_않는다() {
		assertStartupRejected("", "is not set");
		assertStartupRejected("   ", "is not set");
	}

	@Test
	@DisplayName("prod 프로필은 저장소에 공개된 예시 키로 시작하지 않는다")
	void 운영에서_공개_예시_키는_거부한다() {
		assertStartupRejected("change-me-to-a-long-random-secret-for-itemsplit", "public example value");
		assertStartupRejected("change-me-to-a-long-random-secret", "public example value");
		assertStartupRejected("change-me-later-but-still-long-enough-for-hs256", "public example value");
	}

	@Test
	@DisplayName("prod 프로필은 HS256에 필요한 32바이트보다 짧은 키로 시작하지 않는다")
	void 운영에서_짧은_키는_거부한다() {
		assertStartupRejected("too-short-secret-31-bytes-long!", "shorter than 32 bytes");
	}

	@Test
	@DisplayName("prod 프로필에서 안전한 키로 시작하면 토큰을 발급하고 검증한다")
	void 운영에서_정상_키는_토큰을_발급하고_검증한다() {
		contextRunner
			.withPropertyValues("spring.profiles.active=prod", "app.jwt.secret=" + VALID_SECRET)
			.run(context -> {
				assertThat(context).hasNotFailed();
				JwtTokenProvider provider = context.getBean(JwtTokenProvider.class);
				User user = User.create("prod@example.com", "encoded", "prod");
				ReflectionTestUtils.setField(user, "id", 42L);

				String token = provider.createAccessToken(user).accessToken();

				assertThat(provider.getAuthenticatedUser(token).id()).isEqualTo(42L);
			});
	}

	@Test
	@DisplayName("prod가 아닌 프로필은 개발용 기본 키로 시작한다")
	void 개발_프로필은_기본_키를_허용한다() {
		contextRunner
			.withPropertyValues("spring.profiles.active=local", "app.jwt.secret=change-me-to-a-long-random-secret-for-itemsplit")
			.run(context -> assertThat(context).hasNotFailed());
	}

	private void assertStartupRejected(String secret, String reason) {
		contextRunner
			.withPropertyValues("spring.profiles.active=prod", "app.jwt.secret=" + secret)
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure())
					.rootCause()
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining(reason);
				if (!secret.isBlank()) {
					assertThat(context.getStartupFailure()).rootCause().message().doesNotContain(secret.trim());
				}
			});
	}

}
