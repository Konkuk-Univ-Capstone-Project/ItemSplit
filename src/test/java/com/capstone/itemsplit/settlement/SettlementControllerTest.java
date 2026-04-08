package com.capstone.itemsplit.settlement;

import com.capstone.itemsplit.auth.JwtTokenProvider;
import com.capstone.itemsplit.domain.assignment.Assignment;
import com.capstone.itemsplit.domain.assignment.AssignmentRepository;
import com.capstone.itemsplit.domain.item.Item;
import com.capstone.itemsplit.domain.item.ItemRepository;
import com.capstone.itemsplit.domain.receipt.Receipt;
import com.capstone.itemsplit.domain.receipt.ReceiptRepository;
import com.capstone.itemsplit.domain.room.Room;
import com.capstone.itemsplit.domain.room.RoomRepository;
import com.capstone.itemsplit.domain.roommember.RoomMember;
import com.capstone.itemsplit.domain.roommember.RoomMemberRepository;
import com.capstone.itemsplit.domain.user.User;
import com.capstone.itemsplit.domain.user.UserRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SettlementControllerTest {

	@Autowired
	private MockMvc mockMvc;

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

	@BeforeEach
	void setUp() {
		assignmentRepository.deleteAll();
		itemRepository.deleteAll();
		receiptRepository.deleteAll();
		roomMemberRepository.deleteAll();
		roomRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	@DisplayName("참여자별 품목이 다를 때 각자 부담액이 다르게 계산된다")
	void differentAssigneesPerItemProduceDifferentBurdens() throws Exception {
		User owner = createUser("owner@test.com", "owner");
		User member = createUser("member@test.com", "member");
		Room room = roomRepository.save(Room.create("dinner", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));
		roomMemberRepository.save(RoomMember.create(room, member));

		Receipt receipt = receiptRepository.save(Receipt.createManual(room, "저녁식사", owner, null, null));
		// 삼겹살 30000원 — owner, member 둘 다 참여 → 각 15000
		Item pork = itemRepository.save(Item.create(receipt, "삼겹살", 30000, 1));
		// 소주 15000원 — owner만 참여 → owner 15000
		Item soju = itemRepository.save(Item.create(receipt, "소주", 15000, 1));

		assignmentRepository.save(Assignment.create(pork, owner));
		assignmentRepository.save(Assignment.create(pork, member));
		assignmentRepository.save(Assignment.create(soju, owner));

		mockMvc.perform(get("/api/rooms/{roomId}/settlements", room.getId())
				.header(HttpHeaders.AUTHORIZATION, bearer(owner)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.members[?(@.nickname=='owner')].burden").value(30000))
			.andExpect(jsonPath("$.data.members[?(@.nickname=='member')].burden").value(15000));
	}

	@Test
	@DisplayName("결제자가 있으면 paid와 net이 계산된다")
	void payerReceivesCorrectNetAmount() throws Exception {
		User owner = createUser("owner@test.com", "owner");
		User member = createUser("member@test.com", "member");
		Room room = roomRepository.save(Room.create("dinner", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));
		roomMemberRepository.save(RoomMember.create(room, member));

		// owner가 결제자, 10000원 품목을 둘이 N빵
		Receipt receipt = receiptRepository.save(Receipt.createManual(room, "카페", owner, null, null));
		Item coffee = itemRepository.save(Item.create(receipt, "아메리카노", 10000, 1));
		assignmentRepository.save(Assignment.create(coffee, owner));
		assignmentRepository.save(Assignment.create(coffee, member));

		mockMvc.perform(get("/api/rooms/{roomId}/settlements", room.getId())
				.header(HttpHeaders.AUTHORIZATION, bearer(owner)))
			.andExpect(status().isOk())
			// owner: burden=5000, paid=10000, net=+5000
			.andExpect(jsonPath("$.data.members[?(@.nickname=='owner')].burden").value(5000))
			.andExpect(jsonPath("$.data.members[?(@.nickname=='owner')].paid").value(10000))
			.andExpect(jsonPath("$.data.members[?(@.nickname=='owner')].net").value(5000))
			// member: burden=5000, paid=0, net=-5000
			.andExpect(jsonPath("$.data.members[?(@.nickname=='member')].burden").value(5000))
			.andExpect(jsonPath("$.data.members[?(@.nickname=='member')].net").value(-5000));
	}

	@Test
	@DisplayName("배정 없는 품목은 정산에서 제외된다")
	void itemWithNoAssigneesIsExcludedFromSettlement() throws Exception {
		User owner = createUser("owner@test.com", "owner");
		Room room = roomRepository.save(Room.create("room", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));

		Receipt receipt = receiptRepository.save(Receipt.createManual(room, "카페", null, null, null));
		itemRepository.save(Item.create(receipt, "아메리카노", 5000, 1)); // 배정 없음

		mockMvc.perform(get("/api/rooms/{roomId}/settlements", room.getId())
				.header(HttpHeaders.AUTHORIZATION, bearer(owner)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.members[?(@.nickname=='owner')].burden").value(0));
	}

	@Test
	@DisplayName("참여자가 1명인 품목은 전액 그 사람이 부담한다")
	void singleAssigneePaysFullAmount() throws Exception {
		User owner = createUser("owner@test.com", "owner");
		Room room = roomRepository.save(Room.create("room", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));

		Receipt receipt = receiptRepository.save(Receipt.createManual(room, "카페", null, null, null));
		Item item = itemRepository.save(Item.create(receipt, "아메리카노", 4500, 1));
		assignmentRepository.save(Assignment.create(item, owner));

		mockMvc.perform(get("/api/rooms/{roomId}/settlements", room.getId())
				.header(HttpHeaders.AUTHORIZATION, bearer(owner)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.members[?(@.nickname=='owner')].burden").value(4500));
	}

	@Test
	@DisplayName("나머지(라운딩)는 방장에게 귀속된다")
	void remainderIsAssignedToRoomOwner() throws Exception {
		User owner = createUser("owner@test.com", "owner");
		User a = createUser("a@test.com", "a");
		User b = createUser("b@test.com", "b");
		Room room = roomRepository.save(Room.create("room", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));
		roomMemberRepository.save(RoomMember.create(room, a));
		roomMemberRepository.save(RoomMember.create(room, b));

		// 10000원을 3명이 나누면 3333 * 3 = 9999, 나머지 1원은 방장에게
		Receipt receipt = receiptRepository.save(Receipt.createManual(room, "식사", null, null, null));
		Item item = itemRepository.save(Item.create(receipt, "찌개", 10000, 1));
		assignmentRepository.save(Assignment.create(item, owner));
		assignmentRepository.save(Assignment.create(item, a));
		assignmentRepository.save(Assignment.create(item, b));

		mockMvc.perform(get("/api/rooms/{roomId}/settlements", room.getId())
				.header(HttpHeaders.AUTHORIZATION, bearer(owner)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.members[?(@.nickname=='owner')].burden").value(3334))
			.andExpect(jsonPath("$.data.members[?(@.nickname=='a')].burden").value(3333))
			.andExpect(jsonPath("$.data.members[?(@.nickname=='b')].burden").value(3333));
	}

	@Test
	@DisplayName("영수증과 품목이 없으면 모든 멤버의 부담액이 0이다")
	void emptyRoomReturnsZeroBurdens() throws Exception {
		User owner = createUser("owner@test.com", "owner");
		Room room = roomRepository.save(Room.create("room", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));

		mockMvc.perform(get("/api/rooms/{roomId}/settlements", room.getId())
				.header(HttpHeaders.AUTHORIZATION, bearer(owner)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.members[?(@.nickname=='owner')].burden").value(0));
	}

	@Test
	@DisplayName("비멤버는 정산 조회 시 403을 받는다")
	void nonMemberGetsForbidden() throws Exception {
		User owner = createUser("owner@test.com", "owner");
		User outsider = createUser("out@test.com", "outsider");
		Room room = roomRepository.save(Room.create("room", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));

		mockMvc.perform(get("/api/rooms/{roomId}/settlements", room.getId())
				.header(HttpHeaders.AUTHORIZATION, bearer(outsider)))
			.andExpect(status().isForbidden());
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

}
