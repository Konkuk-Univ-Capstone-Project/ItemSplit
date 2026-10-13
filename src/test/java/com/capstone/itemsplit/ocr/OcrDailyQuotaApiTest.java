package com.capstone.itemsplit.ocr;

import com.capstone.itemsplit.auth.JwtTokenProvider;
import com.capstone.itemsplit.ocr.engine.FakeOcrScenario;
import com.capstone.itemsplit.receipt.ReceiptService;
import com.capstone.itemsplit.room.*;
import com.capstone.itemsplit.user.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:ocr-quota-api;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.ocr.limit.per-receipt-daily=2", "app.ocr.limit.per-user-daily=4"
})
@ActiveProfiles({"test", "ocr-demo"}) @AutoConfigureMockMvc
class OcrDailyQuotaApiTest {
    @Autowired UserRepository users;
    @Autowired RoomRepository rooms;
    @Autowired RoomMemberRepository members;
    @Autowired OcrDemoService demo;
    @Autowired OcrService service;
    @Autowired ReceiptService receipts;
    @Autowired JwtTokenProvider tokens;
    @Autowired MockMvc mvc;
    @Autowired OcrWorker worker;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("ocrExecutor") ThreadPoolTaskExecutor executor;
    @org.springframework.test.context.bean.override.mockito.MockitoBean(name="ocrQuotaClock") Clock quotaClock;
    private final MutableClock clock = new MutableClock();

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>();
        void at(String value) { now.set(Instant.parse(value)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
    @BeforeEach void resetTime() {
        clock.at("2026-10-12T14:59:00Z");
        org.mockito.Mockito.when(quotaClock.instant()).thenAnswer(call->clock.instant());
    }

    @Test void thirdReceiptAttemptIsRejectedWithSpecific429Reason() throws Exception {
        Actor a = actor(null);
        var r = receipt(a, FakeOcrScenario.TIMEOUT);
        var first = start(a,r,"first"); complete(a,r);
        service.retry(a.roomId(),r.receiptId(),first.jobId(),r.imageIds().getFirst(),a.userId());
        complete(a,r);
        mvc.perform(post(path(a,r)+"/"+first.jobId()+"/images/"+r.imageIds().getFirst()+"/retry")
            .header("Authorization",a.token()))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error.details[0].reason").value("OCR_RECEIPT_DAILY_LIMIT"));
        assertThat(service.latest(a.roomId(),r.receiptId(),a.userId()).images().getFirst().attemptCount()).isEqualTo(2);
    }

    @Test void overBudgetBatchIsRejectedAtomicallyAndDoesNotUseRemainingSlot() throws Exception {
        Actor a = actor(null);
        for(int i=0;i<3;i++) {var r=receipt(a,FakeOcrScenario.NORMAL);start(a,r,"ok");complete(a,r);}
        var batch=receipt(a,FakeOcrScenario.NORMAL,FakeOcrScenario.NORMAL);
        mvc.perform(post(path(a,batch)).header("Authorization",a.token()).contentType("application/json")
                .content("{\"requestId\":\"batch\"}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error.details[0].reason").value("OCR_USER_DAILY_LIMIT"));
        assertThat(service.latest(a.roomId(),batch.receiptId(),a.userId())).isNull();
        var last=receipt(a,FakeOcrScenario.NORMAL);start(a,last,"last");complete(a,last);
        assertLimit(a,receipt(a,FakeOcrScenario.NORMAL),"OCR_USER_DAILY_LIMIT");
    }

    @Test void repeatedRequestIdDoesNotUseAnotherSlotAndDeletionDoesNotRestoreSpentUsage() {
        Actor a=actor(null);
        var first=receipt(a,FakeOcrScenario.NORMAL); var started=start(a,first,"same");complete(a,first);
        for(int i=0;i<5;i++) assertThat(start(a,first,"same").jobId()).isEqualTo(started.jobId());
        receipts.deleteReceipt(a.roomId(),first.receiptId(),a.userId());
        for(int i=0;i<3;i++) {var r=receipt(a,FakeOcrScenario.NORMAL);start(a,r,"next");complete(a,r);}
        assertLimit(a,receipt(a,FakeOcrScenario.NORMAL),"OCR_USER_DAILY_LIMIT");
    }

    @Test void unreadableImagesAreRefundedBeforeTheyReachTheProvider() {
        Actor a=actor(null);
        for(int i=0;i<5;i++) {var r=receipt(a,FakeOcrScenario.IMAGE_UNAVAILABLE);start(a,r,"missing");complete(a,r);}
        for(int i=0;i<4;i++) {var r=receipt(a,FakeOcrScenario.NORMAL);start(a,r,"ok");complete(a,r);}
        assertLimit(a,receipt(a,FakeOcrScenario.NORMAL),"OCR_USER_DAILY_LIMIT");
    }

    @Test void limitsResetAtKoreanMidnightWithoutResettingImageAttemptCount() {
        Actor a=actor(null);
        var failed=receipt(a,FakeOcrScenario.TIMEOUT);var job=start(a,failed,"retry");complete(a,failed);
        service.retry(a.roomId(),failed.receiptId(),job.jobId(),failed.imageIds().getFirst(),a.userId());complete(a,failed);
        for(int i=0;i<2;i++) {var r=receipt(a,FakeOcrScenario.NORMAL);start(a,r,"old-day");complete(a,r);}
        assertLimit(a,receipt(a,FakeOcrScenario.NORMAL),"OCR_USER_DAILY_LIMIT");
        clock.at("2026-10-12T15:00:00Z");
        service.retry(a.roomId(),failed.receiptId(),job.jobId(),failed.imageIds().getFirst(),a.userId());complete(a,failed);
        assertThatThrownBy(()->service.retry(a.roomId(),failed.receiptId(),job.jobId(),failed.imageIds().getFirst(),a.userId()))
            .isInstanceOfSatisfying(OcrRequestException.class,e->assertThat(e.reason()).isEqualTo("OCR_ATTEMPT_LIMIT"));
        for(int i=0;i<3;i++) {var r=receipt(a,FakeOcrScenario.NORMAL);start(a,r,"new-day");complete(a,r);}
        assertLimit(a,receipt(a,FakeOcrScenario.NORMAL),"OCR_USER_DAILY_LIMIT");
    }

    @Test void recoveryAfterMidnightRefundsTheOriginalReservationDayOnly() throws Exception {
        Actor a=actor(null);
        int threads=executor.getCorePoolSize();
        CountDownLatch occupied=new CountDownLatch(threads),release=new CountDownLatch(1);
        try {
            for(int i=0;i<threads;i++) executor.execute(()->{
                occupied.countDown();
                try {release.await(20,TimeUnit.SECONDS);}
                catch(InterruptedException e){Thread.currentThread().interrupt();}
            });
            assertThat(occupied.await(10,TimeUnit.SECONDS)).isTrue();
            var queued=receipt(a,FakeOcrScenario.NORMAL);
            start(a,queued,"queued");
            assertThat(usage(a,"2026-10-12")).isEqualTo(1);
            clock.at("2026-10-12T15:00:00Z");
            worker.recoverOnStartup();
            assertThat(usage(a,"2026-10-12")).isZero();
            assertThat(usage(a,"2026-10-13")).isZero();
        } finally {
            release.countDown();
            await().atMost(Duration.ofSeconds(10)).until(()->executor.getActiveCount()==0
                && executor.getThreadPoolExecutor().getQueue().isEmpty());
        }
        for(int i=0;i<4;i++){var r=receipt(a,FakeOcrScenario.NORMAL);start(a,r,"today");complete(a,r);}
        assertLimit(a,receipt(a,FakeOcrScenario.NORMAL),"OCR_USER_DAILY_LIMIT");
    }

    private int usage(Actor a,String date) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(used_count),0) FROM ocr_daily_usage WHERE quota_key=?",
            Integer.class,"USER:"+a.userId()+":"+date);
    }

    @Test void concurrentRequestsInDifferentRoomsShareOneUserQuota() throws Exception {
        Actor a=actor(null),b=actor(a.userId());
        for(int i=0;i<3;i++) {var r=receipt(a,FakeOcrScenario.NORMAL);start(a,r,"used");complete(a,r);}
        var left=receipt(a,FakeOcrScenario.NORMAL);var right=receipt(b,FakeOcrScenario.NORMAL);
        CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            List<Future<String>> results=List.of(
                pool.submit(()->race(a,left,ready,go)),pool.submit(()->race(b,right,ready,go)));
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();go.countDown();
            assertThat(List.of(results.get(0).get(10,TimeUnit.SECONDS),results.get(1).get(10,TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("accepted","OCR_USER_DAILY_LIMIT");
        }
    }

    private String race(Actor a,OcrDemoService.DemoReceipt r,CountDownLatch ready,CountDownLatch go) throws Exception {
        ready.countDown();if(!go.await(5,TimeUnit.SECONDS))throw new IllegalStateException("race gate");
        try {start(a,r,"race");complete(a,r);return "accepted";}
        catch(OcrRequestException e){return e.reason();}
    }
    private void assertLimit(Actor a,OcrDemoService.DemoReceipt r,String reason) {
        assertThatThrownBy(()->start(a,r,"limit")).isInstanceOfSatisfying(OcrRequestException.class,
            e->assertThat(e.reason()).isEqualTo(reason));
    }
    private Actor actor(Long userId) {
        var user=userId==null?users.save(User.create(UUID.randomUUID()+"@quota.test","password","tester")):users.findById(userId).orElseThrow();
        var room=rooms.save(Room.create("quota",user));members.save(RoomMember.create(room,user));
        return new Actor(user.getId(),room.getId(),"Bearer "+tokens.createAccessToken(user).accessToken());
    }
    private OcrDemoService.DemoReceipt receipt(Actor a,FakeOcrScenario... scenarios) {return demo.create(a.roomId(),a.userId(),"receipt",List.of(scenarios));}
    private OcrViews.JobView start(Actor a,OcrDemoService.DemoReceipt r,String requestId) {return service.start(a.roomId(),r.receiptId(),a.userId(),requestId);}
    private void complete(Actor a,OcrDemoService.DemoReceipt r) {
        await().atMost(Duration.ofSeconds(10)).until(()->!List.of("PENDING","PROCESSING").contains(service.latest(a.roomId(),r.receiptId(),a.userId()).status()));
    }
    private String path(Actor a,OcrDemoService.DemoReceipt r){return "/api/rooms/"+a.roomId()+"/receipts/"+r.receiptId()+"/ocr/jobs";}
    private record Actor(Long userId,Long roomId,String token){}
}
