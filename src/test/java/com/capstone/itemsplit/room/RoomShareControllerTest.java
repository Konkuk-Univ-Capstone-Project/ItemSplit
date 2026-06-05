package com.capstone.itemsplit.room;

import com.capstone.itemsplit.assignment.Assignment;
import com.capstone.itemsplit.assignment.AssignmentRepository;
import com.capstone.itemsplit.auth.JwtTokenProvider;
import com.capstone.itemsplit.item.Item;
import com.capstone.itemsplit.item.ItemRepository;
import com.capstone.itemsplit.receipt.Receipt;
import com.capstone.itemsplit.receipt.ReceiptRepository;
import com.capstone.itemsplit.user.User;
import com.capstone.itemsplit.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoomShareControllerTest {

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
	private ReceiptRepository receiptRepository;

	@Autowired
	private ItemRepository itemRepository;

	@Autowired
	private AssignmentRepository assignmentRepository;

	@Autowired
	private RoomInviteTokenRepository roomInviteTokenRepository;

	@Autowired
	private RoomShareTokenRepository roomShareTokenRepository;

	@BeforeEach
	void setUp() {
		cleanUp();
	}

	@AfterEach
	void tearDown() {
		cleanUp();
	}

	@Test
	@DisplayName("방 멤버는 읽기 전용 공유 토큰을 발급할 수 있다")
	void 방_멤버는_읽기_전용_공유_토큰을_발급할_수_있다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		Room room = createRoomWithMember(owner);

		mockMvc
			.perform(
				post("/api/rooms/{roomId}/share-token", room.getId())
					.header(HttpHeaders.AUTHORIZATION, bearer(owner))
			)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.roomId").value(room.getId()))
			.andExpect(jsonPath("$.data.roomName").value("Capstone Team"))
			.andExpect(jsonPath("$.data.token").isNotEmpty())
			.andExpect(jsonPath("$.data.readOnly").value(true));
	}

	@Test
	@DisplayName("공유 토큰으로 인증 없이 정산 결과를 읽기 전용 조회할 수 있다")
	void 공유_토큰으로_인증_없이_정산_결과를_조회할_수_있다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		User member = createUser("member@example.com", "member");
		Room room = createRoomWithMember(owner);
		roomMemberRepository.save(RoomMember.create(room, member));
		createSettlementFixture(room, owner, member);

		MvcResult issueResult = mockMvc
			.perform(
				post("/api/rooms/{roomId}/share-token", room.getId())
					.header(HttpHeaders.AUTHORIZATION, bearer(owner))
			)
			.andExpect(status().isOk())
			.andReturn();
		String token = readText(issueResult, "data.token");

		mockMvc
			.perform(get("/api/shared/rooms/{token}/settlements", token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.roomId").value(room.getId()))
			.andExpect(jsonPath("$.data.readOnly").value(true))
			.andExpect(jsonPath("$.data.members[?(@.nickname=='owner')].paid").value(10000))
			.andExpect(jsonPath("$.data.members[?(@.nickname=='member')].net").value(-5000));
	}

	@Test
	@DisplayName("공유 토큰 재발급 시 이전 토큰은 더 이상 사용할 수 없다")
	void 공유_토큰_재발급은_이전_토큰을_무효화한다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		Room room = createRoomWithMember(owner);

		MvcResult firstIssue = mockMvc
			.perform(
				post("/api/rooms/{roomId}/share-token", room.getId())
					.header(HttpHeaders.AUTHORIZATION, bearer(owner))
			)
			.andExpect(status().isOk())
			.andReturn();

		MvcResult secondIssue = mockMvc
			.perform(
				post("/api/rooms/{roomId}/share-token", room.getId())
					.header(HttpHeaders.AUTHORIZATION, bearer(owner))
			)
			.andExpect(status().isOk())
			.andReturn();

		String firstToken = readText(firstIssue, "data.token");
		String secondToken = readText(secondIssue, "data.token");

		mockMvc
			.perform(get("/api/shared/rooms/{token}/settlements", firstToken))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		mockMvc
			.perform(get("/api/shared/rooms/{token}/settlements", secondToken))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("방 멤버가 아닌 사용자는 공유 토큰을 발급할 수 없다")
	void 비멤버는_공유_토큰을_발급할_수_없다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		User stranger = createUser("stranger@example.com", "stranger");
		Room room = createRoomWithMember(owner);

		mockMvc
			.perform(
				post("/api/rooms/{roomId}/share-token", room.getId())
					.header(HttpHeaders.AUTHORIZATION, bearer(stranger))
			)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}

	@Test
	@DisplayName("만료된 공유 토큰은 정산 조회에 사용할 수 없다")
	void 만료된_공유_토큰은_정산_조회에_사용할_수_없다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		Room room = createRoomWithMember(owner);
		roomShareTokenRepository.save(
			RoomShareToken.create(room, "expired-share-token", LocalDateTime.now().minusDays(1))
		);

		mockMvc
			.perform(get("/api/shared/rooms/{token}/settlements", "expired-share-token"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").value("Share token is invalid or expired."));
	}

	private void createSettlementFixture(Room room, User owner, User member) {
		RoomMember ownerMember = roomMemberRepository.findByRoomIdAndUserId(room.getId(), owner.getId()).orElseThrow();
		RoomMember memberMember = roomMemberRepository.findByRoomIdAndUserId(room.getId(), member.getId()).orElseThrow();
		Receipt receipt = receiptRepository.save(Receipt.createManual(room, "Cafe", ownerMember, null, null));
		Item coffee = itemRepository.save(Item.create(receipt, "Coffee", 10000, 1));
		assignmentRepository.save(Assignment.create(coffee, ownerMember));
		assignmentRepository.save(Assignment.create(coffee, memberMember));
	}

	private Room createRoomWithMember(User owner) {
		Room room = roomRepository.save(Room.create("Capstone Team", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));
		return room;
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

	private String readText(MvcResult mvcResult, String path) throws Exception {
		JsonNode root = objectMapper.readTree(mvcResult.getResponse().getContentAsString());
		return root.at(pointer(path)).asText();
	}

	private String pointer(String path) {
		return "/" + path.replace(".", "/");
	}

	private void cleanUp() {
		assignmentRepository.deleteAll();
		itemRepository.deleteAll();
		receiptRepository.deleteAll();
		roomShareTokenRepository.deleteAll();
		roomInviteTokenRepository.deleteAll();
		roomMemberRepository.deleteAll();
		roomRepository.deleteAll();
		userRepository.deleteAll();
	}

}
