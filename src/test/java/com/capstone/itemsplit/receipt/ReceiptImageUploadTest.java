package com.capstone.itemsplit.receipt;

import com.capstone.itemsplit.assignment.AssignmentRepository;
import com.capstone.itemsplit.auth.JwtTokenProvider;
import com.capstone.itemsplit.item.ItemRepository;
import com.capstone.itemsplit.room.Room;
import com.capstone.itemsplit.room.RoomMember;
import com.capstone.itemsplit.room.RoomMemberRepository;
import com.capstone.itemsplit.room.RoomRepository;
import com.capstone.itemsplit.user.User;
import com.capstone.itemsplit.user.UserRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.util.FileSystemUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReceiptImageUploadTest {

	private static final Path TEST_STORAGE_ROOT = Path.of("./build/test-storage");
	private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
	private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};
	private static final byte[] WEBP = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};

	@Autowired private MockMvc mockMvc;
	@Autowired private JwtTokenProvider jwtTokenProvider;
	@Autowired private PasswordEncoder passwordEncoder;
	@Autowired private UserRepository userRepository;
	@Autowired private RoomRepository roomRepository;
	@Autowired private RoomMemberRepository roomMemberRepository;
	@Autowired private AssignmentRepository assignmentRepository;
	@Autowired private ItemRepository itemRepository;
	@Autowired private ReceiptRepository receiptRepository;
	@Autowired private ReceiptImageRepository receiptImageRepository;

	private User uploader;
	private User otherMember;
	private Room room;
	private RoomMember uploaderMember;

	@BeforeEach
	void setUp() throws Exception {
		clearDatabase();
		FileSystemUtils.deleteRecursively(TEST_STORAGE_ROOT);
		Files.createDirectories(TEST_STORAGE_ROOT);

		uploader = createUser("uploader@example.com", "uploader");
		otherMember = createUser("other@example.com", "other");
		room = roomRepository.save(Room.create("Capstone Team", uploader));
		uploaderMember = roomMemberRepository.save(RoomMember.create(room, uploader));
		roomMemberRepository.save(RoomMember.create(room, otherMember));
	}

	// Other test classes share this database and delete receipts without knowing about images.
	@AfterEach
	void tearDown() {
		clearDatabase();
	}

	@Test
	@DisplayName("첫 이미지 업로드는 올린 사람을 생성자와 기본 결제자로 기록한다")
	void 첫_이미지_업로드는_올린_사람을_생성자와_기본_결제자로_기록한다() throws Exception {
		upload(uploader, image("receipt.png", PNG))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.sourceType").value("IMAGE_UPLOAD"))
			.andExpect(jsonPath("$.data.createdByUserId").value(uploader.getId()))
			.andExpect(jsonPath("$.data.payerMemberId").value(uploaderMember.getId()))
			.andExpect(jsonPath("$.data.image.order").value(1))
			.andExpect(jsonPath("$.data.image.contentType").value("image/png"))
			.andExpect(jsonPath("$.data.storedPath").doesNotExist())
			.andExpect(jsonPath("$.data.image.storedPath").doesNotExist());

		Receipt receipt = receiptRepository.findByIdAndRoomId(onlyReceiptId(), room.getId()).orElseThrow();
		assertThat(receipt.getPayer().getId()).isEqualTo(uploaderMember.getId());
		assertThat(receiptImageRepository.findAllByReceiptId(receipt.getId())).hasSize(1);
	}

	@Test
	@DisplayName("저장 확장자와 형식은 파일 이름이나 헤더가 아니라 실제 내용으로 정한다")
	void 저장_확장자와_형식은_실제_내용으로_정한다() throws Exception {
		upload(uploader, new MockMultipartFile("file", "photo.png", "image/png", JPEG))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.image.contentType").value("image/jpeg"))
			.andExpect(jsonPath("$.data.image.originalFilename").value("photo.png"));

		ReceiptImage image = receiptImageRepository.findAll().get(0);
		assertThat(image.getStoredPath()).startsWith("receipts/" + room.getId() + "/").endsWith(".jpg");
		assertThat(Files.readAllBytes(TEST_STORAGE_ROOT.resolve(image.getStoredPath()))).isEqualTo(JPEG);
	}

	@Test
	@DisplayName("확장자와 헤더만 이미지인 파일은 거부한다")
	void 확장자와_헤더만_이미지인_파일은_거부한다() throws Exception {
		upload(uploader, new MockMultipartFile("file", "receipt.png", "image/png", "not-an-image".getBytes()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		assertThat(receiptRepository.count()).isZero();
		assertThat(storedFiles()).isEmpty();
	}

	@Test
	@DisplayName("JPEG와 PNG가 아닌 이미지는 거부한다")
	void JPEG와_PNG가_아닌_이미지는_거부한다() throws Exception {
		upload(uploader, new MockMultipartFile("file", "receipt.webp", "image/webp", WEBP))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.message").value("JPEG 또는 PNG 이미지만 올릴 수 있습니다."));
	}

	@Test
	@DisplayName("10MB를 넘는 이미지는 413으로 거부한다")
	void 크기를_넘는_이미지는_413으로_거부한다() throws Exception {
		byte[] tooLarge = Arrays.copyOf(PNG, 10 * 1024 * 1024 + 1);

		upload(uploader, image("large.png", tooLarge))
			.andExpect(status().isPayloadTooLarge())
			.andExpect(jsonPath("$.error.code").value("PAYLOAD_TOO_LARGE"));

		assertThat(receiptRepository.count()).isZero();
		assertThat(storedFiles()).isEmpty();
	}

	@Test
	@DisplayName("올린 사람은 이미지를 순서대로 세 장까지 추가할 수 있고 네 번째는 거부된다")
	void 이미지는_세_장까지_추가할_수_있다() throws Exception {
		Long receiptId = uploadedReceiptId();

		addImage(uploader, receiptId, image("second.jpg", JPEG))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.receiptId").value(receiptId))
			.andExpect(jsonPath("$.data.order").value(2));
		addImage(uploader, receiptId, image("third.png", PNG))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.order").value(3));
		addImage(uploader, receiptId, image("fourth.png", PNG))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		assertThat(receiptImageRepository.findAllByReceiptId(receiptId))
			.extracting(ReceiptImage::getSortOrder).containsExactly(1, 2, 3);
		assertThat(storedFiles()).hasSize(3);
	}

	@Test
	@DisplayName("올린 사람이 아닌 방 멤버는 이미지를 추가할 수 없다")
	void 올린_사람이_아닌_멤버는_이미지를_추가할_수_없다() throws Exception {
		Long receiptId = uploadedReceiptId();

		addImage(otherMember, receiptId, image("second.png", PNG))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

		assertThat(receiptImageRepository.countByReceiptId(receiptId)).isEqualTo(1);
		assertThat(storedFiles()).hasSize(1);
	}

	@Test
	@DisplayName("올린 사람 기록이 없는 기존 이미지 영수증에는 방 멤버 누구나 이미지를 추가할 수 있다")
	void 올린_사람_기록이_없는_영수증에는_누구나_추가할_수_있다() throws Exception {
		Receipt legacy = receiptRepository.save(Receipt.createImageUpload(room, "Legacy", null, null));

		addImage(otherMember, legacy.getId(), image("first.png", PNG))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.order").value(1));
	}

	@Test
	@DisplayName("수기로 만든 영수증에는 이미지를 추가할 수 없다")
	void 수기_영수증에는_이미지를_추가할_수_없다() throws Exception {
		Receipt manual = receiptRepository.save(Receipt.createManual(room, "Manual", null, null, null));

		addImage(uploader, manual.getId(), image("receipt.png", PNG))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		assertThat(receiptImageRepository.count()).isZero();
		assertThat(storedFiles()).isEmpty();
	}

	@Test
	@DisplayName("다른 방의 영수증에는 이미지를 추가할 수 없다")
	void 다른_방의_영수증에는_이미지를_추가할_수_없다() throws Exception {
		Room otherRoom = roomRepository.save(Room.create("Other Room", uploader));
		roomMemberRepository.save(RoomMember.create(otherRoom, uploader));
		Receipt elsewhere = receiptRepository.save(Receipt.createImageUpload(otherRoom, "Elsewhere", uploader, null));

		mockMvc.perform(multipart("/api/rooms/{roomId}/receipts/{receiptId}/images", room.getId(), elsewhere.getId())
				.file(image("receipt.png", PNG))
				.header(HttpHeaders.AUTHORIZATION, bearer(uploader)))
			.andExpect(status().isNotFound());
	}

	private ResultActions upload(User user, MockMultipartFile file) throws Exception {
		return mockMvc.perform(multipart("/api/rooms/{roomId}/receipts/image", room.getId())
			.file(file)
			.param("name", "Dinner")
			.header(HttpHeaders.AUTHORIZATION, bearer(user)));
	}

	private ResultActions addImage(User user, Long receiptId, MockMultipartFile file) throws Exception {
		return mockMvc.perform(multipart("/api/rooms/{roomId}/receipts/{receiptId}/images", room.getId(), receiptId)
			.file(file)
			.header(HttpHeaders.AUTHORIZATION, bearer(user)));
	}

	private Long uploadedReceiptId() throws Exception {
		upload(uploader, image("first.png", PNG)).andExpect(status().isCreated());
		return onlyReceiptId();
	}

	private Long onlyReceiptId() {
		List<Receipt> receipts = receiptRepository.findAll();
		assertThat(receipts).hasSize(1);
		return receipts.get(0).getId();
	}

	private MockMultipartFile image(String filename, byte[] content) {
		return new MockMultipartFile("file", filename, "application/octet-stream", content);
	}

	private List<Path> storedFiles() throws Exception {
		Path directory = TEST_STORAGE_ROOT.resolve("receipts/" + room.getId());
		if (!Files.isDirectory(directory)) {
			return List.of();
		}
		try (var files = Files.list(directory)) {
			return files.toList();
		}
	}

	private void clearDatabase() {
		receiptImageRepository.deleteAll();
		assignmentRepository.deleteAll();
		itemRepository.deleteAll();
		receiptRepository.deleteAll();
		roomMemberRepository.deleteAll();
		roomRepository.deleteAll();
		userRepository.deleteAll();
	}

	private User createUser(String email, String nickname) {
		return userRepository.save(User.create(email, passwordEncoder.encode("password123"), nickname));
	}

	private String bearer(User user) {
		return "Bearer " + jwtTokenProvider.createAccessToken(user).accessToken();
	}

}
