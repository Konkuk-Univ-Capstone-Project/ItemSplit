package com.capstone.itemsplit.common.ratelimit;

import com.capstone.itemsplit.common.exception.ErrorCode;
import com.capstone.itemsplit.common.response.ApiResponse;
import com.capstone.itemsplit.common.response.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

	private final RateLimitProperties properties;
	private final ObjectMapper objectMapper;
	private final Clock clock = Clock.systemUTC();
	private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !properties.isEnabled() || !request.getRequestURI().startsWith("/api/");
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		LimitDecision decision = tryConsume(resolveClientKey(request));
		response.setHeader("X-RateLimit-Limit", String.valueOf(properties.getCapacity()));
		response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));

		if (decision.allowed()) {
			filterChain.doFilter(request, response);
			return;
		}

		response.setStatus(ErrorCode.TOO_MANY_REQUESTS.getStatus().value());
		response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		ErrorResponse error = ErrorResponse.of(
			ErrorCode.TOO_MANY_REQUESTS,
			ErrorCode.TOO_MANY_REQUESTS.getMessage(),
			List.of()
		);
		objectMapper.writeValue(response.getWriter(), ApiResponse.failure(error));
	}

	private LimitDecision tryConsume(String key) {
		long now = clock.instant().getEpochSecond();
		Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket(now));

		synchronized (bucket) {
			long elapsed = now - bucket.windowStartedAt();
			if (elapsed >= properties.getWindowSeconds()) {
				bucket.reset(now);
			}

			if (bucket.count() < properties.getCapacity()) {
				bucket.consume();
				return new LimitDecision(true, properties.getCapacity() - bucket.count(), 0);
			}

			long retryAfterSeconds = Math.max(1, properties.getWindowSeconds() - (now - bucket.windowStartedAt()));
			return new LimitDecision(false, 0, retryAfterSeconds);
		}
	}

	private String resolveClientKey(HttpServletRequest request) {
		String forwardedFor = request.getHeader("X-Forwarded-For");
		if (forwardedFor != null && !forwardedFor.isBlank()) {
			return forwardedFor.split(",")[0].trim();
		}
		return request.getRemoteAddr();
	}

	private record LimitDecision(boolean allowed, int remaining, long retryAfterSeconds) {
	}

	private static final class Bucket {

		private long windowStartedAt;
		private int count;

		private Bucket(long windowStartedAt) {
			this.windowStartedAt = windowStartedAt;
		}

		private long windowStartedAt() {
			return windowStartedAt;
		}

		private int count() {
			return count;
		}

		private void consume() {
			count++;
		}

		private void reset(long windowStartedAt) {
			this.windowStartedAt = windowStartedAt;
			this.count = 0;
		}

	}

}
