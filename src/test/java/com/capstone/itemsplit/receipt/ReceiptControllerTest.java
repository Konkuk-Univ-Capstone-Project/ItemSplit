package com.capstone.itemsplit.receipt;

import com.capstone.itemsplit.auth.JwtTokenProvider;
import com.capstone.itemsplit.assignment.AssignmentRepository;
import com.capstone.itemsplit.item.ItemRepository;
import com.capstone.itemsplit.room.Room;
import com.capstone.itemsplit.room.RoomRepository;
import com.capstone.itemsplit.room.RoomMember;
import com.capstone.itemsplit.room.RoomMemberRepository;
import com.capstone.itemsplit.user.User;
import com.capstone.itemsplit.user.UserRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.FileSystemUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReceiptControllerTest {

	private static final Path TEST_STORAGE_ROOT = Path.of("./build/test-storage");

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
	void setUp() throws Exception {
		assignmentRepository.deleteAll();
		itemRepository.deleteAll();
		receiptRepository.deleteAll();
		roomMemberRepository.deleteAll();
		roomRepository.deleteAll();
		userRepository.deleteAll();
		FileSystemUtils.deleteRecursively(TEST_STORAGE_ROOT);
		Files.createDirectories(TEST_STORAGE_ROOT);
	}

	@Test
	@DisplayName("POST /api/rooms/{roomId}/receipts/image 요청은 파일과 영수증 메타데이터를 저장한다")
	void 이미지_영수증_업로드는_파일과_메타데이터를_저장한다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		Room room = roomRepository.save(Room.create("Capstone Team", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));

		MockMultipartFile file = new MockMultipartFile(
			"file",
			"receipt-sample.png",
			"image/png",
			"fake-image-bytes".getBytes()
		);

		mockMvc
			.perform(
				multipart("/api/rooms/{roomId}/receipts/image", room.getId())
					.file(file)
					.param("name", "Dinner Receipt")
					.header(HttpHeaders.AUTHORIZATION, bearer(owner))
			)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.roomId").value(room.getId()))
			.andExpect(jsonPath("$.data.name").value("Dinner Receipt"))
			.andExpect(jsonPath("$.data.sourceType").value("IMAGE_UPLOAD"))
			.andExpect(jsonPath("$.data.originalFilename").value("receipt-sample.png"))
			.andExpect(jsonPath("$.data.storedPath").value(org.hamcrest.Matchers.startsWith("receipts/" + room.getId() + "/")));

		assertThat(receiptRepository.count()).isEqualTo(1);
		String storedPath = receiptRepository.findAll().get(0).getStoredPath();
		assertThat(Files.exists(TEST_STORAGE_ROOT.resolve(storedPath))).isTrue();
	}

	@Test
	@DisplayName("POST /api/rooms/{roomId}/receipts/image 요청은 방 멤버가 아니면 403을 반환한다")
	void 이미지_영수증_업로드는_방_멤버가_아니면_403을_반환한다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		User stranger = createUser("stranger@example.com", "stranger");
		Room room = roomRepository.save(Room.create("Capstone Team", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));

		MockMultipartFile file = new MockMultipartFile(
			"file",
			"receipt-sample.png",
			"image/png",
			"fake-image-bytes".getBytes()
		);

		mockMvc
			.perform(
				multipart("/api/rooms/{roomId}/receipts/image", room.getId())
					.file(file)
					.header(HttpHeaders.AUTHORIZATION, bearer(stranger))
			)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
			.andExpect(jsonPath("$.error.message").value("You are not a member of this room."));
	}

	@Test
	@DisplayName("POST /api/rooms/{roomId}/receipts/image 요청은 이미지가 아닌 파일을 거부한다")
	void 이미지_영수증_업로드는_이미지가_아닌_파일을_거부한다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		Room room = roomRepository.save(Room.create("Capstone Team", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));

		MockMultipartFile file = new MockMultipartFile(
			"file",
			"receipt.txt",
			"text/plain",
			"not-an-image".getBytes()
		);

		mockMvc
			.perform(
				multipart("/api/rooms/{roomId}/receipts/image", room.getId())
					.file(file)
					.header(HttpHeaders.AUTHORIZATION, bearer(owner))
			)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").value("Only image files can be uploaded."));

		assertThat(receiptRepository.count()).isZero();
	}

	@Test
	@DisplayName("POST /api/rooms/{roomId}/receipts/manual 요청은 수기 영수증과 품목을 저장한다")
	void 수기_영수증_생성은_영수증과_품목을_저장한다() throws Exception {
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
						  "name": "Dinner Manual Entry",
						  "items": [
						    { "name": "Pasta", "price": 15000, "quantity": 1 },
						    { "name": "Pizza", "price": 22000, "quantity": 2 }
						  ]
						}
						""")
			)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.roomId").value(room.getId()))
			.andExpect(jsonPath("$.data.name").value("Dinner Manual Entry"))
			.andExpect(jsonPath("$.data.sourceType").value("MANUAL"))
			.andExpect(jsonPath("$.data.items.length()").value(2))
			.andExpect(jsonPath("$.data.items[0].name").value("Pasta"))
			.andExpect(jsonPath("$.data.items[1].name").value("Pizza"));

		assertThat(receiptRepository.count()).isEqualTo(1);
		assertThat(itemRepository.count()).isEqualTo(2);
		assertThat(receiptRepository.findAll().get(0).getStoredPath()).isNull();
		assertThat(receiptRepository.findAll().get(0).getSourceType().name()).isEqualTo("MANUAL");
	}

	@Test
	@DisplayName("POST /api/rooms/{roomId}/receipts/manual 요청은 방 멤버가 아니면 403을 반환한다")
	void 수기_영수증_생성은_방_멤버가_아니면_403을_반환한다() throws Exception {
		User owner = createUser("owner@example.com", "owner");
		User stranger = createUser("stranger@example.com", "stranger");
		Room room = roomRepository.save(Room.create("Capstone Team", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));

		mockMvc
			.perform(
				post("/api/rooms/{roomId}/receipts/manual", room.getId())
					.header(HttpHeaders.AUTHORIZATION, bearer(stranger))
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{
						  "name": "Dinner Manual Entry",
						  "items": [
						    { "name": "Pasta", "price": 15000, "quantity": 1 }
						  ]
						}
						""")
			)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
			.andExpect(jsonPath("$.error.message").value("You are not a member of this room."));
	}

	@Test
	@DisplayName("POST /api/rooms/{roomId}/receipts/manual 요청은 빈 품목 목록을 검증한다")
	void 수기_영수증_생성은_빈_품목_목록을_검증한다() throws Exception {
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
						  "name": "Dinner Manual Entry",
						  "items": []
						}
						""")
			)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.details[0].field").value("items"));
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
