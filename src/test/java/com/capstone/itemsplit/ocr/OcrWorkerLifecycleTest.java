package com.capstone.itemsplit.ocr;

import com.capstone.itemsplit.ocr.engine.ClovaReceiptParser;
import com.capstone.itemsplit.ocr.engine.FakeClovaTransport;
import com.capstone.itemsplit.ocr.engine.FakeOcrScenario;
import com.capstone.itemsplit.ocr.engine.ReceiptOcrEngine;
import com.capstone.itemsplit.ocr.engine.ReceiptOcrEngine.OcrImageInput;
import com.capstone.itemsplit.receipt.ReceiptImageRepository;
import com.capstone.itemsplit.receipt.ReceiptRepository;
import com.capstone.itemsplit.receipt.ReceiptService;
import com.capstone.itemsplit.room.Room;
import com.capstone.itemsplit.room.RoomMember;
import com.capstone.itemsplit.room.RoomMemberRepository;
import com.capstone.itemsplit.room.RoomRepository;
import com.capstone.itemsplit.user.User;
import com.capstone.itemsplit.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:ocr-lifecycle;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.ocr.worker-threads=2",
    "app.settlement.snapshots.enabled=true",
    "app.ocr.recovery-interval-ms=3600000"
})
@ActiveProfiles({"test", "ocr-demo"})
class OcrWorkerLifecycleTest {
    @Autowired UserRepository users;
    @Autowired RoomRepository rooms;
    @Autowired RoomMemberRepository members;
    @Autowired ReceiptRepository receipts;
    @Autowired ReceiptImageRepository receiptImages;
    @Autowired ReceiptService receiptService;
    @Autowired OcrDemoService demoService;
    @Autowired OcrService service;
    @Autowired OcrWorker worker;
    @Autowired OcrDraftService draftService;
    @Autowired com.capstone.itemsplit.settlement.SettlementSnapshotService settlements;
    @Autowired OcrJobRepository jobs;
    @Autowired OcrJobImageRepository jobImages;
    @Autowired OcrDraftRepository drafts;
    @Autowired OcrDraftItemRepository draftItems;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("ocrExecutor") ThreadPoolTaskExecutor executor;
    @MockitoBean ReceiptOcrEngine engine;

    @Test
    void deletingReceiptDuringRecognitionDiscardsTheLateResult() throws Exception {
        Actor actor = actor();
        var receipt = demoService.create(actor.roomId(), actor.userId(), "demo",
            List.of(FakeOcrScenario.NORMAL, FakeOcrScenario.NORMAL, FakeOcrScenario.NORMAL));
        try (var held = new HeldRecognition()) {
            var started = start(actor, receipt);
            held.awaitEntered();
            assertThat(userUsage(actor)).isEqualTo(3);

            receiptService.deleteReceipt(actor.roomId(), receipt.receiptId(), actor.userId());
            assertThat(jobs.findById(started.jobId())).isEmpty();
            assertThat(userUsage(actor)).isEqualTo(1);

            held.releaseAndDrain();
            assertThat(receipts.findById(receipt.receiptId())).isEmpty();
            assertThat(receiptImages.findAllByReceiptId(receipt.receiptId())).isEmpty();
            assertThat(jobs.findById(started.jobId())).isEmpty();
            assertThat(jobImages.findAllByJobIdOrderBySortOrder(started.jobId())).isEmpty();
            assertThat(drafts.findByJobId(started.jobId())).isEmpty();
            assertThat(draftItems.findAllByDraftIdOrderBySortOrder(started.draft().draftId())).isEmpty();
            assertThat(userUsage(actor)).isEqualTo(1);
            verify(engine, times(1)).recognize(any());
        }
    }

