package com.capstone.itemsplit.auth;

import com.capstone.itemsplit.domain.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenProvider {

	private final SecretKey signingKey;
	private final long accessTokenExpiration;

	public JwtTokenProvider(
		@Value("${app.jwt.secret}") String secret,
		@Value("${app.jwt.access-token-expiration}") long accessTokenExpiration
	) {
		this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
		this.accessTokenExpiration = accessTokenExpiration;
	}

	public TokenInfo createAccessToken(User user) {
		Instant issuedAt = Instant.now();
		Instant expiresAt = issuedAt.plusMillis(accessTokenExpiration);

		String accessToken = Jwts.builder()
			.subject(String.valueOf(user.getId()))
			.claim("email", user.getEmail())
			.claim("nickname", user.getNickname())
			.issuedAt(Date.from(issuedAt))
			.expiration(Date.from(expiresAt))
			.signWith(signingKey)
			.compact();

		return new TokenInfo(accessToken, "Bearer", accessTokenExpiration);
	}

	public AuthenticatedUser getAuthenticatedUser(String accessToken) {
		Claims claims = Jwts.parser()
			.verifyWith(signingKey)
			.build()
			.parseSignedClaims(accessToken)
			.getPayload();

		return new AuthenticatedUser(
			Long.valueOf(claims.getSubject()),
			claims.get("email", String.class),
			claims.get("nickname", String.class)
		);
	}

	public record TokenInfo(String accessToken, String tokenType, long expiresIn) {
	}

}
