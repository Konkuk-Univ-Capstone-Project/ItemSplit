package com.capstone.itemsplit.ocr;

import com.capstone.itemsplit.ocr.engine.FakeClovaTransport;
import com.capstone.itemsplit.ocr.engine.FakeOcrScenario;
import com.capstone.itemsplit.receipt.*;
import com.capstone.itemsplit.room.*;
import com.capstone.itemsplit.storage.*;
import com.capstone.itemsplit.user.*;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Real PR 23 S3 adapter and OCR worker; only the AWS endpoint and CLOVA provider are simulated. */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:ocr-s3;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.storage.type=s3", "app.storage.s3.bucket=ocr-integration",
    "app.storage.cleanup-queue-path=./build/ocr-s3-cleanup"
})
@ActiveProfiles({"test","ocr-demo"})
@Testcontainers(disabledWithoutDocker=true)
@Import(OcrS3IntegrationTest.LocalS3.class)
class OcrS3IntegrationTest {
    private static final String BUCKET="ocr-integration";
    @Container static final LocalStackContainer localstack=new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8"))
        .withServices(LocalStackContainer.Service.S3);

    @Autowired StorageService storage;
    @Autowired S3Client s3;
    @Autowired UserRepository users;
    @Autowired RoomRepository rooms;
    @Autowired RoomMemberRepository members;
    @Autowired ReceiptImageService uploads;
    @Autowired ReceiptImageRepository images;
    @Autowired ReceiptService receiptService;
    @Autowired RoomService roomService;
    @Autowired OcrService ocr;
    @Autowired OcrJobRepository jobs;
    @Autowired OcrDraftItemRepository items;
    @Autowired OcrDemoService demos;

    @ParameterizedTest @ValueSource(booleans={false,true})
    void readsThreeS3ImagesIntoDraftAndDeletesObjectsWithReceiptOrRoom(boolean deleteRoom) throws Exception {
        var actor=actor();
        assertThat(storage).isInstanceOf(S3StorageService.class);
        byte[] bytes=FakeClovaTransport.demoImage(FakeOcrScenario.NORMAL);
        var created=uploads.createReceiptWithImage(actor.roomId(),actor.userId(),file(bytes),"S3 receipt");
        for(int i=0;i<2;i++) uploads.addImage(actor.roomId(),created.receiptId(),actor.userId(),file(bytes));
        var stored=images.findAllByReceiptId(created.receiptId());
        assertThat(stored).hasSize(3);
        for(var image:stored) assertThat(storage.read(image.getStoredPath())).isEqualTo(bytes);
        var job=ocr.start(actor.roomId(),created.receiptId(),actor.userId(),UUID.randomUUID().toString());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(()->
            assertThat(ocr.get(actor.roomId(),created.receiptId(),job.jobId(),actor.userId()).status()).isEqualTo("REVIEWABLE"));
        var done=ocr.get(actor.roomId(),created.receiptId(),job.jobId(),actor.userId());
        assertThat(done.provider()).isEqualTo("FAKE_CLOVA_RECEIPT");
        assertThat(done.draft().items()).hasSize(6);
        assertThat(done.draft().itemsTotal()).isEqualTo(54000L);
        if(deleteRoom) roomService.deleteRoom(actor.roomId(),actor.userId());
        else receiptService.deleteReceipt(actor.roomId(),created.receiptId(),actor.userId());
        for(var image:stored) assertThatThrownBy(()->storage.read(image.getStoredPath())).isInstanceOf(IOException.class);
        assertThat(s3.listObjectsV2(r->r.bucket(BUCKET).prefix("receipts/"+actor.roomId()+"/")).contents()).isEmpty();
        assertThat(jobs.findById(job.jobId())).isEmpty();
        assertThat(items.findAllByDraftIdOrderBySortOrder(done.draft().draftId())).isEmpty();
    }

    @Test void missingS3ObjectIsPersistedAsRetryableImageUnavailable() {
        var actor=actor();
        var receipt=demos.create(actor.roomId(),actor.userId(),"missing object",List.of(FakeOcrScenario.IMAGE_UNAVAILABLE));
        var job=ocr.start(actor.roomId(),receipt.receiptId(),actor.userId(),UUID.randomUUID().toString());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(()-> {
            var done=ocr.get(actor.roomId(),receipt.receiptId(),job.jobId(),actor.userId());
            assertThat(done.status()).isEqualTo("FAILED");
            assertThat(done.images().getFirst().errorCode()).isEqualTo("IMAGE_UNAVAILABLE");
            assertThat(done.images().getFirst().retryable()).isTrue();
            assertThat(done.draft().items()).isEmpty();
        });
        roomService.deleteRoom(actor.roomId(),actor.userId());
    }

    private Actor actor() {
        var user=users.save(User.create(UUID.randomUUID()+"@test.com","password","S3 tester"));
        var room=rooms.save(Room.create("S3 OCR",user));members.save(RoomMember.create(room,user));
        return new Actor(room.getId(),user.getId());
    }
    private MockMultipartFile file(byte[] bytes) {return new MockMultipartFile("file","test.png","image/png",bytes);}
    private record Actor(Long roomId,Long userId){}

    @TestConfiguration(proxyBeanMethods=false)
    static class LocalS3 {
        @Bean @Primary S3Client localstackClient() {
            var client=S3Client.builder().endpointOverride(localstack.getEndpoint()).region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(localstack.getAccessKey(),localstack.getSecretKey())))
                .forcePathStyle(true).build();
            client.createBucket(r->r.bucket(BUCKET));
            return client;
        }
    }
}
