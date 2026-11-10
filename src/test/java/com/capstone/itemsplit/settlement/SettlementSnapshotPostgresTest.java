package com.capstone.itemsplit.settlement;

import com.capstone.itemsplit.assignment.*;
import com.capstone.itemsplit.item.*;
import com.capstone.itemsplit.receipt.*;
import com.capstone.itemsplit.room.*;
import com.capstone.itemsplit.user.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"app.settlement.snapshots.enabled=true","app.storage.local.root-path=./build/test-storage"})
@Testcontainers(disabledWithoutDocker=true)
class SettlementSnapshotPostgresTest {
    @Container @ServiceConnection static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired SettlementSnapshotService service;
    @Autowired UserRepository users;
    @Autowired RoomRepository rooms;
    @Autowired RoomMemberRepository members;
    @Autowired ReceiptRepository receipts;
    @Autowired ItemRepository items;
    @Autowired AssignmentRepository assignments;
    @Autowired JdbcTemplate jdbc;
    User owner;Room room;RoomMember payer,debtor;
    @BeforeEach void fixture() {
        owner=users.save(User.create(UUID.randomUUID()+"@test.com","encoded","owner"));
        room=rooms.save(Room.create("room",owner));payer=members.save(RoomMember.create(room,owner));
        debtor=members.save(RoomMember.createManual(room,"debtor"));
        var receipt=receipts.save(Receipt.createManual(room,"receipt",payer,null));
        var item=items.save(Item.create(receipt,"item",100,1));assignments.save(Assignment.create(item,debtor));
    }
    @Test void concurrentIdenticalRequestsCreateExactlyOneSnapshot() throws Exception {
        var results=race(true);
        assertThat(results).filteredOn(SettlementSnapshotService.Confirmation.class::isInstance).hasSize(2);
        var confirmations=results.stream().map(SettlementSnapshotService.Confirmation.class::cast).toList();
        assertThat(confirmations).extracting(SettlementSnapshotService.Confirmation::created).containsExactlyInAnyOrder(true,false);
        assertThat(confirmations.get(0).snapshot()).isEqualTo(confirmations.get(1).snapshot());
        assertThat(count("settlement_snapshots")).isEqualTo(1);
    }
    @Test void concurrentDifferentRequestsSerializeToOneSuccessAndOneConflict() throws Exception {
        var results=race(false);
        assertThat(results).filteredOn(SettlementSnapshotService.Confirmation.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(String.class::isInstance).containsExactly("SETTLEMENT_VERSION_CONFLICT");
        assertThat(count("settlement_snapshots")).isEqualTo(1);
    }
    @Test void migrationConstraintsPreserveHistoricalIdsAndRejectCrossSnapshotTransfer() {
        var snapshot=service.confirm(room.getId(),owner.getId(),new SettlementSnapshotService.ConfirmRequest("one",service.preview(room.getId(),owner.getId()).previewToken())).snapshot();
        assertThatThrownBy(()->jdbc.update("update settlement_transfers set amount=0 where snapshot_id=?",snapshot.snapshotId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("update settlement_transfers set to_member_id=999999 where snapshot_id=?",snapshot.snapshotId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("update settlement_transfers set status='COMPLETED' where snapshot_id=?",snapshot.snapshotId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("delete from assignments where room_member_id=?",debtor.getId());members.delete(debtor);
        assertThat(service.detail(room.getId(),snapshot.snapshotId(),owner.getId()).members()).extracting(SettlementSnapshotService.MemberView::memberId).contains(debtor.getId());
        jdbc.update("insert into settlement_snapshot_share_tokens(snapshot_id,token_hash,created_at,expires_at) values(?, ?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + interval '1 day')",snapshot.snapshotId(),"a".repeat(64));
        jdbc.update("update room_settlement_states set status='OPEN',current_snapshot_id=null where room_id=?",room.getId());
        jdbc.update("delete from items where receipt_id in (select id from receipts where room_id=?)",room.getId());
        jdbc.update("delete from receipts where room_id=?",room.getId());jdbc.update("delete from room_members where room_id=?",room.getId());
        jdbc.update("delete from rooms where id=?",room.getId());
        assertThat(count("settlement_snapshots")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from settlement_snapshot_share_tokens where snapshot_id=?",Long.class,snapshot.snapshotId())).isZero();
    }
    private long count(String table) {return jdbc.queryForObject("select count(*) from "+table+" where room_id=?",Long.class,room.getId());}
    private List<Object> race(boolean sameRequest) throws Exception {
        String preview=service.preview(room.getId(),owner.getId()).previewToken();
        CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            List<Future<Object>> futures=new ArrayList<>();
            for(int i=0;i<2;i++) {
                String request=sameRequest?"same":"different-"+i;
                futures.add(pool.submit(()->{
                    ready.countDown();assertThat(go.await(5,TimeUnit.SECONDS)).isTrue();
                    try {return service.confirm(room.getId(),owner.getId(),new SettlementSnapshotService.ConfirmRequest(request,preview));}
                    catch(SettlementRequestException exception) {return exception.reason();}
                }));
            }
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();go.countDown();
            List<Object> results=new ArrayList<>();for(var future:futures)results.add(future.get(15,TimeUnit.SECONDS));return results;
        }
    }
}
