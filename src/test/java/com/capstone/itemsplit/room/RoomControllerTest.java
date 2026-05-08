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
	@DisplayName("POST /api/rooms creates a room and automatically registers the owner as a member")
	void createRoomCreatesOwnerMembership() throws Exception {
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
	@DisplayName("GET /api/rooms/{roomId}/members returns forbidden for non-members")
	void getMembersRequiresRoomMembership() throws Exception {
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
	@DisplayName("POST /api/rooms/{roomId}/invite-token reissues a new token and invalidates the previous token")
	void issueInviteTokenReissuesToken() throws Exception {
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
	@DisplayName("POST /api/rooms/join allows multiple users to join with the same token")
	void joinRoomSupportsMultiUseToken() throws Exception {
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
	@DisplayName("POST /api/rooms/join rejects expired invite tokens")
	void joinRoomRejectsExpiredToken() throws Exception {
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
