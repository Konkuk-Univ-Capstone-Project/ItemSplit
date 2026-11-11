package com.capstone.itemsplit.common.ratelimit;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs through the real Tomcat, where the test client connects from 127.0.0.1 and so plays the
 * internal proxy (nginx). Only the right-most address the proxy reports counts as the client.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"app.rate-limit.enabled=true",
	"app.rate-limit.capacity=2",
	"app.rate-limit.window-seconds=60"
})
@ActiveProfiles("test")
class RateLimitProxyHeaderTest {

	@LocalServerPort
	private int port;

	private final HttpClient client = HttpClient.newHttpClient();

	@Test
	void addressesPrependedByTheClientCannotSplitOneClientIntoMany() throws Exception {
		// A proxy that appends ($proxy_add_x_forwarded_for) still ends with the real client address.
		assertThat(ping("198.51.100.1, 203.0.113.20")).isEqualTo(200);
		assertThat(ping("198.51.100.2, 203.0.113.20")).isEqualTo(200);
		assertThat(ping("198.51.100.3, 203.0.113.20")).isEqualTo(429);
	}

	@Test
	void differentClientsBehindTheSameProxyAreNotMerged() throws Exception {
		assertThat(ping("203.0.113.30")).isEqualTo(200);
		assertThat(ping("203.0.113.30")).isEqualTo(200);
		assertThat(ping("203.0.113.31")).isEqualTo(200);
		assertThat(ping("203.0.113.30")).isEqualTo(429);
	}

	private int ping(String forwardedFor) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ping"))
			.header("X-Forwarded-For", forwardedFor)
			.GET()
			.build();
		return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
	}

}
