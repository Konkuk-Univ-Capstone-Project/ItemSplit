package com.capstone.itemsplit.room;

import com.capstone.itemsplit.assignment.AssignmentRepository;
import com.capstone.itemsplit.auth.JwtTokenProvider;
import com.capstone.itemsplit.item.ItemRepository;
import com.capstone.itemsplit.receipt.ReceiptRepository;
import com.capstone.itemsplit.user.User;
import com.capstone.itemsplit.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoomListControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

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
	@DisplayName("GET /api/rooms 요청은 로그인한 계정이 연결된 방만 최신 방부터 반환한다")
	void 내가_연결된_방만_최신순으로_반환한다() throws Exception {
		User me = createUser("me@example.com", "me");
		User other = createUser("other@example.com", "other");

		Room owned = roomRepository.save(Room.create("내가 만든 방", me));
		RoomMember ownedMember = roomMemberRepository.save(RoomMember.create(owned, me));

		Room joined = roomRepository.save(Room.create("참여한 방", other));
		roomMemberRepository.save(RoomMember.create(joined, other));
		RoomMember claimed = roomMemberRepository.save(RoomMember.createManual(joined, "me"));
		claimed.claim(me, "me");
		roomMemberRepository.save(claimed);

		// A manual member with my nickname is not my room until it is linked to my account.
		Room notMine = roomRepository.save(Room.create("남의 방", other));
		roomMemberRepository.save(RoomMember.create(notMine, other));
		roomMemberRepository.save(RoomMember.createManual(notMine, "me"));

		mockMvc.perform(get("/api/rooms").header(HttpHeaders.AUTHORIZATION, bearer(me)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.rooms", hasSize(2)))
			.andExpect(jsonPath("$.data.rooms[0].roomId").value(joined.getId()))
			.andExpect(jsonPath("$.data.rooms[0].roomName").value("참여한 방"))
			.andExpect(jsonPath("$.data.rooms[0].myMemberId").value(claimed.getId()))
			.andExpect(jsonPath("$.data.rooms[0].owner").value(false))
			.andExpect(jsonPath("$.data.rooms[0].createdAt", notNullValue()))
			.andExpect(jsonPath("$.data.rooms[1].roomId").value(owned.getId()))
			.andExpect(jsonPath("$.data.rooms[1].myMemberId").value(ownedMember.getId()))
			.andExpect(jsonPath("$.data.rooms[1].owner").value(true));
	}

	@Test
	@DisplayName("GET /api/rooms 요청은 참여한 방이 없으면 빈 목록을 반환한다")
	void 참여한_방이_없으면_빈_목록이다() throws Exception {
		User me = createUser("me@example.com", "me");

		mockMvc.perform(get("/api/rooms").header(HttpHeaders.AUTHORIZATION, bearer(me)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.rooms", hasSize(0)));
	}

	@Test
	@DisplayName("GET /api/rooms 요청은 인증이 없으면 401을 반환한다")
	void 인증이_없으면_401이다() throws Exception {
		mockMvc.perform(get("/api/rooms"))
			.andExpect(status().isUnauthorized());
	}

	private User createUser(String email, String nickname) {
		return userRepository.save(User.create(email, "encoded", nickname));
	}

	private String bearer(User user) {
		return "Bearer " + jwtTokenProvider.createAccessToken(user).accessToken();
	}

}
