package com.capstone.itemsplit.settlement;

import com.capstone.itemsplit.assignment.Assignment;
import com.capstone.itemsplit.assignment.AssignmentRepository;
import com.capstone.itemsplit.item.Item;
import com.capstone.itemsplit.item.ItemRepository;
import com.capstone.itemsplit.receipt.Receipt;
import com.capstone.itemsplit.receipt.ReceiptRepository;
import com.capstone.itemsplit.receipt.ReceiptService;
import com.capstone.itemsplit.room.*;
import com.capstone.itemsplit.user.User;
import com.capstone.itemsplit.user.UserRepository;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.aop.framework.Advised;
import org.aopalliance.intercept.MethodInterceptor;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class SettlementSnapshotTest {
    @Autowired SettlementService settlements;
    @Autowired ReceiptService receiptService;
    @Autowired UserRepository users;
    @Autowired RoomRepository rooms;
    @Autowired RoomMemberRepository members;
    @Autowired ItemRepository items;
    @Autowired AssignmentRepository assignments;
    @Autowired RoomShareTokenRepository shareTokens;
    @Autowired RoomInviteTokenRepository inviteTokens;
    @Autowired ReceiptRepository receipts;

    @BeforeEach
    void cleanUp() {
        assignments.deleteAll();
        items.deleteAll();
        receipts.deleteAll();
        shareTokens.deleteAll();
        inviteTokens.deleteAll();
        members.deleteAll();
        rooms.deleteAll();
        users.deleteAll();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void atomicPayerAndParticipantChangeCannotCreatePhantomDebt(boolean shared) throws Exception {
        User owner = users.save(User.create("snapshot@example.com", "encoded", "owner"));
        Room room = rooms.save(Room.create("snapshot", owner));
        RoomMember first = members.save(RoomMember.create(room, owner));
        RoomMember second = members.save(RoomMember.createManual(room, "second"));
        Receipt receipt = receipts.save(Receipt.createManual(room, "receipt", first, 1000L, null));
        Item item = items.save(Item.create(receipt, "item", 1000L, 1));
        assignments.save(Assignment.create(item, first));
        CountDownLatch receiptRead = new CountDownLatch(1);
        CountDownLatch writerCommitted = new CountDownLatch(1);
        AtomicBoolean pauseNextRead = new AtomicBoolean(true);
        MethodInterceptor pauseAfterReceiptQuery = invocation -> {
            Object result = invocation.proceed();
            if (invocation.getMethod().getName().equals("findAllByRoomId")
                && pauseNextRead.compareAndSet(true, false)) {
                receiptRead.countDown();
                if (!writerCommitted.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("Concurrent writer did not commit in time");
                }
            }
            return result;
        };
        Advised repositoryProxy = (Advised) receipts;
        repositoryProxy.addAdvice(0, pauseAfterReceiptQuery);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var reader = executor.submit(() -> shared
                ? settlements.calculateShared(room) : settlements.calculate(room.getId(), owner.getId()));
            try {
                if (!receiptRead.await(10, TimeUnit.SECONDS)) {
                    // Surface an early reader error rather than hiding it behind a timeout.
                    if (reader.isDone()) reader.get(1, TimeUnit.SECONDS);
                    throw new AssertionError("Settlement did not reach the receipt query");
                }
                var writer = executor.submit(() -> receiptService.saveContents(room.getId(), receipt.getId(), owner.getId(),
                    "receipt", second.getId(), 1000L, null,
                    List.of(new ReceiptService.SaveItemCommand(item.getId(), "item", 1000L, 1,
                        List.of(second.getId()), false))));
                writer.get(10, TimeUnit.SECONDS);
            } finally {
                writerCommitted.countDown();
            }
            SettlementService.SettlementResult result = reader.get(10, TimeUnit.SECONDS);
            assertThat(result.ready()).isTrue();
            // Before: A pays/consumes everything. After: B pays/consumes everything.
            // Both committed states have no transfers; mixing them invents a debt.
            assertThat(result.members()).allSatisfy(member -> {
                assertThat(member.net()).isZero();
                assertThat(member.paid()).isEqualTo(member.burden());
            });
        } finally {
            repositoryProxy.removeAdvice(pauseAfterReceiptQuery);
        }
    }
}
