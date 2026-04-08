package com.capstone.itemsplit.receipt;

import com.capstone.itemsplit.auth.JwtTokenProvider;
import com.capstone.itemsplit.domain.receipt.ReceiptRepository;
import com.capstone.itemsplit.domain.room.Room;
import com.capstone.itemsplit.domain.room.RoomRepository;
import com.capstone.itemsplit.domain.roommember.RoomMember;
import com.capstone.itemsplit.domain.roommember.RoomMemberRepository;
import com.capstone.itemsplit.domain.user.User;
import com.capstone.itemsplit.domain.user.UserRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.FileSystemUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
	private ReceiptRepository receiptRepository;

	@BeforeEach
	void setUp() throws Exception {
		receiptRepository.deleteAll();
		roomMemberRepository.deleteAll();
		roomRepository.deleteAll();
		userRepository.deleteAll();
		FileSystemUtils.deleteRecursively(TEST_STORAGE_ROOT);
		Files.createDirectories(TEST_STORAGE_ROOT);
	}

	@Test
	@DisplayName("POST /api/rooms/{roomId}/receipts/image stores the file and saves receipt metadata for a room member")
	void uploadReceiptImageStoresFileAndMetadata() throws Exception {
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
			.andExpect(jsonPath("$.data.originalFilename").value("receipt-sample.png"))
			.andExpect(jsonPath("$.data.storedPath").value(org.hamcrest.Matchers.startsWith("receipts/" + room.getId() + "/")));

		assertThat(receiptRepository.count()).isEqualTo(1);
		String storedPath = receiptRepository.findAll().get(0).getStoredPath();
		assertThat(Files.exists(TEST_STORAGE_ROOT.resolve(storedPath))).isTrue();
	}

	@Test
	@DisplayName("POST /api/rooms/{roomId}/receipts/image returns forbidden for non-members")
	void uploadReceiptImageRequiresRoomMembership() throws Exception {
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
	@DisplayName("POST /api/rooms/{roomId}/receipts/image rejects non-image files")
	void uploadReceiptImageRejectsNonImageFiles() throws Exception {
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
