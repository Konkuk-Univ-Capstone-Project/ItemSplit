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
	@DisplayName("GET /api/rooms/{roomId}/receipts returns all receipts for a room member")
	void getReceiptsReturnsListForMember() throws Exception {
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
	@DisplayName("GET /api/rooms/{roomId}/receipts/{receiptId} returns receipt detail with items")
	void getReceiptReturnsDetailWithItems() throws Exception {
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
	@DisplayName("GET /api/rooms/{roomId}/receipts/{receiptId} returns warning when declaredTotal does not match item sum")
	void getReceiptReturnsWarningOnTotalMismatch() throws Exception {
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
	@DisplayName("PUT /api/rooms/{roomId}/receipts/{receiptId} updates receipt name and payer")
	void updateReceiptUpdatesFields() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		Room room = roomRepository.save(Room.create("Capstone Team", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));
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
						""".formatted(owner.getId()))
			)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.name").value("스타벅스"))
			.andExpect(jsonPath("$.data.payerId").value(owner.getId()))
			.andExpect(jsonPath("$.data.declaredTotal").value(9000));
	}

	@Test
	@DisplayName("DELETE /api/rooms/{roomId}/receipts/{receiptId} deletes receipt along with its items and assignments")
	void deleteReceiptCascadesItemsAndAssignments() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		Room room = roomRepository.save(Room.create("Capstone Team", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));
		Receipt receipt = receiptRepository.save(Receipt.createManual(room, "카페", null, null, null));
		Item item = itemRepository.save(Item.create(receipt, "아메리카노", 4500, 1));
		assignmentRepository.save(Assignment.create(item, owner));

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
	@DisplayName("POST /api/rooms/{roomId}/receipts/manual saves payer and declaredTotal when provided")
	void createManualReceiptSavesPayerAndDeclaredTotal() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		Room room = roomRepository.save(Room.create("Capstone Team", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));

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
						""".formatted(owner.getId()))
			)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.payerId").value(owner.getId()))
			.andExpect(jsonPath("$.data.payerNickname").value("owner"))
			.andExpect(jsonPath("$.data.declaredTotal").value(9000));
	}

	@Test
	@DisplayName("DELETE /api/rooms/{roomId}/receipts/{receiptId} returns forbidden for non-members")
	void deleteReceiptRequiresRoomMembership() throws Exception {
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
