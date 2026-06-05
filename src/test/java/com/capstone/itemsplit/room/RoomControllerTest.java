package com.capstone.itemsplit.room;

import com.capstone.itemsplit.auth.JwtTokenProvider;
import com.capstone.itemsplit.assignment.Assignment;
import com.capstone.itemsplit.assignment.AssignmentRepository;
import com.capstone.itemsplit.item.Item;
import com.capstone.itemsplit.item.ItemRepository;
import com.capstone.itemsplit.receipt.Receipt;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

	@Autowired
	private RoomShareTokenRepository roomShareTokenRepository;

	@BeforeEach
	void setUp() {
		assignmentRepository.deleteAll();
		itemRepository.deleteAll();
		receiptRepository.deleteAll();
		roomShareTokenRepository.deleteAll();
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
	@DisplayName("DELETE /api/rooms/{roomId} 요청은 owner만 방과 관련 데이터를 삭제한다")
	void owner는_방과_관련_데이터를_삭제할_수_있다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		Room room = roomRepository.save(Room.create("Capstone Team", owner));
		RoomMember ownerMember = roomMemberRepository.save(RoomMember.create(room, owner));
		RoomMember manualMember = roomMemberRepository.save(RoomMember.createManual(room, "민지"));
		Receipt receipt = receiptRepository.save(Receipt.createManual(room, "Dinner", manualMember, null, null));
		Item item = itemRepository.save(Item.create(receipt, "Pasta", 15000, 1));
		assignmentRepository.save(Assignment.create(item, ownerMember));
		roomInviteTokenRepository.save(RoomInviteToken.create(room, "invite-token", LocalDateTime.now().plusDays(1)));
		roomShareTokenRepository.save(RoomShareToken.create(room, "share-token", LocalDateTime.now().plusDays(1)));

		mockMvc
			.perform(
				delete("/api/rooms/{roomId}", room.getId())
					.header(HttpHeaders.AUTHORIZATION, bearer(owner))
			)
			.andExpect(status().isNoContent());

		assertThat(roomRepository.findById(room.getId())).isEmpty();
		assertThat(roomMemberRepository.findAllByRoomId(room.getId())).isEmpty();
		assertThat(receiptRepository.findById(receipt.getId())).isEmpty();
		assertThat(itemRepository.findById(item.getId())).isEmpty();
		assertThat(assignmentRepository.findAllByItemId(item.getId())).isEmpty();
		assertThat(roomInviteTokenRepository.findByRoomId(room.getId())).isEmpty();
		assertThat(roomShareTokenRepository.findByRoomId(room.getId())).isEmpty();
	}

	@Test
	@DisplayName("DELETE /api/rooms/{roomId} 요청은 owner가 아니면 403을 반환한다")
	void owner가_아니면_방을_삭제할_수_없다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		User member = createUser("member@example.com", "member");
		Room room = roomRepository.save(Room.create("Capstone Team", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));
		roomMemberRepository.save(RoomMember.create(room, member));

		mockMvc
			.perform(
				delete("/api/rooms/{roomId}", room.getId())
					.header(HttpHeaders.AUTHORIZATION, bearer(member))
			)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
			.andExpect(jsonPath("$.error.message").value("Only room owner can delete this room."));

		assertThat(roomRepository.findById(room.getId())).isPresent();
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
	@DisplayName("초대 참여자는 수동 멤버 목록에서 자신을 선택해 방에 참여할 수 있다")
	void 초대_참여자는_수동_멤버를_선택해_방에_참여할_수_있다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		User guest = createUser("guest@example.com", "guest");
		Room room = roomRepository.save(Room.create("Capstone Team", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));

		MvcResult manualMemberResult = mockMvc
			.perform(
				post("/api/rooms/{roomId}/members/manual", room.getId())
					.header(HttpHeaders.AUTHORIZATION, bearer(owner))
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{
						  "nickname": "민지"
						}
						""")
			)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.nickname").value("민지"))
			.andExpect(jsonPath("$.data.linked").value(false))
			.andExpect(jsonPath("$.data.userId").doesNotExist())
			.andReturn();
		Long manualMemberId = readLong(manualMemberResult, "data.memberId");

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
				get("/api/rooms/join-options")
					.param("token", token)
					.header(HttpHeaders.AUTHORIZATION, bearer(guest))
			)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.members[1].memberId").value(manualMemberId))
			.andExpect(jsonPath("$.data.members[1].nickname").value("민지"))
			.andExpect(jsonPath("$.data.members[1].linked").value(false));

		mockMvc
			.perform(
				post("/api/rooms/join")
					.param("token", token)
					.param("memberId", String.valueOf(manualMemberId))
					.header(HttpHeaders.AUTHORIZATION, bearer(guest))
			)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.memberId").value(manualMemberId))
			.andExpect(jsonPath("$.data.memberNickname").value("민지"))
			.andExpect(jsonPath("$.data.joined").value(true));

		mockMvc
			.perform(
				get("/api/rooms/{roomId}/members", room.getId())
					.header(HttpHeaders.AUTHORIZATION, bearer(owner))
			)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.members[1].memberId").value(manualMemberId))
			.andExpect(jsonPath("$.data.members[1].userId").value(guest.getId()))
			.andExpect(jsonPath("$.data.members[1].linked").value(true));
	}

	@Test
	@DisplayName("PUT /api/rooms/{roomId}/members/{memberId} 요청은 멤버 표시 이름을 수정한다")
	void 멤버_표시_이름을_수정한다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		Room room = roomRepository.save(Room.create("Capstone Team", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));
		RoomMember manualMember = roomMemberRepository.save(RoomMember.createManual(room, "민지"));

		mockMvc
			.perform(
				put("/api/rooms/{roomId}/members/{memberId}", room.getId(), manualMember.getId())
					.header(HttpHeaders.AUTHORIZATION, bearer(owner))
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{
						  "nickname": "민수"
						}
						""")
			)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.memberId").value(manualMember.getId()))
			.andExpect(jsonPath("$.data.nickname").value("민수"))
			.andExpect(jsonPath("$.data.linked").value(false));

		RoomMember updated = roomMemberRepository.findById(manualMember.getId()).orElseThrow();
		assertThat(updated.getDisplayName()).isEqualTo("민수");
	}

	@Test
	@DisplayName("DELETE /api/rooms/{roomId}/members/{memberId} 요청은 수동 멤버와 배정을 삭제한다")
	void 수동_멤버와_배정을_삭제한다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		Room room = roomRepository.save(Room.create("Capstone Team", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));
		RoomMember manualMember = roomMemberRepository.save(RoomMember.createManual(room, "민지"));
		Receipt receipt = receiptRepository.save(Receipt.createManual(room, "Dinner", null, null, null));
		Item item = itemRepository.save(Item.create(receipt, "Pasta", 15000, 1));
		assignmentRepository.save(Assignment.create(item, manualMember));

		mockMvc
			.perform(
				delete("/api/rooms/{roomId}/members/{memberId}", room.getId(), manualMember.getId())
					.header(HttpHeaders.AUTHORIZATION, bearer(owner))
			)
			.andExpect(status().isNoContent());

		assertThat(roomMemberRepository.findById(manualMember.getId())).isEmpty();
		assertThat(assignmentRepository.findAllByItemId(item.getId())).isEmpty();
	}

	@Test
	@DisplayName("DELETE /api/rooms/{roomId}/members/{memberId} 요청은 결제자로 지정된 멤버 삭제를 명확히 거부한다")
	void 결제자로_지정된_멤버는_삭제할_수_없다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		Room room = roomRepository.save(Room.create("Capstone Team", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));
		RoomMember manualMember = roomMemberRepository.save(RoomMember.createManual(room, "민지"));
		receiptRepository.save(Receipt.createManual(room, "노래방", manualMember, null, null));
		receiptRepository.save(Receipt.createManual(room, "카페", manualMember, null, null));

		mockMvc
			.perform(
				delete("/api/rooms/{roomId}/members/{memberId}", room.getId(), manualMember.getId())
					.header(HttpHeaders.AUTHORIZATION, bearer(owner))
			)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message")
				.value("영수증 '노래방', '카페'에서 '민지'가 결제자로 지정되어 있습니다. 결제자를 해제한 뒤 삭제해주십시오."));

		assertThat(roomMemberRepository.findById(manualMember.getId())).isPresent();
	}

	@Test
	@DisplayName("DELETE /api/rooms/{roomId}/members/{memberId} 요청은 연결된 일반 멤버를 제외하고 재참여를 허용한다")
	void 연결된_일반_멤버를_삭제한_뒤_초대_토큰으로_재참여할_수_있다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		User guest = createUser("guest@example.com", "guest");
		Room room = roomRepository.save(Room.create("Capstone Team", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));
		RoomMember guestMember = roomMemberRepository.save(RoomMember.create(room, guest));
		Receipt receipt = receiptRepository.save(Receipt.createManual(room, "Dinner", null, null, null));
		Item item = itemRepository.save(Item.create(receipt, "Pasta", 15000, 1));
		assignmentRepository.save(Assignment.create(item, guestMember));

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
				delete("/api/rooms/{roomId}/members/{memberId}", room.getId(), guestMember.getId())
					.header(HttpHeaders.AUTHORIZATION, bearer(owner))
			)
			.andExpect(status().isNoContent());

		assertThat(roomMemberRepository.findById(guestMember.getId())).isEmpty();
		assertThat(roomMemberRepository.existsByRoomIdAndUserId(room.getId(), guest.getId())).isFalse();
		assertThat(assignmentRepository.findAllByItemId(item.getId())).isEmpty();

		MvcResult rejoinResult = mockMvc
			.perform(
				post("/api/rooms/join")
					.param("token", token)
					.header(HttpHeaders.AUTHORIZATION, bearer(guest))
			)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.joined").value(true))
			.andExpect(jsonPath("$.data.memberNickname").value("guest"))
			.andReturn();

		Long rejoinedMemberId = readLong(rejoinResult, "data.memberId");
		assertThat(rejoinedMemberId).isNotEqualTo(guestMember.getId());
		assertThat(roomMemberRepository.existsByRoomIdAndUserId(room.getId(), guest.getId())).isTrue();
	}

	@Test
	@DisplayName("DELETE /api/rooms/{roomId}/members/{memberId} 요청은 owner 삭제를 거부한다")
	void owner_멤버는_삭제할_수_없다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		Room room = roomRepository.save(Room.create("Capstone Team", owner));
		RoomMember ownerMember = roomMemberRepository.save(RoomMember.create(room, owner));

		mockMvc
			.perform(
				delete("/api/rooms/{roomId}/members/{memberId}", room.getId(), ownerMember.getId())
					.header(HttpHeaders.AUTHORIZATION, bearer(owner))
			)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").value("Room owner cannot be deleted."));

		assertThat(roomMemberRepository.existsByRoomIdAndUserId(room.getId(), owner.getId())).isTrue();
	}

	@Test
	@DisplayName("DELETE /api/rooms/{roomId}/members/{memberId} 요청은 일반 멤버가 자기 자신을 삭제해 방을 나갈 수 있다")
	void 일반_멤버는_자기_자신을_삭제해_방을_나갈_수_있다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		User guest = createUser("guest@example.com", "guest");
		Room room = roomRepository.save(Room.create("Capstone Team", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));
		RoomMember guestMember = roomMemberRepository.save(RoomMember.create(room, guest));
		Receipt receipt = receiptRepository.save(Receipt.createManual(room, "Dinner", null, null, null));
		Item item = itemRepository.save(Item.create(receipt, "Pasta", 15000, 1));
		assignmentRepository.save(Assignment.create(item, guestMember));

		mockMvc
			.perform(
				delete("/api/rooms/{roomId}/members/{memberId}", room.getId(), guestMember.getId())
					.header(HttpHeaders.AUTHORIZATION, bearer(guest))
			)
			.andExpect(status().isNoContent());

		assertThat(roomMemberRepository.findById(guestMember.getId())).isEmpty();
		assertThat(roomMemberRepository.existsByRoomIdAndUserId(room.getId(), guest.getId())).isFalse();
		assertThat(assignmentRepository.findAllByItemId(item.getId())).isEmpty();
	}

	@Test
	@DisplayName("DELETE /api/rooms/{roomId}/members/{memberId} 요청은 일반 멤버가 다른 일반 멤버를 삭제할 수 있다")
	void 일반_멤버는_다른_일반_멤버를_삭제할_수_있다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		User guest = createUser("guest@example.com", "guest");
		User another = createUser("another@example.com", "another");
		Room room = roomRepository.save(Room.create("Capstone Team", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));
		roomMemberRepository.save(RoomMember.create(room, guest));
		RoomMember anotherMember = roomMemberRepository.save(RoomMember.create(room, another));
		Receipt receipt = receiptRepository.save(Receipt.createManual(room, "Dinner", null, null, null));
		Item item = itemRepository.save(Item.create(receipt, "Pasta", 15000, 1));
		assignmentRepository.save(Assignment.create(item, anotherMember));

		mockMvc
			.perform(
				delete("/api/rooms/{roomId}/members/{memberId}", room.getId(), anotherMember.getId())
					.header(HttpHeaders.AUTHORIZATION, bearer(guest))
			)
			.andExpect(status().isNoContent());

		assertThat(roomMemberRepository.findById(anotherMember.getId())).isEmpty();
		assertThat(assignmentRepository.findAllByItemId(item.getId())).isEmpty();
	}

	@Test
	@DisplayName("DELETE /api/rooms/{roomId}/members/{memberId} 요청은 결제자인 일반 멤버의 방 나가기를 명확히 거부한다")
	void 결제자인_일반_멤버는_방을_나갈_수_없다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		User guest = createUser("guest@example.com", "guest");
		Room room = roomRepository.save(Room.create("Capstone Team", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));
		RoomMember guestMember = roomMemberRepository.save(RoomMember.create(room, guest));
		receiptRepository.save(Receipt.createManual(room, "노래방", guestMember, null, null));
		receiptRepository.save(Receipt.createManual(room, "카페", guestMember, null, null));

		mockMvc
			.perform(
				delete("/api/rooms/{roomId}/members/{memberId}", room.getId(), guestMember.getId())
					.header(HttpHeaders.AUTHORIZATION, bearer(guest))
			)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message")
				.value("영수증 '노래방', '카페'에서 'guest'가 결제자로 지정되어 있습니다. 결제자를 해제한 뒤 삭제해주십시오."));

		assertThat(roomMemberRepository.findById(guestMember.getId())).isPresent();
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
