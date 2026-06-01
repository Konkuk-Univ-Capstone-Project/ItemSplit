package com.capstone.itemsplit.common.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
	"app.rate-limit.enabled=true",
	"app.rate-limit.capacity=2",
	"app.rate-limit.window-seconds=60"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RateLimitFilterTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("API 요청이 제한량을 넘으면 429 표준 응답을 반환한다")
	void API_요청이_제한량을_넘으면_429를_반환한다() throws Exception {
		mockMvc.perform(get("/api/ping").with(request -> {
			request.setRemoteAddr("203.0.113.10");
			return request;
		})).andExpect(status().isOk());

		mockMvc.perform(get("/api/ping").with(request -> {
			request.setRemoteAddr("203.0.113.10");
			return request;
		})).andExpect(status().isOk());

		mockMvc
			.perform(get("/api/ping").with(request -> {
				request.setRemoteAddr("203.0.113.10");
				return request;
			}))
			.andExpect(status().isTooManyRequests())
			.andExpect(header().exists("Retry-After"))
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("TOO_MANY_REQUESTS"));
	}

}