    @Test
    void revokedMembershipDuringRecognitionFailsWithoutPersistingCandidates() throws Exception {
        Actor actor = actor();
        var receipt = demo(actor);
        User observer = user();
        members.save(RoomMember.create(rooms.findById(actor.roomId()).orElseThrow(), observer));
        // The demo initially chooses the creator as payer; remove that reference before membership deletion.
        receiptService.updateReceipt(actor.roomId(), receipt.receiptId(), actor.userId(), "demo", null, null);
        try (var held = new HeldRecognition()) {
            var started = start(actor, receipt);
            held.awaitEntered();
            members.deleteById(actor.memberId());

            held.releaseAndDrain();
            var failed = service.get(actor.roomId(), receipt.receiptId(), started.jobId(), observer.getId());
            assertThat(failed.status()).isEqualTo("FAILED");
            var image = failed.images().getFirst();
            assertThat(image.status()).isEqualTo("FAILED");
            assertThat(image.errorCode()).isEqualTo("ACCESS_REVOKED");
            assertThat(image.retryable()).isFalse();
            assertThat(image.candidates()).isNull();
            assertThat(failed.draft().items()).isEmpty();
            assertThat(draftItems.findAllByDraftIdOrderBySortOrder(failed.draft().draftId())).isEmpty();
        }
    }

