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
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

	private final RateLimitProperties properties;
	private final ObjectMapper objectMapper;
	private final Clock clock;
	private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();
	private final AtomicLong lastSweepAt;

	@Autowired
	public RateLimitFilter(RateLimitProperties properties, ObjectMapper objectMapper) {
		this(properties, objectMapper, Clock.systemUTC());
	}

	RateLimitFilter(RateLimitProperties properties, ObjectMapper objectMapper, Clock clock) {
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.clock = clock;
		this.lastSweepAt = new AtomicLong(clock.instant().getEpochSecond());
	}

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
		// The client address is never read from X-Forwarded-For here. Tomcat's RemoteIpValve
		// (server.forward-headers-strategy=native) applies that header only when the request comes from
		// an internal proxy, taking the right-most address the proxy chain did not add itself.
		LimitDecision decision = tryConsume(request.getRemoteAddr());
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

	int trackedClients() {
		return buckets.size();
	}

	private LimitDecision tryConsume(String key) {
		long now = clock.instant().getEpochSecond();
		sweepIdleBuckets(now);

		while (true) {
			Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket(now));
			synchronized (bucket) {
				if (bucket.removed) {
					continue;
				}
				long elapsed = now - bucket.windowStartedAt;
				if (elapsed >= properties.getWindowSeconds()) {
					bucket.reset(now);
				}

				if (bucket.count < properties.getCapacity()) {
					bucket.count++;
					return new LimitDecision(true, properties.getCapacity() - bucket.count, 0);
				}

				long retryAfterSeconds = Math.max(1, properties.getWindowSeconds() - (now - bucket.windowStartedAt));
				return new LimitDecision(false, 0, retryAfterSeconds);
			}
		}
	}

	// At most once per window, drop clients whose window has already ended so the map holds only recent clients.
	private void sweepIdleBuckets(long now) {
		long last = lastSweepAt.get();
		if (now - last < properties.getWindowSeconds() || !lastSweepAt.compareAndSet(last, now)) {
			return;
		}
		buckets.forEach((key, bucket) -> {
			synchronized (bucket) {
				if (now - bucket.windowStartedAt >= properties.getWindowSeconds()) {
					bucket.removed = true;
					buckets.remove(key, bucket);
				}
			}
		});
	}

	private record LimitDecision(boolean allowed, int remaining, long retryAfterSeconds) {
	}

	private static final class Bucket {

		private long windowStartedAt;
		private int count;
		// Set under the bucket's lock when the sweep drops it; a request holding a stale reference retries.
		private boolean removed;

		private Bucket(long windowStartedAt) {
			this.windowStartedAt = windowStartedAt;
		}

		private void reset(long windowStartedAt) {
			this.windowStartedAt = windowStartedAt;
			this.count = 0;
		}

	}

}
