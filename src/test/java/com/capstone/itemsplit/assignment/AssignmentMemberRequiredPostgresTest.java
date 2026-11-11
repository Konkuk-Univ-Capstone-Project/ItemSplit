package com.capstone.itemsplit.assignment;

import com.capstone.itemsplit.receipt.ReceiptService;
import com.capstone.itemsplit.receipt.ReceiptService.SaveItemCommand;
import com.capstone.itemsplit.room.Room;
import com.capstone.itemsplit.room.RoomMember;
import com.capstone.itemsplit.room.RoomMemberRepository;
import com.capstone.itemsplit.room.RoomRepository;
import com.capstone.itemsplit.room.RoomService;
import com.capstone.itemsplit.user.User;
import com.capstone.itemsplit.user.UserRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The required member column must not break unassigned items or member deletion on the real schema. */
@SpringBootTest(properties = "app.storage.local.root-path=./build/test-storage")
@Testcontainers(disabledWithoutDocker = true)
class AssignmentMemberRequiredPostgresTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired UserRepository users;
    @Autowired RoomRepository rooms;
    @Autowired RoomMemberRepository members;
    @Autowired ReceiptService receipts;
    @Autowired RoomService roomService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void unassignedItemsSaveAndDeletingAMemberRemovesOnlyTheirAssignments() {
        User owner = users.save(User.create(UUID.randomUUID() + "@example.com", "encoded", "owner"));
        Room room = rooms.save(Room.create("room", owner));
        RoomMember ownerMember = members.save(RoomMember.create(room, owner));
        RoomMember manual = members.save(RoomMember.createManual(room, "manual"));

        var receipt = receipts.createManualReceipt(room.getId(), owner.getId(), UUID.randomUUID().toString(),
            "receipt", ownerMember.getId(), null, List.of(
                new SaveItemCommand(null, "shared", 1000, 1, List.of(ownerMember.getId(), manual.getId()), false),
                new SaveItemCommand(null, "not decided", 500, 2, List.of(), false)));
        Long shared = receipt.items().get(0).itemId();
        Long undecided = receipt.items().get(1).itemId();
        assertThat(assignmentCount(undecided)).isZero();
        assertThat(assignmentCount(shared)).isEqualTo(2);

        roomService.deleteMember(room.getId(), manual.getId(), owner.getId());

        assertThat(jdbc.queryForList("SELECT room_member_id FROM assignments WHERE item_id = ?", Long.class, shared))
            .containsExactly(ownerMember.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM items WHERE receipt_id = ?", Long.class,
            receipt.receiptId())).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO assignments(item_id, room_member_id) VALUES (?, NULL)", undecided))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private long assignmentCount(Long itemId) {
        return jdbc.queryForObject("SELECT count(*) FROM assignments WHERE item_id = ?", Long.class, itemId);
    }
}