    @Test
    void interruptedAttemptCannotOverwriteSuccessfulRetryWhenItsResultArrivesLate() throws Exception {
        Actor actor = actor();
        var receipt = demo(actor);
        try (var held = new HeldRecognition()) {
            var started = start(actor, receipt);
            held.awaitEntered();

            worker.recoverOnStartup();
            var interrupted = service.get(actor.roomId(), receipt.receiptId(), started.jobId(), actor.userId());
            assertThat(interrupted.status()).isEqualTo("FAILED");
            assertThat(interrupted.images().getFirst().errorCode()).isEqualTo("INTERRUPTED");
            assertThat(interrupted.images().getFirst().retryable()).isTrue();
            assertThat(interrupted.draft().items()).isEmpty();

            service.retry(actor.roomId(), receipt.receiptId(), started.jobId(), receipt.imageIds().getFirst(), actor.userId());
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(service.get(actor.roomId(), receipt.receiptId(), started.jobId(), actor.userId()).status())
                    .isEqualTo("REVIEWABLE"));
            var successful = service.get(actor.roomId(), receipt.receiptId(), started.jobId(), actor.userId());
            assertThat(successful.images().getFirst().attemptCount()).isEqualTo(2);
            assertThat(successful.images().getFirst().errorCode()).isNull();
            assertThat(successful.draft().items()).hasSize(2);
            assertThat(successful.draft().itemsTotal()).isEqualTo(18000L);

            held.releaseAndDrain();
            // Includes candidate values, row IDs, revision and duration: a stale completion must change none of them.
            assertThat(service.get(actor.roomId(), receipt.receiptId(), started.jobId(), actor.userId()))
                .isEqualTo(successful);
            assertThat(draftItems.findAllByDraftIdOrderBySortOrder(successful.draft().draftId())).hasSize(2);
            verify(engine, times(2)).recognize(any());
        }
    }

    @Test
    void newlyQueuedRetryOfAnOldJobIsNotInterruptedByRecovery() throws Exception {
        Actor actor = actor();
        var receipt = demo(actor);
        when(engine.recognize(any())).thenThrow(new com.capstone.itemsplit.ocr.engine.OcrEngineException("PROVIDER_TIMEOUT", true));
        var started = start(actor, receipt);
        await().atMost(Duration.ofSeconds(10)).until(() ->
            service.get(actor.roomId(), receipt.receiptId(), started.jobId(), actor.userId()).status().equals("FAILED"));
        jdbc.update("UPDATE ocr_jobs SET created_at = ? WHERE id = ?",
            java.time.LocalDateTime.now().minusDays(1), started.jobId());

        CountDownLatch occupied = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 0; i < 2; i++) executor.execute(() -> {
                occupied.countDown();
                try { release.await(20, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertThat(occupied.await(10, TimeUnit.SECONDS)).isTrue();
            service.retry(actor.roomId(), receipt.receiptId(), started.jobId(), receipt.imageIds().getFirst(), actor.userId());
            service.retry(actor.roomId(), receipt.receiptId(), started.jobId(), receipt.imageIds().getFirst(), actor.userId());
            assertThat(userUsage(actor)).isEqualTo(2);
            worker.recoverStuckJobs();
            var queued = service.get(actor.roomId(), receipt.receiptId(), started.jobId(), actor.userId());
            assertThat(queued.images().getFirst().status()).isEqualTo("PENDING");
            assertThat(queued.images().getFirst().errorCode()).isNull();
        } finally {
            release.countDown();
            await().atMost(Duration.ofSeconds(10)).until(() ->
                executor.getActiveCount() == 0 && executor.getThreadPoolExecutor().getQueue().isEmpty());
        }
    }

    @Test
    void duplicateWorkerDeliveryCannotRecognizeAnAlreadyClaimedImage() throws Exception {
        Actor actor = actor();
        var receipt = demo(actor);
        try (var held = new HeldRecognition()) {
            var started = start(actor, receipt);
            held.awaitEntered();

            worker.process(started.jobId());
            verify(engine, times(1)).recognize(any());
            held.releaseAndDrain();

            var successful = service.get(actor.roomId(), receipt.receiptId(), started.jobId(), actor.userId());
            assertThat(successful.status()).isEqualTo("REVIEWABLE");
            assertThat(successful.images().getFirst().attemptCount()).isEqualTo(1);
            assertThat(successful.draft().items()).hasSize(2);
            verify(engine, times(1)).recognize(any());
        }
    }

    @Test
    void lateResultCannotWriteCandidatesIntoStoredConfirmedRoom() throws Exception {
        Actor actor=actor();
        var receipt=demoService.create(actor.roomId(),actor.userId(),"demo",List.of(FakeOcrScenario.NORMAL,FakeOcrScenario.NORMAL));
        try(var held=new HeldRecognition()) {
            var started=start(actor,receipt);held.awaitEntered();
            // Defensive persisted state: a worker queued before deployment must respect an existing lock.
            installConfirmedSnapshot(actor);
            held.releaseAndDrain();
            var result=service.get(actor.roomId(),receipt.receiptId(),started.jobId(),actor.userId());
            assertThat(result.draft().items()).isEmpty();
            assertThat(result.images()).hasSize(2).allSatisfy(image->{
                assertThat(image.candidates()).isNull();
                assertThat(image.status()).isEqualTo("FAILED");
                assertThat(image.errorCode()).isEqualTo("STALE_RESULT");
            });
            assertThat(userUsage(actor)).isEqualTo(1);
            verify(engine,times(1)).recognize(any());
        }
    }

    @Test
    void recoveryStillReleasesUncalledQuotaInConfirmedRoom() throws Exception {
        Actor actor=actor();
        var receipt=demoService.create(actor.roomId(),actor.userId(),"demo",List.of(FakeOcrScenario.NORMAL,FakeOcrScenario.NORMAL));
        try(var held=new HeldRecognition()) {
            var started=start(actor,receipt);held.awaitEntered();assertThat(userUsage(actor)).isEqualTo(2);
            installConfirmedSnapshot(actor);
            worker.recoverOnStartup();
            assertThat(userUsage(actor)).isEqualTo(1);
            var recovered=service.get(actor.roomId(),receipt.receiptId(),started.jobId(),actor.userId());
            assertThat(recovered.images()).allSatisfy(image->assertThat(image.status()).isEqualTo("FAILED"));
            held.releaseAndDrain();
            assertThat(service.get(actor.roomId(),receipt.receiptId(),started.jobId(),actor.userId())).isEqualTo(recovered);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void interruptedLateResultCannotChangeAppliedDraftAfterConfirmationOrReopen(boolean reopen) throws Exception {
        Actor actor=actor();var receipt=demo(actor);
        try(var held=new HeldRecognition()) {
            var started=start(actor,receipt);held.awaitEntered();
            worker.recoverOnStartup();
            var failed=service.get(actor.roomId(),receipt.receiptId(),started.jobId(),actor.userId());
            var saved=draftService.save(actor.roomId(),receipt.receiptId(),started.jobId(),actor.userId(),
                new OcrDraftService.SaveRequest(failed.draft().revision(),receipt.imageIds(),List.of(
                    new OcrDraftService.LineRequest(null,"reviewed",100L,1,List.of(actor.memberId()),false,false))));
            draftService.apply(actor.roomId(),receipt.receiptId(),started.jobId(),actor.userId(),new OcrDraftService.ApplyRequest(saved.draft().revision(),true));
            var preview=settlements.preview(actor.roomId(),actor.userId());assertThat(preview.ready()).isTrue();
            var snapshot=settlements.confirm(actor.roomId(),actor.userId(),new com.capstone.itemsplit.settlement.SettlementSnapshotService.ConfirmRequest("confirmed",preview.previewToken())).snapshot();
            if(reopen) settlements.reopen(actor.roomId(),snapshot.snapshotId(),actor.userId());
            var before=service.get(actor.roomId(),receipt.receiptId(),started.jobId(),actor.userId());
            var receiptBefore=receiptService.getReceipt(actor.roomId(),receipt.receiptId(),actor.userId());
            String previewBefore=settlements.preview(actor.roomId(),actor.userId()).previewToken();
            held.releaseAndDrain();
            assertThat(service.get(actor.roomId(),receipt.receiptId(),started.jobId(),actor.userId())).isEqualTo(before);
            assertThat(receiptService.getReceipt(actor.roomId(),receipt.receiptId(),actor.userId())).isEqualTo(receiptBefore);
            assertThat(settlements.preview(actor.roomId(),actor.userId()).previewToken()).isEqualTo(previewBefore);
            assertThat(settlements.detail(actor.roomId(),snapshot.snapshotId(),actor.userId()).status()).isEqualTo(reopen?"REOPENED":"CONFIRMED");
        }
    }

    private void installConfirmedSnapshot(Actor actor) {
        jdbc.update("insert into settlement_snapshots(room_id,room_name,version,status,confirmed_by_user_id,confirmed_at,request_id,request_fingerprint,input_fingerprint) values(?,'stored',1,'CONFIRMED',?,CURRENT_TIMESTAMP,'stored','stored','stored')",actor.roomId(),actor.userId());
        Long id=jdbc.queryForObject("select id from settlement_snapshots where room_id=?",Long.class,actor.roomId());
        jdbc.update("insert into room_settlement_states(room_id,status,current_snapshot_id) values(?,'CONFIRMED',?)",actor.roomId(),id);
    }

    private Actor actor() {
        User user = user();
        Room room = rooms.save(Room.create("OCR lifecycle", user));
        RoomMember member = members.save(RoomMember.create(room, user));
        return new Actor(room.getId(), user.getId(), member.getId());
    }

    private int userUsage(Actor actor) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(used_count), 0) FROM ocr_daily_usage WHERE quota_key LIKE ?",
            Integer.class, "USER:" + actor.userId() + ":%");
    }

    private User user() {
        return users.save(User.create(UUID.randomUUID() + "@test.com", "password", "tester"));
    }

    private OcrDemoService.DemoReceipt demo(Actor actor) {
        return demoService.create(actor.roomId(), actor.userId(), "demo", List.of(FakeOcrScenario.NORMAL));
    }

    private OcrViews.JobView start(Actor actor, OcrDemoService.DemoReceipt receipt) {
        return service.start(actor.roomId(), receipt.receiptId(), actor.userId(), UUID.randomUUID().toString());
    }

    private record Actor(Long roomId, Long userId, Long memberId) {}

    /** Keeps the first provider call outside the DB transaction until the test changes its lifecycle. */
    private final class HeldRecognition implements AutoCloseable {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        HeldRecognition() {
            AtomicInteger calls = new AtomicInteger();
            when(engine.recognize(any())).thenAnswer(invocation -> {
                OcrImageInput input = invocation.getArgument(0);
                if (calls.incrementAndGet() == 1) {
                    entered.countDown();
                    if (!release.await(20, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Test did not release the held OCR call");
                    }
                }
                return new ClovaReceiptParser(mapper).parse(new FakeClovaTransport().recognize(input));
            });
        }

        void awaitEntered() throws InterruptedException {
            assertThat(entered.await(10, TimeUnit.SECONDS)).as("worker reached the provider").isTrue();
        }

        void releaseAndDrain() {
            release.countDown();
            await().atMost(Duration.ofSeconds(10)).until(() ->
                executor.getActiveCount() == 0 && executor.getThreadPoolExecutor().getQueue().isEmpty());
        }

        @Override
        public void close() {
            releaseAndDrain();
        }
    }
}
