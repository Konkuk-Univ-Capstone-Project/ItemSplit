package com.capstone.itemsplit.ocr;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "app.storage.local.root-path=./build/test-storage")
@Testcontainers(disabledWithoutDocker = true)
class OcrDailyQuotaPostgresTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final AtomicLong ids = new AtomicLong(1000);
    @Autowired OcrQuotaService quotas;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @Test void fifteenthReceiptCallSucceedsAndSixteenthRollsBack() {
        long receipt=ids.incrementAndGet(),user=ids.incrementAndGet();
        for(int i=0;i<15;i++) reserve(receipt,user,1);
        assertThatThrownBy(()->reserve(receipt,user,1)).isInstanceOfSatisfying(OcrRequestException.class,
            e->assertThat(e.reason()).isEqualTo("OCR_RECEIPT_DAILY_LIMIT"));
        assertThat(usage("RECEIPT",receipt)).isEqualTo(15);
        assertThat(usage("USER",user)).isEqualTo(15);
    }

    @Test void sixtiethUserCallSucceedsAndRejectedBatchDoesNotChargeEitherScope() {
        long user=ids.incrementAndGet();
        for(int i=0;i<19;i++)reserve(ids.incrementAndGet(),user,3);
        reserve(ids.incrementAndGet(),user,2); // 59
        long rejectedReceipt=ids.incrementAndGet();
        assertThatThrownBy(()->reserve(rejectedReceipt,user,2)).isInstanceOfSatisfying(OcrRequestException.class,
            e->assertThat(e.reason()).isEqualTo("OCR_USER_DAILY_LIMIT"));
        assertThat(usage("RECEIPT",rejectedReceipt)).isZero();
        assertThat(usage("USER",user)).isEqualTo(59);
        reserve(rejectedReceipt,user,1);
        assertThatThrownBy(()->reserve(ids.incrementAndGet(),user,1)).isInstanceOf(OcrRequestException.class);
        assertThat(usage("USER",user)).isEqualTo(60);
        assertThat(usage("RECEIPT",rejectedReceipt)).isEqualTo(1);
    }

    @Test void competingFirstReservationsAndLastSlotsCannotExceedUserLimit() throws Exception {
        long user=ids.incrementAndGet();
        assertThat(race(user,8)).containsOnly("accepted"); // concurrent insertion of a new user's counter
        for(int i=0;i<17;i++)reserve(ids.incrementAndGet(),user,3); // 59
        assertThat(race(user,8)).filteredOn("accepted"::equals).hasSize(1);
        assertThat(usage("USER",user)).isEqualTo(60);
    }

    private List<String> race(long user,int count) throws Exception {
        CountDownLatch ready=new CountDownLatch(count),go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(count)) {
            List<Future<String>> futures=new ArrayList<>();
            for(int i=0;i<count;i++)futures.add(pool.submit(()->{
                long receipt=ids.incrementAndGet();ready.countDown();
                if(!go.await(5,TimeUnit.SECONDS))throw new IllegalStateException("race gate");
                try{reserve(receipt,user,1);return "accepted";}
                catch(OcrRequestException e){assertThat(e.reason()).isEqualTo("OCR_USER_DAILY_LIMIT");return e.reason();}
            }));
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();go.countDown();
            List<String> results=new ArrayList<>();
            for(var future:futures)results.add(future.get(10,TimeUnit.SECONDS));
            return results;
        }
    }
    private void reserve(long receipt,long user,int count) {
        new TransactionTemplate(transactions).executeWithoutResult(s->quotas.reserve(receipt,user,count));
    }
    private int usage(String scope,long id) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(used_count),0) FROM ocr_daily_usage WHERE quota_key LIKE ?",Integer.class,scope+":"+id+":%");
    }
}
