package com.capstone.itemsplit.room;

import com.capstone.itemsplit.auth.JwtTokenProvider;
import com.capstone.itemsplit.assignment.AssignmentRepository;
import com.capstone.itemsplit.item.ItemRepository;
import com.capstone.itemsplit.receipt.ReceiptRepository;
import com.capstone.itemsplit.user.User;
import com.capstone.itemsplit.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoomControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private RoomRepository roomRepository;

	@Autowired
	private RoomMemberRepository roomMemberRepository;

	@Autowired
	private AssignmentRepository assignmentRepository;

	@Autowired
	private ItemRepository itemRepository;

	@Autowired
	private ReceiptRepository receiptRepository;

	@Autowired
	private RoomInviteTokenRepository roomInviteTokenRepository;

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
	@DisplayName("POST /api/rooms 요청은 방을 생성하고 소유자를 멤버로 자동 등록한다")
	void 방_생성은_소유자를_멤버로_자동_등록한다() throws Exception {
		User owner = createUser("owner@example.com", "owner");

		MvcResult result = mockMvc
			.perform(
				post("/api/rooms")
					.header(HttpHeaders.AUTHORIZATION, bearer(owner))
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{
						  "name": "Capstone Team"
						}
						""")
			)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.roomName").value("Capstone Team"))
			.andReturn();

		Long roomId = readLong(result, "data.roomId");
		assertThat(roomMemberRepository.existsByRoomIdAndUserId(roomId, owner.getId())).isTrue();
	}

	@Test
	@DisplayName("GET /api/rooms/{roomId}/members 요청은 방 멤버가 아니면 403을 반환한다")
	void 멤버_조회는_방_멤버가_아니면_403을_반환한다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		User stranger = createUser("stranger@example.com", "stranger");
		Room room = roomRepository.save(Room.create("Capstone Team", owner));
		roomMemberRepository.save(com.capstone.itemsplit.room.RoomMember.create(room, owner));

		mockMvc
			.perform(
				get("/api/rooms/{roomId}/members", room.getId())
					.header(HttpHeaders.AUTHORIZATION, bearer(stranger))
			)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
			.andExpect(jsonPath("$.error.message").value("You are not a member of this room."));
	}

	@Test
	@DisplayName("POST /api/rooms/{roomId}/invite-token 요청은 새 토큰을 발급하고 기존 토큰을 무효화한다")
	void 초대_토큰_재발급은_새_토큰을_발급하고_기존_토큰을_무효화한다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		User invitedUser = createUser("guest@example.com", "guest");
		Room room = roomRepository.save(Room.create("Capstone Team", owner));
		roomMemberRepository.save(com.capstone.itemsplit.room.RoomMember.create(room, owner));

		MvcResult firstIssue = mockMvc
			.perform(
				post("/api/rooms/{roomId}/invite-token", room.getId())
					.header(HttpHeaders.AUTHORIZATION, bearer(owner))
			)
			.andExpect(status().isOk())
			.andReturn();

		MvcResult secondIssue = mockMvc
			.perform(
				post("/api/rooms/{roomId}/invite-token", room.getId())
					.header(HttpHeaders.AUTHORIZATION, bearer(owner))
			)
			.andExpect(status().isOk())
			.andReturn();

		String firstToken = readText(firstIssue, "data.token");
		String secondToken = readText(secondIssue, "data.token");
		assertThat(secondToken).isNotEqualTo(firstToken);

		mockMvc
			.perform(
				post("/api/rooms/join")
					.param("token", firstToken)
					.header(HttpHeaders.AUTHORIZATION, bearer(invitedUser))
			)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		mockMvc
			.perform(
				post("/api/rooms/join")
					.param("token", secondToken)
					.header(HttpHeaders.AUTHORIZATION, bearer(invitedUser))
			)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.joined").value(true));
	}

	@Test
	@DisplayName("POST /api/rooms/join 요청은 같은 토큰으로 여러 사용자의 참여를 허용한다")
	void 같은_초대_토큰으로_여러_사용자가_참여할_수_있다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		User guestOne = createUser("guest1@example.com", "guest1");
		User guestTwo = createUser("guest2@example.com", "guest2");
		Room room = roomRepository.save(Room.create("Capstone Team", owner));
		roomMemberRepository.save(com.capstone.itemsplit.room.RoomMember.create(room, owner));

		MvcResult issueResult = mockMvc
			.perform(
				post("/api/rooms/{roomId}/invite-token", room.getId())
					.header(HttpHeaders.AUTHORIZATION, bearer(owner))
			)
			.andExpect(status().isOk())
			.andReturn();
		String token = readText(issueResult, "data.token");

		mockMvc
			.perform(
				post("/api/rooms/join")
					.param("token", token)
					.header(HttpHeaders.AUTHORIZATION, bearer(guestOne))
			)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.joined").value(true));

		mockMvc
			.perform(
				post("/api/rooms/join")
					.param("token", token)
					.header(HttpHeaders.AUTHORIZATION, bearer(guestTwo))
			)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.joined").value(true));

		mockMvc
			.perform(
				get("/api/rooms/{roomId}/members", room.getId())
					.header(HttpHeaders.AUTHORIZATION, bearer(owner))
			)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.members.length()").value(3));
	}

	@Test
	@DisplayName("POST /api/rooms/join 요청은 만료된 초대 토큰을 거부한다")
	void 만료된_초대_토큰은_방_참여를_거부한다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		User guest = createUser("guest@example.com", "guest");
		Room room = roomRepository.save(Room.create("Capstone Team", owner));
		roomMemberRepository.save(com.capstone.itemsplit.room.RoomMember.create(room, owner));
		roomInviteTokenRepository.save(
			RoomInviteToken.create(room, "expired-token", LocalDateTime.now().minusDays(1))
		);

		mockMvc
			.perform(
				post("/api/rooms/join")
					.param("token", "expired-token")
					.header(HttpHeaders.AUTHORIZATION, bearer(guest))
			)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").value("Invite token is invalid or expired."));
	}

	private User createUser(String email, String nickname) {
		return userRepository.save(User.create(
			email,
			passwordEncoder.encode("password123"),
			nickname
		));
	}

	private String bearer(User user) {
		return "Bearer " + jwtTokenProvider.createAccessToken(user).accessToken();
	}

	private Long readLong(MvcResult mvcResult, String path) throws Exception {
		JsonNode root = objectMapper.readTree(mvcResult.getResponse().getContentAsString());
		return root.at(pointer(path)).asLong();
	}

	private String readText(MvcResult mvcResult, String path) throws Exception {
		JsonNode root = objectMapper.readTree(mvcResult.getResponse().getContentAsString());
		return root.at(pointer(path)).asText();
	}

	private String pointer(String path) {
		return "/" + path.replace(".", "/");
	}

}
