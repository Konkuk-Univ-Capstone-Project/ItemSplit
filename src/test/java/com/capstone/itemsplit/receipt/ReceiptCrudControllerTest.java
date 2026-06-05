package com.capstone.itemsplit.receipt;

import com.capstone.itemsplit.auth.JwtTokenProvider;
import com.capstone.itemsplit.assignment.Assignment;
import com.capstone.itemsplit.assignment.AssignmentRepository;
import com.capstone.itemsplit.item.Item;
import com.capstone.itemsplit.item.ItemRepository;
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
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

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
class ReceiptCrudControllerTest {

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
	private AssignmentRepository assignmentRepository;

	@Autowired
	private ItemRepository itemRepository;

	@Autowired
	private ReceiptRepository receiptRepository;

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
	@DisplayName("GET /api/rooms/{roomId}/receipts 요청은 방 멤버의 모든 영수증을 반환한다")
	void 영수증_목록_조회는_방_멤버의_영수증을_반환한다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		Room room = roomRepository.save(Room.create("Capstone Team", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));
		receiptRepository.save(Receipt.createManual(room, "카페", null, null, null));
		receiptRepository.save(Receipt.createManual(room, "저녁 식사", null, null, null));

		mockMvc
			.perform(
				get("/api/rooms/{roomId}/receipts", room.getId())
					.header(HttpHeaders.AUTHORIZATION, bearer(owner))
			)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.length()").value(2));
	}

	@Test
	@DisplayName("GET /api/rooms/{roomId}/receipts/{receiptId} 요청은 품목을 포함한 영수증 상세를 반환한다")
	void 영수증_상세_조회는_품목을_함께_반환한다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		Room room = roomRepository.save(Room.create("Capstone Team", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));
		Receipt receipt = receiptRepository.save(Receipt.createManual(room, "카페", null, null, null));
		itemRepository.save(Item.create(receipt, "아메리카노", 4500, 2));

		mockMvc
			.perform(
				get("/api/rooms/{roomId}/receipts/{receiptId}", room.getId(), receipt.getId())
					.header(HttpHeaders.AUTHORIZATION, bearer(owner))
			)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.name").value("카페"))
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].name").value("아메리카노"))
			.andExpect(jsonPath("$.data.warning").doesNotExist());
	}

	@Test
	@DisplayName("GET /api/rooms/{roomId}/receipts/{receiptId} 요청은 선언 합계와 품목 합계가 다르면 경고를 반환한다")
	void 영수증_상세_조회는_선언_합계와_품목_합계가_다르면_경고를_반환한다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		Room room = roomRepository.save(Room.create("Capstone Team", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));
		Receipt receipt = receiptRepository.save(Receipt.createManual(room, "카페", null, 99999, null));
		itemRepository.save(Item.create(receipt, "아메리카노", 4500, 2));

		mockMvc
			.perform(
				get("/api/rooms/{roomId}/receipts/{receiptId}", room.getId(), receipt.getId())
					.header(HttpHeaders.AUTHORIZATION, bearer(owner))
			)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.warning").value(org.hamcrest.Matchers.containsString("검토 필요")));
	}

	@Test
	@DisplayName("PUT /api/rooms/{roomId}/receipts/{receiptId} 요청은 영수증 이름과 결제자를 수정한다")
	void 영수증_수정은_이름과_결제자를_변경한다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		Room room = roomRepository.save(Room.create("Capstone Team", owner));
		RoomMember ownerMember = roomMemberRepository.save(RoomMember.create(room, owner));
		Receipt receipt = receiptRepository.save(Receipt.createManual(room, "카페", null, null, null));

		mockMvc
			.perform(
				put("/api/rooms/{roomId}/receipts/{receiptId}", room.getId(), receipt.getId())
					.header(HttpHeaders.AUTHORIZATION, bearer(owner))
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{
						  "name": "스타벅스",
						  "payerId": %d,
						  "declaredTotal": 9000
						}
						""".formatted(ownerMember.getId()))
			)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.name").value("스타벅스"))
			.andExpect(jsonPath("$.data.payerId").value(ownerMember.getId()))
			.andExpect(jsonPath("$.data.declaredTotal").value(9000));
	}

	@Test
	@DisplayName("DELETE /api/rooms/{roomId}/receipts/{receiptId} 요청은 영수증과 품목 및 배정을 함께 삭제한다")
	void 영수증_삭제는_품목과_배정을_함께_삭제한다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		Room room = roomRepository.save(Room.create("Capstone Team", owner));
		RoomMember ownerMember = roomMemberRepository.save(RoomMember.create(room, owner));
		Receipt receipt = receiptRepository.save(Receipt.createManual(room, "카페", null, null, null));
		Item item = itemRepository.save(Item.create(receipt, "아메리카노", 4500, 1));
		assignmentRepository.save(Assignment.create(item, ownerMember));

		mockMvc
			.perform(
				delete("/api/rooms/{roomId}/receipts/{receiptId}", room.getId(), receipt.getId())
					.header(HttpHeaders.AUTHORIZATION, bearer(owner))
			)
			.andExpect(status().isNoContent());

		assertThat(receiptRepository.count()).isZero();
		assertThat(itemRepository.count()).isZero();
		assertThat(assignmentRepository.count()).isZero();
	}

	@Test
	@DisplayName("POST /api/rooms/{roomId}/receipts/manual 요청은 결제자와 선언 합계가 있으면 함께 저장한다")
	void 수기_영수증_생성은_결제자와_선언_합계를_저장한다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		Room room = roomRepository.save(Room.create("Capstone Team", owner));
		RoomMember ownerMember = roomMemberRepository.save(RoomMember.create(room, owner));

		mockMvc
			.perform(
				post("/api/rooms/{roomId}/receipts/manual", room.getId())
					.header(HttpHeaders.AUTHORIZATION, bearer(owner))
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{
						  "name": "카페",
						  "payerId": %d,
						  "declaredTotal": 9000,
						  "items": [
						    { "name": "아메리카노", "price": 4500, "quantity": 2 }
						  ]
						}
						""".formatted(ownerMember.getId()))
			)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.payerId").value(ownerMember.getId()))
			.andExpect(jsonPath("$.data.payerNickname").value("owner"))
			.andExpect(jsonPath("$.data.declaredTotal").value(9000));
	}

	@Test
	@DisplayName("POST /api/rooms/{roomId}/receipts/manual 요청은 수동 멤버도 결제자로 저장한다")
	void 수기_영수증_생성은_수동_멤버도_결제자로_저장한다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		Room room = roomRepository.save(Room.create("Capstone Team", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));
		RoomMember manualMember = roomMemberRepository.save(RoomMember.createManual(room, "민지"));

		mockMvc
			.perform(
				post("/api/rooms/{roomId}/receipts/manual", room.getId())
					.header(HttpHeaders.AUTHORIZATION, bearer(owner))
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{
						  "name": "점심",
						  "payerId": %d,
						  "items": [
						    { "name": "파스타", "price": 14000, "quantity": 1 }
						  ]
						}
						""".formatted(manualMember.getId()))
			)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.payerId").value(manualMember.getId()))
			.andExpect(jsonPath("$.data.payerNickname").value("민지"));
	}

	@Test
	@DisplayName("DELETE /api/rooms/{roomId}/receipts/{receiptId} 요청은 방 멤버가 아니면 403을 반환한다")
	void 영수증_삭제는_방_멤버가_아니면_403을_반환한다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		User stranger = createUser("stranger@example.com", "stranger");
		Room room = roomRepository.save(Room.create("Capstone Team", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));
		Receipt receipt = receiptRepository.save(Receipt.createManual(room, "카페", null, null, null));

		mockMvc
			.perform(
				delete("/api/rooms/{roomId}/receipts/{receiptId}", room.getId(), receipt.getId())
					.header(HttpHeaders.AUTHORIZATION, bearer(stranger))
			)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

		assertThat(receiptRepository.count()).isEqualTo(1);
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
