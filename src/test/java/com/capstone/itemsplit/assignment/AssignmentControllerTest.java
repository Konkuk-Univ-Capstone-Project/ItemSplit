package com.capstone.itemsplit.assignment;

import com.capstone.itemsplit.auth.JwtTokenProvider;
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
import java.util.Arrays;
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
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssignmentControllerTest {

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
	@DisplayName("PUT assignees 요청은 담당자를 교체하고 GET 요청은 최신 담당자를 반환한다")
	void 담당자_교체_후_조회하면_최신_담당자를_반환한다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		User memberOne = createUser("member1@example.com", "member1");
		User memberTwo = createUser("member2@example.com", "member2");
		User memberThree = createUser("member3@example.com", "member3");
		ItemFixture fixture = createItemFixture("Capstone Team", "Dinner", "Pasta", owner, memberOne, memberTwo, memberThree);

		mockMvc
			.perform(
				putAssignees(fixture.room().getId(), fixture.receipt().getId(), fixture.item().getId(), owner,
					"""
						{
						  "memberIds": [%d, %d]
						}
						""".formatted(memberOne.getId(), memberTwo.getId()))
			)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.roomId").value(fixture.room().getId()))
			.andExpect(jsonPath("$.data.receiptId").value(fixture.receipt().getId()))
			.andExpect(jsonPath("$.data.itemId").value(fixture.item().getId()))
			.andExpect(jsonPath("$.data.itemName").value("Pasta"))
			.andExpect(jsonPath("$.data.assignees[*].userId", containsInAnyOrder(
				memberOne.getId().intValue(),
				memberTwo.getId().intValue()
			)));

		mockMvc
			.perform(
				putAssignees(fixture.room().getId(), fixture.receipt().getId(), fixture.item().getId(), owner,
					"""
						{
						  "memberIds": [%d, %d]
						}
						""".formatted(memberTwo.getId(), memberThree.getId()))
			)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.assignees[*].userId", containsInAnyOrder(
				memberTwo.getId().intValue(),
				memberThree.getId().intValue()
			)));

		assertThat(assignmentRepository.findAllByItemId(fixture.item().getId())).hasSize(2);

		mockMvc
			.perform(
				getAssignees(fixture.room().getId(), fixture.receipt().getId(), fixture.item().getId(), owner)
			)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.assignees[*].userId", containsInAnyOrder(
				memberTwo.getId().intValue(),
				memberThree.getId().intValue()
			)));
	}

	@Test
	@DisplayName("PUT assignees 요청은 빈 배열로 모든 담당자를 해제할 수 있다")
	void 빈_배열로_담당자를_모두_해제할_수_있다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		User memberOne = createUser("member1@example.com", "member1");
		User memberTwo = createUser("member2@example.com", "member2");
		ItemFixture fixture = createItemFixture("Capstone Team", "Dinner", "Pasta", owner, memberOne, memberTwo);

		mockMvc.perform(
				putAssignees(fixture.room().getId(), fixture.receipt().getId(), fixture.item().getId(), owner,
					"""
						{
						  "memberIds": [%d, %d]
						}
						""".formatted(memberOne.getId(), memberTwo.getId()))
			)
			.andExpect(status().isOk());

		mockMvc
			.perform(
				putAssignees(fixture.room().getId(), fixture.receipt().getId(), fixture.item().getId(), owner,
					"""
						{
						  "memberIds": []
						}
						""")
			)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.assignees.length()").value(0));

		assertThat(assignmentRepository.findAllByItemId(fixture.item().getId())).isEmpty();
	}

	@Test
	@DisplayName("PUT assignees 요청은 수동 멤버를 담당자로 지정할 수 있다")
	void 수동_멤버를_담당자로_지정할_수_있다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		ItemFixture fixture = createItemFixture("Capstone Team", "Dinner", "Pasta", owner);
		RoomMember manualMember = roomMemberRepository.save(RoomMember.createManual(fixture.room(), "민지"));

		mockMvc
			.perform(
				putAssignees(fixture.room().getId(), fixture.receipt().getId(), fixture.item().getId(), owner,
					"""
						{
						  "memberIds": [%d]
						}
						""".formatted(manualMember.getId()))
			)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.assignees[0].memberId").value(manualMember.getId()))
			.andExpect(jsonPath("$.data.assignees[0].userId").doesNotExist())
			.andExpect(jsonPath("$.data.assignees[0].nickname").value("민지"))
			.andExpect(jsonPath("$.data.assignees[0].linked").value(false));

		assertThat(assignmentRepository.findAllByItemId(fixture.item().getId())).hasSize(1);
	}

	@Test
	@DisplayName("GET assignees 요청은 인증이 없으면 401을 반환한다")
	void 담당자_조회는_인증이_없으면_401을_반환한다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		ItemFixture fixture = createItemFixture("Capstone Team", "Dinner", "Pasta", owner);

		mockMvc
			.perform(get("/api/rooms/{roomId}/receipts/{receiptId}/items/{itemId}/assignees",
				fixture.room().getId(),
				fixture.receipt().getId(),
				fixture.item().getId()
			))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}

	@Test
	@DisplayName("PUT assignees 요청은 요청자가 방 멤버가 아니면 403을 반환한다")
	void 담당자_변경은_방_멤버가_아니면_403을_반환한다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		User stranger = createUser("stranger@example.com", "stranger");
		ItemFixture fixture = createItemFixture("Capstone Team", "Dinner", "Pasta", owner);

		mockMvc
			.perform(
				putAssignees(fixture.room().getId(), fixture.receipt().getId(), fixture.item().getId(), stranger,
					"""
						{
						  "memberIds": []
						}
						""")
			)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
			.andExpect(jsonPath("$.error.message").value("You are not a member of this room."));
	}

	@Test
	@DisplayName("PUT assignees 요청은 다른 방 사용자를 담당자로 지정하면 검증 오류를 반환한다")
	void 다른_방_사용자를_담당자로_지정하면_검증_오류를_반환한다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		User member = createUser("member@example.com", "member");
		User outsider = createUser("outsider@example.com", "outsider");
		ItemFixture fixture = createItemFixture("Capstone Team", "Dinner", "Pasta", owner, member);
		Room otherRoom = roomRepository.save(Room.create("Other Team", outsider));
		roomMemberRepository.save(RoomMember.create(otherRoom, outsider));

		mockMvc
			.perform(
				putAssignees(fixture.room().getId(), fixture.receipt().getId(), fixture.item().getId(), owner,
					"""
						{
						  "memberIds": [%d, %d]
						}
						""".formatted(member.getId(), outsider.getId()))
			)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").value("All assignees must be members of this room."));
	}

	@Test
	@DisplayName("PUT assignees 요청은 방이 없으면 404를 반환한다")
	void 존재하지_않는_방의_담당자_변경은_404를_반환한다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		User member = createUser("member@example.com", "member");
		ItemFixture fixture = createItemFixture("Capstone Team", "Dinner", "Pasta", owner, member);

		mockMvc
			.perform(
				putAssignees(99999L, fixture.receipt().getId(), fixture.item().getId(), owner,
					"""
						{
						  "memberIds": [%d]
						}
						""".formatted(member.getId()))
			)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.message").value("Room was not found."));
	}

	@Test
	@DisplayName("PUT assignees 요청은 영수증이 없으면 404를 반환한다")
	void 존재하지_않는_영수증의_담당자_변경은_404를_반환한다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		User member = createUser("member@example.com", "member");
		ItemFixture fixture = createItemFixture("Capstone Team", "Dinner", "Pasta", owner, member);

		mockMvc
			.perform(
				putAssignees(fixture.room().getId(), 99999L, fixture.item().getId(), owner,
					"""
						{
						  "memberIds": [%d]
						}
						""".formatted(member.getId()))
			)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.message").value("Receipt was not found."));
	}

	@Test
	@DisplayName("PUT assignees 요청은 품목이 없으면 404를 반환한다")
	void 존재하지_않는_품목의_담당자_변경은_404를_반환한다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		User member = createUser("member@example.com", "member");
		ItemFixture fixture = createItemFixture("Capstone Team", "Dinner", "Pasta", owner, member);

		mockMvc
			.perform(
				putAssignees(fixture.room().getId(), fixture.receipt().getId(), 99999L, owner,
					"""
						{
						  "memberIds": [%d]
						}
						""".formatted(member.getId()))
			)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.message").value("Item was not found."));
	}

	@Test
	@DisplayName("PUT assignees 요청은 존재하지 않는 멤버 ID가 있으면 404를 반환한다")
	void 존재하지_않는_멤버를_담당자로_지정하면_404를_반환한다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		ItemFixture fixture = createItemFixture("Capstone Team", "Dinner", "Pasta", owner);

		mockMvc
			.perform(
				putAssignees(fixture.room().getId(), fixture.receipt().getId(), fixture.item().getId(), owner,
					"""
						{
						  "memberIds": [99999]
						}
						""")
			)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.message").value("One or more members were not found."));
	}

	@Test
	@DisplayName("PUT assignees 요청은 영수증이 방에 속하지 않으면 404를 반환한다")
	void 방에_속하지_않은_영수증의_담당자_변경은_404를_반환한다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		User otherOwner = createUser("other@example.com", "other");
		ItemFixture fixture = createItemFixture("Capstone Team", "Dinner", "Pasta", owner);
		ItemFixture otherFixture = createItemFixture("Other Team", "Lunch", "Burger", otherOwner);

		mockMvc
			.perform(
				putAssignees(fixture.room().getId(), otherFixture.receipt().getId(), fixture.item().getId(), owner,
					"""
						{
						  "memberIds": []
						}
						""")
			)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.message").value("Receipt was not found in this room."));
	}

	@Test
	@DisplayName("PUT assignees 요청은 품목이 영수증에 속하지 않으면 404를 반환한다")
	void 영수증에_속하지_않은_품목의_담당자_변경은_404를_반환한다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		ItemFixture fixture = createItemFixture("Capstone Team", "Dinner", "Pasta", owner);
		Receipt otherReceipt = receiptRepository.save(Receipt.createManual(fixture.room(), "Late Night", null, null, null));
		Item otherItem = itemRepository.save(Item.create(otherReceipt, "Pizza", 22000, 1));

		mockMvc
			.perform(
				putAssignees(fixture.room().getId(), fixture.receipt().getId(), otherItem.getId(), owner,
					"""
						{
						  "memberIds": []
						}
						""")
			)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.message").value("Item was not found in this receipt."));
	}

	@Test
	@DisplayName("PUT assignees 요청은 memberIds가 null이면 검증 오류 상세를 반환한다")
	void memberIds가_null이면_검증_오류를_반환한다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		ItemFixture fixture = createItemFixture("Capstone Team", "Dinner", "Pasta", owner);

		mockMvc
			.perform(
				putAssignees(fixture.room().getId(), fixture.receipt().getId(), fixture.item().getId(), owner,
					"""
						{
						  "memberIds": null
						}
						""")
			)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").value("The request contains invalid values."))
			.andExpect(jsonPath("$.error.details[0].field").value("memberIds"));
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder putAssignees(
		Long roomId,
		Long receiptId,
		Long itemId,
		User user,
		String content
	) {
		return put("/api/rooms/{roomId}/receipts/{receiptId}/items/{itemId}/assignees", roomId, receiptId, itemId)
			.header(HttpHeaders.AUTHORIZATION, bearer(user))
			.contentType(MediaType.APPLICATION_JSON)
			.content(content);
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder getAssignees(
		Long roomId,
		Long receiptId,
		Long itemId,
		User user
	) {
		return get("/api/rooms/{roomId}/receipts/{receiptId}/items/{itemId}/assignees", roomId, receiptId, itemId)
			.header(HttpHeaders.AUTHORIZATION, bearer(user));
	}

	private ItemFixture createItemFixture(
		String roomName,
		String receiptName,
		String itemName,
		User owner,
		User... additionalMembers
	) {
		Room room = roomRepository.save(Room.create(roomName, owner));
		roomMemberRepository.save(RoomMember.create(room, owner));
		Arrays.stream(additionalMembers)
			.forEach(member -> roomMemberRepository.save(RoomMember.create(room, member)));

		Receipt receipt = receiptRepository.save(Receipt.createManual(room, receiptName, null, null, null));
		Item item = itemRepository.save(Item.create(receipt, itemName, 15000, 1));
		return new ItemFixture(room, receipt, item);
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

	private record ItemFixture(Room room, Receipt receipt, Item item) {
	}

}
