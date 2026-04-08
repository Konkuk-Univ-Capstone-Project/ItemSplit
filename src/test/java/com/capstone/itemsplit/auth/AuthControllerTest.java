package com.capstone.itemsplit.auth;

import com.capstone.itemsplit.domain.user.User;
import com.capstone.itemsplit.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@BeforeEach
	void setUp() {
		userRepository.deleteAll();
	}

	@Test
	@DisplayName("POST /api/auth/signup creates a new user with a BCrypt password")
	void signupCreatesUser() throws Exception {
		mockMvc
			.perform(
				post("/api/auth/signup")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{
						  "email": "user@example.com",
						  "password": "password123",
						  "nickname": "itemsplit"
						}
						""")
			)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.email").value("user@example.com"))
			.andExpect(jsonPath("$.data.nickname").value("itemsplit"));

		User user = userRepository.findByEmail("user@example.com").orElseThrow();
		assertThat(user.getPassword()).isNotEqualTo("password123");
		assertThat(passwordEncoder.matches("password123", user.getPassword())).isTrue();
	}

	@Test
	@DisplayName("POST /api/auth/signup returns validation error when email is duplicated")
	void signupFailsWhenEmailAlreadyExists() throws Exception {
		userRepository.save(User.create(
			"user@example.com",
			passwordEncoder.encode("password123"),
			"existing"
		));

		mockMvc
			.perform(
				post("/api/auth/signup")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{
						  "email": "user@example.com",
						  "password": "password123",
						  "nickname": "duplicate"
						}
						""")
			)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").value("Email is already in use."));
	}

	@Test
	@DisplayName("POST /api/auth/login returns an access token for valid credentials")
	void loginReturnsAccessToken() throws Exception {
		userRepository.save(User.create(
			"user@example.com",
			passwordEncoder.encode("password123"),
			"itemsplit"
		));

		mockMvc
			.perform(
				post("/api/auth/login")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{
						  "email": "user@example.com",
						  "password": "password123"
						}
						""")
			)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.email").value("user@example.com"))
			.andExpect(jsonPath("$.data.accessToken").isNotEmpty())
			.andExpect(jsonPath("$.data.tokenType").value("Bearer"));
	}

	@Test
	@DisplayName("POST /api/auth/login returns unauthorized for invalid credentials")
	void loginFailsForInvalidCredentials() throws Exception {
		userRepository.save(User.create(
			"user@example.com",
			passwordEncoder.encode("password123"),
			"itemsplit"
		));

		mockMvc
			.perform(
				post("/api/auth/login")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{
						  "email": "user@example.com",
						  "password": "wrong-password"
						}
						""")
			)
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.message").value("Email or password is incorrect."));
	}

	@Test
	@DisplayName("GET /api/auth/me returns unauthorized when the access token is missing")
	void meRequiresAuthentication() throws Exception {
		mockMvc
			.perform(get("/api/auth/me"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}

	@Test
	@DisplayName("GET /api/auth/me returns the authenticated user for a valid access token")
	void meReturnsAuthenticatedUser() throws Exception {
		User user = userRepository.save(User.create(
			"user@example.com",
			passwordEncoder.encode("password123"),
			"itemsplit"
		));
		String accessToken = jwtTokenProvider.createAccessToken(user).accessToken();

		mockMvc
			.perform(
				get("/api/auth/me")
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.userId").value(user.getId()))
			.andExpect(jsonPath("$.data.email").value("user@example.com"))
			.andExpect(jsonPath("$.data.nickname").value("itemsplit"));
	}

}
