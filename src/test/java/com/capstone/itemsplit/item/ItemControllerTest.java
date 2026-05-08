package com.capstone.itemsplit.item;

import com.capstone.itemsplit.auth.JwtTokenProvider;
import com.capstone.itemsplit.assignment.Assignment;
import com.capstone.itemsplit.assignment.AssignmentRepository;
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
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ItemControllerTest {

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
	@DisplayName("POST /api/rooms/{roomId}/receipts/{receiptId}/items 요청은 영수증에 새 품목을 추가한다")
	void 영수증에_새_품목을_추가한다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		Room room = roomRepository.save(Room.create("Capstone Team", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));
		Receipt receipt = receiptRepository.save(Receipt.createManual(room, "카페", null, null, null));

		mockMvc
			.perform(
				post("/api/rooms/{roomId}/receipts/{receiptId}/items", room.getId(), receipt.getId())
					.header(HttpHeaders.AUTHORIZATION, bearer(owner))
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{ "name": "아메리카노", "price": 4500, "quantity": 2 }
						""")
			)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.name").value("아메리카노"))
			.andExpect(jsonPath("$.data.price").value(4500))
			.andExpect(jsonPath("$.data.quantity").value(2))
			.andExpect(jsonPath("$.data.receiptId").value(receipt.getId()));

		assertThat(itemRepository.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("PUT /api/rooms/{roomId}/receipts/{receiptId}/items/{itemId} 요청은 품목 정보를 수정한다")
	void 품목_정보를_수정한다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		Room room = roomRepository.save(Room.create("Capstone Team", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));
		Receipt receipt = receiptRepository.save(Receipt.createManual(room, "카페", null, null, null));
		Item item = itemRepository.save(Item.create(receipt, "아메리카노", 4500, 1));

		mockMvc
			.perform(
				put("/api/rooms/{roomId}/receipts/{receiptId}/items/{itemId}",
					room.getId(), receipt.getId(), item.getId())
					.header(HttpHeaders.AUTHORIZATION, bearer(owner))
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{ "name": "카페라떼", "price": 5500, "quantity": 3 }
						""")
			)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.name").value("카페라떼"))
			.andExpect(jsonPath("$.data.price").value(5500))
			.andExpect(jsonPath("$.data.quantity").value(3));

		Item updated = itemRepository.findById(item.getId()).orElseThrow();
		assertThat(updated.getName()).isEqualTo("카페라떼");
		assertThat(updated.getPrice()).isEqualTo(5500);
	}

	@Test
	@DisplayName("DELETE /api/rooms/{roomId}/receipts/{receiptId}/items/{itemId} 요청은 품목과 배정을 함께 삭제한다")
	void 품목과_배정을_함께_삭제한다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		Room room = roomRepository.save(Room.create("Capstone Team", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));
		Receipt receipt = receiptRepository.save(Receipt.createManual(room, "카페", null, null, null));
		Item item = itemRepository.save(Item.create(receipt, "아메리카노", 4500, 1));
		assignmentRepository.save(Assignment.create(item, owner));

		mockMvc
			.perform(
				delete("/api/rooms/{roomId}/receipts/{receiptId}/items/{itemId}",
					room.getId(), receipt.getId(), item.getId())
					.header(HttpHeaders.AUTHORIZATION, bearer(owner))
			)
			.andExpect(status().isNoContent());

		assertThat(itemRepository.count()).isZero();
		assertThat(assignmentRepository.count()).isZero();
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
