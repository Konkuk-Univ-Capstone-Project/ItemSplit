package com.capstone.itemsplit.settlement;

import com.capstone.itemsplit.auth.JwtTokenProvider;
import com.capstone.itemsplit.assignment.Assignment;
import com.capstone.itemsplit.assignment.AssignmentRepository;
import com.capstone.itemsplit.item.Item;
import com.capstone.itemsplit.item.ItemRepository;
import com.capstone.itemsplit.receipt.Receipt;
import com.capstone.itemsplit.receipt.ReceiptRepository;
import com.capstone.itemsplit.room.Room;
import com.capstone.itemsplit.room.RoomRepository;
import com.capstone.itemsplit.room.RoomMember;
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
	void 품목별_참여자가_다르면_각자_부담액이_다르게_계산된다() throws Exception {
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
	void 결제자가_있으면_paid와_net이_계산된다() throws Exception {
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
	void 배정_없는_품목은_정산에서_제외된다() throws Exception {
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
	void 참여자가_한_명인_품목은_전액_그_사람이_부담한다() throws Exception {
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
	@DisplayName("나머지(라운딩)는 참여자 중 1명에게 귀속되며 합계는 정확히 맞는다")
	void 라운딩_나머지는_참여자_중_한_명에게_귀속되고_합계는_맞는다() throws Exception {
		User owner = createUser("owner@test.com", "owner");
		User a = createUser("a@test.com", "a");
		User b = createUser("b@test.com", "b");
		Room room = roomRepository.save(Room.create("room", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));
		roomMemberRepository.save(RoomMember.create(room, a));
		roomMemberRepository.save(RoomMember.create(room, b));

		// 10000원을 3명이 나누면 3333 * 3 = 9999, 나머지 1원은 seeded random으로 참여자 중 1명에게
		Receipt receipt = receiptRepository.save(Receipt.createManual(room, "식사", null, null, null));
		Item item = itemRepository.save(Item.create(receipt, "찌개", 10000, 1));
		assignmentRepository.save(Assignment.create(item, owner));
		assignmentRepository.save(Assignment.create(item, a));
		assignmentRepository.save(Assignment.create(item, b));

		mockMvc.perform(get("/api/rooms/{roomId}/settlements", room.getId())
				.header(HttpHeaders.AUTHORIZATION, bearer(owner)))
			.andExpect(status().isOk())
			// 세 명의 부담 합계는 반드시 10000
			.andExpect(jsonPath("$.data.members[?(@.nickname=='owner')].burden").isArray())
			.andExpect(jsonPath("$.data.members[?(@.nickname=='a')].burden").isArray())
			.andExpect(jsonPath("$.data.members[?(@.nickname=='b')].burden").isArray())
			// 각자 3333 또는 3334 (합계 검증은 아래 custom matcher 대신 최소 합으로 대체)
			.andExpect(result -> {
				com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
				var body = mapper.readTree(result.getResponse().getContentAsString());
				long total = 0;
				for (var m : body.at("/data/members")) {
					long burden = m.get("burden").asLong();
					assert burden == 3333 || burden == 3334 : "burden should be 3333 or 3334, was " + burden;
					total += burden;
				}
				assert total == 10000 : "total burden should be 10000, was " + total;
			});
	}

	@Test
	@DisplayName("영수증과 품목이 없으면 모든 멤버의 부담액이 0이다")
	void 영수증과_품목이_없으면_모든_멤버의_부담액이_0이다() throws Exception {
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
	void 비멤버는_정산_조회시_403을_받는다() throws Exception {
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
