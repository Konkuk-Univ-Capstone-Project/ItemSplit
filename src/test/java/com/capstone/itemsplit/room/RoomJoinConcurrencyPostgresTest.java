package com.capstone.itemsplit.room;

import com.capstone.itemsplit.common.exception.ApiException;
import com.capstone.itemsplit.common.exception.ErrorCode;
import com.capstone.itemsplit.user.User;
import com.capstone.itemsplit.user.UserRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Joins are queued behind a room lock held by the test, then released together,
 * so both requests are guaranteed to be in flight at the same time.
 */
@SpringBootTest(properties = "app.storage.local.root-path=./build/test-storage")
@Testcontainers(disabledWithoutDocker = true)
class RoomJoinConcurrencyPostgresTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired UserRepository users;
    @Autowired RoomRepository rooms;
    @Autowired RoomMemberRepository members;
    @Autowired RoomInviteTokenRepository inviteTokens;
    @Autowired RoomService roomService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void twoAccountsClaimingTheSameManualMemberLinkOnlyOne() throws Exception {
        Room room = room();
        RoomMember manual = members.save(RoomMember.createManual(room, "manual"));
        String token = invite(room);
        User first = user();
        User second = user();

        List<Object> results = whileRoomLocked(room,
            () -> roomService.joinRoom(token, first.getId(), manual.getId(), null),
            () -> roomService.joinRoom(token, second.getId(), manual.getId(), null));

        assertThat(results).filteredOn(RoomService.JoinRoomResult.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(ApiException.class::isInstance).singleElement()
            .satisfies(error -> assertThat(((ApiException) error).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        RoomService.JoinRoomResult joined = (RoomService.JoinRoomResult) results.stream()
            .filter(RoomService.JoinRoomResult.class::isInstance).findFirst().orElseThrow();
        assertThat(joined.memberId()).isEqualTo(manual.getId());
        Long linkedUser = jdbc.queryForObject("SELECT user_id FROM room_members WHERE id = ?", Long.class, manual.getId());
        assertThat(linkedUser).isIn(first.getId(), second.getId());
        assertThat(memberCount(room)).as("no extra member was created").isEqualTo(2);
    }

    @Test
    void theSameAccountJoiningTwiceAtOnceIsAddedOnce() throws Exception {
        Room room = room();
        RoomMember manualA = members.save(RoomMember.createManual(room, "a"));
        RoomMember manualB = members.save(RoomMember.createManual(room, "b"));
        String token = invite(room);
        User joiner = user();

        List<Object> results = whileRoomLocked(room,
            () -> roomService.joinRoom(token, joiner.getId(), manualA.getId(), null),
            () -> roomService.joinRoom(token, joiner.getId(), manualB.getId(), null));

        assertThat(results).allMatch(RoomService.JoinRoomResult.class::isInstance);
        // The second request finds the account already in the room and returns that member instead of claiming another.
        assertThat(results).extracting(result -> ((RoomService.JoinRoomResult) result).memberId())
            .containsOnly(((RoomService.JoinRoomResult) results.get(0)).memberId());
        assertThat(results).extracting(result -> ((RoomService.JoinRoomResult) result).joined())
            .containsExactlyInAnyOrder(true, false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM room_members WHERE room_id = ? AND user_id = ?",
            Long.class, room.getId(), joiner.getId())).isEqualTo(1);
        assertThat(memberCount(room)).isEqualTo(3);
    }

    private List<Object> whileRoomLocked(Room room, Callable<?>... joins) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(joins.length);
        try (Connection holder = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            holder.setAutoCommit(false);
            try (var lock = holder.prepareStatement("SELECT id FROM rooms WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, room.getId());
                lock.executeQuery().close();
            }
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<?> join : joins) {
                futures.add(pool.submit(() -> {
                    try {
                        return join.call();
                    } catch (ApiException exception) {
                        return exception;
                    }
                }));
            }
            awaitWaitingOnLock(joins.length);
            holder.commit();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private void awaitWaitingOnLock(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()",
                Integer.class);
            if (waiting != null && waiting >= expected) return;
            Thread.sleep(20);
        }
        throw new AssertionError("joins did not wait for the room lock");
    }

    private Room room() {
        User owner = user();
        Room room = rooms.save(Room.create("room", owner));
        members.save(RoomMember.create(room, owner));
        return room;
    }

    private String invite(Room room) {
        String token = UUID.randomUUID().toString();
        inviteTokens.save(RoomInviteToken.create(room, token, LocalDateTime.now().plusDays(1)));
        return token;
    }

    private User user() {
        return users.save(User.create(UUID.randomUUID() + "@example.com", "encoded", "user"));
    }

    private long memberCount(Room room) {
        return jdbc.queryForObject("SELECT count(*) FROM room_members WHERE room_id = ?", Long.class, room.getId());
    }
}
