package com.capstone.itemsplit.settlement;

import com.capstone.itemsplit.room.*;
import com.capstone.itemsplit.user.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:edit_guard;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class RoomEditGuardTest {
    @Autowired RoomEditGuard guard;
    @Autowired RoomService roomService;
    @Autowired RoomRepository rooms;
    @Autowired UserRepository users;
    @Autowired RoomSettlementStateRepository states;
    @Autowired SettlementSnapshotRepository snapshots;
    @Autowired PlatformTransactionManager transactions;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    User owner;
    Room room;
    @BeforeEach void fixture() {
        owner=users.save(User.create(UUID.randomUUID()+"@test.com","encoded","owner"));
        var created=roomService.createRoom(owner.getId(),"room");
        room=rooms.findById(created.roomId()).orElseThrow();
    }
    @Test void guardRequiresExistingTransaction() {
        assertThatThrownBy(()->guard.requireEditable(room)).isInstanceOf(IllegalTransactionStateException.class);
    }
    @Test void uninitializedExistingRoomRemainsEditable() {
        assertThat(roomService.addManualMember(room.getId(),owner.getId(),"new").nickname()).isEqualTo("new");
    }
    @Test void storedConfirmationIsProtectedEvenWhenSnapshotFeatureIsDisabled() {
        var snapshot=snapshots.saveAndFlush(SettlementSnapshot.create(room,1,owner.getId(),"one","fingerprint","input",List.of(),List.of()));
        new TransactionTemplate(transactions).executeWithoutResult(tx->states.save(RoomSettlementState.confirmed(rooms.findById(room.getId()).orElseThrow(),snapshot.getId())));
        assertThatThrownBy(()->roomService.addManualMember(room.getId(),owner.getId(),"new"))
            .isInstanceOfSatisfying(SettlementRequestException.class,e->assertThat(e.reason()).isEqualTo("SETTLEMENT_LOCKED"));
    }
    @Test void missingStateWithHistoryFailsClosed() {
        snapshots.saveAndFlush(SettlementSnapshot.create(room,1,owner.getId(),"one","fingerprint","input",List.of(),List.of()));
        assertThatThrownBy(()->roomService.addManualMember(room.getId(),owner.getId(),"new")).isInstanceOf(IllegalStateException.class);
    }
    @Test void unknownStateFailsClosed() {
        jdbc.update("insert into room_settlement_states(room_id,status,current_snapshot_id) values(?,'UNKNOWN',null)",room.getId());
        assertThatThrownBy(()->new TransactionTemplate(transactions).executeWithoutResult(tx->
            guard.requireEditable(rooms.findByIdForUpdate(room.getId()).orElseThrow())))
            .isInstanceOf(IllegalStateException.class);
    }
}
