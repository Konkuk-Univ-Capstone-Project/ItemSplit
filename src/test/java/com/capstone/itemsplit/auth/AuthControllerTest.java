package com.capstone.itemsplit.auth;

import com.capstone.itemsplit.assignment.AssignmentRepository;
import com.capstone.itemsplit.item.ItemRepository;
import com.capstone.itemsplit.receipt.ReceiptRepository;
import com.capstone.itemsplit.room.RoomRepository;
import com.capstone.itemsplit.room.RoomInviteTokenRepository;
import com.capstone.itemsplit.room.RoomMemberRepository;
import com.capstone.itemsplit.user.User;
import com.capstone.itemsplit.user.UserRepository;
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
	private AssignmentRepository assignmentRepository;

	@Autowired
	private ItemRepository itemRepository;

	@Autowired
	private ReceiptRepository receiptRepository;

	@Autowired
	private RoomInviteTokenRepository roomInviteTokenRepository;

	@Autowired
	private RoomMemberRepository roomMemberRepository;

	@Autowired
	private RoomRepository roomRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@BeforeEach
	void setUp() {
		assignmentRepository.deleteAll();
		itemRepository.deleteAll();
		receiptRepository.deleteAll();
		roomInviteTokenRepository.deleteAll();
		roomMemberRepository.deleteAll();
		roomRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	@DisplayName("POST /api/auth/signup 요청은 BCrypt 비밀번호로 새 사용자를 생성한다")
	void 회원가입_요청은_BCrypt_비밀번호로_사용자를_생성한다() throws Exception {
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
	@DisplayName("POST /api/auth/signup 요청은 이메일이 중복되면 검증 오류를 반환한다")
	void 중복_이메일_회원가입은_검증_오류를_반환한다() throws Exception {
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
	@DisplayName("POST /api/auth/login 요청은 올바른 인증 정보에 액세스 토큰을 반환한다")
	void 올바른_로그인_요청은_액세스_토큰을_반환한다() throws Exception {
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
	@DisplayName("POST /api/auth/login 요청은 잘못된 인증 정보에 401을 반환한다")
	void 잘못된_로그인_요청은_인증_실패를_반환한다() throws Exception {
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
	@DisplayName("GET /api/auth/me 요청은 액세스 토큰이 없으면 401을 반환한다")
	void 내_정보_조회는_토큰이_없으면_인증_실패를_반환한다() throws Exception {
		mockMvc
			.perform(get("/api/auth/me"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}

	@Test
	@DisplayName("GET /api/auth/me 요청은 유효한 액세스 토큰의 사용자를 반환한다")
	void 내_정보_조회는_유효한_토큰의_사용자를_반환한다() throws Exception {
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
