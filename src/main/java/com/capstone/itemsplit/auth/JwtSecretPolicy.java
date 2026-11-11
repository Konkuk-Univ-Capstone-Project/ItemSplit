package com.capstone.itemsplit.auth;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Rejects JWT signing keys that must never protect real accounts. Messages name the problem only,
 * never the key itself.
 */
final class JwtSecretPolicy {

	// HS256 needs a key of at least 256 bits. Longer keys make jjwt pick HS384/HS512, which are also satisfied.
	static final int MIN_KEY_BYTES = 32;

	// Values published in this repository (application.yml, .env.example, docker-compose.yml).
	private static final Set<String> PUBLIC_VALUES = Set.of(
		"change-me-to-a-long-random-secret-for-itemsplit",
		"change-me-to-a-long-random-secret"
	);

	private JwtSecretPolicy() {
	}

	static void requireProductionSecret(String secret) {
		if (secret == null || secret.isBlank()) {
			throw invalid("JWT_SECRET is not set.");
		}
		String trimmed = secret.trim();
		if (PUBLIC_VALUES.contains(trimmed) || trimmed.toLowerCase().startsWith("change-me")) {
			throw invalid("JWT_SECRET is a public example value.");
		}
		if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_KEY_BYTES) {
			throw invalid("JWT_SECRET is shorter than " + MIN_KEY_BYTES + " bytes, the minimum for HS256.");
		}
	}

	private static IllegalStateException invalid(String reason) {
		return new IllegalStateException(reason
			+ " The prod profile refuses to start without a private random key."
			+ " Generate one with: openssl rand -base64 48");
	}

}
