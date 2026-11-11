package com.capstone.itemsplit.common.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitClientKeyTest {

	private final MutableClock clock = new MutableClock(Instant.parse("2026-11-11T00:00:00Z"));
	private final RateLimitFilter filter = new RateLimitFilter(properties(2, 60), new ObjectMapper(), clock);

	@Test
	void changingXForwardedForDoesNotGiveTheSameConnectionANewLimit() throws Exception {
		assertThat(status("203.0.113.10", "198.51.100.1")).isEqualTo(200);
		assertThat(status("203.0.113.10", "198.51.100.2")).isEqualTo(200);
		assertThat(status("203.0.113.10", "198.51.100.3")).isEqualTo(429);
	}

	@Test
	void differentClientsAreLimitedSeparately() throws Exception {
		assertThat(status("203.0.113.10", null)).isEqualTo(200);
		assertThat(status("203.0.113.10", null)).isEqualTo(200);
		assertThat(status("203.0.113.11", null)).isEqualTo(200);
		assertThat(status("203.0.113.10", null)).isEqualTo(429);
	}

	@Test
	void recordsOfClientsWhoseWindowEndedAreRemoved() throws Exception {
		status("203.0.113.10", null);
		status("203.0.113.11", null);
		status("203.0.113.12", null);
		assertThat(filter.trackedClients()).isEqualTo(3);

		clock.advance(Duration.ofSeconds(61));
		assertThat(status("203.0.113.13", null)).isEqualTo(200);

		assertThat(filter.trackedClients()).isEqualTo(1);
	}

	@Test
	void aClientRemovedByTheSweepStartsAFreshWindow() throws Exception {
		status("203.0.113.10", null);
		status("203.0.113.10", null);
		assertThat(status("203.0.113.10", null)).isEqualTo(429);

		clock.advance(Duration.ofSeconds(60));

		assertThat(status("203.0.113.10", null)).isEqualTo(200);
		assertThat(filter.trackedClients()).isEqualTo(1);
	}

	private int status(String remoteAddress, String forwardedFor) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ping");
		request.setRemoteAddr(remoteAddress);
		if (forwardedFor != null) {
			request.addHeader("X-Forwarded-For", forwardedFor);
		}
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, new MockFilterChain());
		return response.getStatus();
	}

	private static RateLimitProperties properties(int capacity, int windowSeconds) {
		RateLimitProperties properties = new RateLimitProperties();
		properties.setCapacity(capacity);
		properties.setWindowSeconds(windowSeconds);
		return properties;
	}

	private static final class MutableClock extends Clock {

		private Instant now;

		private MutableClock(Instant now) {
			this.now = now;
		}

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneId.of("UTC");
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}

	}

}
