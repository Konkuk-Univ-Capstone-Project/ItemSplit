package com.capstone.itemsplit.settlement;

import com.capstone.itemsplit.assignment.*;
import com.capstone.itemsplit.common.exception.ApiException;
import com.capstone.itemsplit.common.exception.ErrorCode;
import com.capstone.itemsplit.item.*;
import com.capstone.itemsplit.receipt.*;
import com.capstone.itemsplit.room.*;
import com.capstone.itemsplit.user.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties={"app.settlement.snapshots.enabled=true","app.storage.local.root-path=./build/test-storage"})
@Testcontainers(disabledWithoutDocker=true)
class SettlementSnapshotPostgresTest {
    @Container @ServiceConnection static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired SettlementSnapshotService service;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired UserRepository users;
    @Autowired RoomRepository rooms;
    @Autowired RoomMemberRepository members;
    @Autowired ReceiptRepository receipts;
    @Autowired ItemRepository items;
    @Autowired AssignmentRepository assignments;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ItemService itemService;
    @Autowired RoomService roomService;
    @Autowired AssignmentService assignmentService;
    @Autowired RoomSettlementStateRepository states;
    User owner;Room room;RoomMember payer,debtor;Receipt receipt;Item item;
    @BeforeEach void fixture() {
        owner=users.save(User.create(UUID.randomUUID()+"@test.com","encoded","owner"));
        room=rooms.save(Room.create("room",owner));payer=members.save(RoomMember.create(room,owner));
        debtor=members.save(RoomMember.createManual(room,"debtor"));
        receipt=receipts.save(Receipt.createManual(room,"receipt",payer,null));
        item=items.save(Item.create(receipt,"item",100,1));assignments.save(Assignment.create(item,debtor));
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

    @Test void itemUpdateWaitingForConfirmationIsRejectedWithoutChangingAmounts() throws Exception {
        var request=confirmationRequest("confirm-before-edit");
        var results=orderedRace(()->service.confirm(room.getId(),owner.getId(),request),()->updateItem(200));

        assertThat(results.get(0)).isInstanceOf(SettlementSnapshotService.Confirmation.class);
        assertThat(results.get(1)).isEqualTo("SETTLEMENT_LOCKED");
        assertThat(items.findById(item.getId()).orElseThrow().getPrice()).isEqualTo(100);
        var snapshot=((SettlementSnapshotService.Confirmation)results.get(0)).snapshot();
        assertThat(snapshot.transfers()).singleElement().satisfies(transfer->assertThat(transfer.amount()).isEqualTo(100));
    }

    @Test void confirmationWaitingForItemUpdateRejectsTheOldPreview() throws Exception {
        var request=confirmationRequest("edit-before-confirm");
        var results=orderedRace(()->updateItem(200),()->service.confirm(room.getId(),owner.getId(),request));

        assertThat(results.get(0)).isInstanceOf(ItemService.ItemResult.class);
        assertThat(results.get(1)).isEqualTo("SETTLEMENT_INPUT_CHANGED");
        assertThat(items.findById(item.getId()).orElseThrow().getPrice()).isEqualTo(200);
        assertThat(count("settlement_snapshots")).isZero();
        assertThat(service.state(room.getId(),owner.getId()).status()).isEqualTo("OPEN");
    }

    @Test void manualMemberLinkWaitingForConfirmationCannotChangeTheConfirmedMembership() throws Exception {
        User joiningUser=users.save(User.create(UUID.randomUUID()+"@test.com","encoded","joining"));
        String invite=roomService.issueInviteToken(room.getId(),owner.getId()).token();
        var request=confirmationRequest("confirm-before-link");
        var results=orderedRace(()->service.confirm(room.getId(),owner.getId(),request),
            ()->roomService.joinRoom(invite,joiningUser.getId(),debtor.getId(),"debtor"));

        assertThat(results.get(0)).isInstanceOf(SettlementSnapshotService.Confirmation.class);
        assertThat(results.get(1)).isEqualTo("SETTLEMENT_LOCKED");
        assertThat(members.findById(debtor.getId()).orElseThrow().isLinkedUser()).isFalse();
        assertThat(members.existsByRoomIdAndUserId(room.getId(),joiningUser.getId())).isFalse();
        assertThat(members.findAllByRoomId(room.getId())).hasSize(2);
    }

    @Test void confirmationWaitingForManualMemberLinkRejectsPreviewEvenWhenNamesAndAmountsStayTheSame() throws Exception {
        User joiningUser=users.save(User.create(UUID.randomUUID()+"@test.com","encoded","joining"));
        String invite=roomService.issueInviteToken(room.getId(),owner.getId()).token();
        var request=confirmationRequest("link-before-confirm");
        var results=orderedRace(()->roomService.joinRoom(invite,joiningUser.getId(),debtor.getId(),"debtor"),
            ()->service.confirm(room.getId(),owner.getId(),request));

        assertThat(results.get(0)).isInstanceOf(RoomService.JoinRoomResult.class);
        assertThat(results.get(1)).isEqualTo("SETTLEMENT_INPUT_CHANGED");
        assertThat(members.existsByRoomIdAndUserId(room.getId(),joiningUser.getId())).isTrue();
        assertThat(count("settlement_snapshots")).isZero();
    }

    @Test void joinWaitingForInviteReissueRejectsThePreviouslyValidToken() throws Exception {
        User joiningUser=users.save(User.create(UUID.randomUUID()+"@test.com","encoded","joining"));
        String oldInvite=roomService.issueInviteToken(room.getId(),owner.getId()).token();
        var results=orderedRace(()->roomService.issueInviteToken(room.getId(),owner.getId()),
            ()->roomService.joinRoom(oldInvite,joiningUser.getId(),null,"joining"));

        assertThat(results.get(0)).isInstanceOfSatisfying(RoomService.InviteTokenResult.class,
            invite->assertThat(invite.token()).isNotEqualTo(oldInvite));
        assertThat(results.get(1)).isInstanceOfSatisfying(ApiException.class,
            exception->assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThat(members.existsByRoomIdAndUserId(room.getId(),joiningUser.getId())).isFalse();
        assertThat(members.findAllByRoomId(room.getId())).hasSize(2);
    }

    @Test void twoAccountsClaimingTheSameManualMemberCannotOverwriteTheFirstLink() throws Exception {
        User firstUser=users.save(User.create(UUID.randomUUID()+"@test.com","encoded","first"));
        User secondUser=users.save(User.create(UUID.randomUUID()+"@test.com","encoded","second"));
        String invite=roomService.issueInviteToken(room.getId(),owner.getId()).token();
        var results=orderedRace(()->roomService.joinRoom(invite,firstUser.getId(),debtor.getId(),"first"),
            ()->roomService.joinRoom(invite,secondUser.getId(),debtor.getId(),"second"));

        assertThat(results.get(0)).isInstanceOfSatisfying(RoomService.JoinRoomResult.class,result->{
            assertThat(result.memberId()).isEqualTo(debtor.getId());
            assertThat(result.joined()).isTrue();
        });
        assertThat(results.get(1)).isInstanceOfSatisfying(ApiException.class,
            exception->assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        var linked=members.findByRoomIdAndUserId(room.getId(),firstUser.getId()).orElseThrow();
        assertThat(linked.getId()).isEqualTo(debtor.getId());
        assertThat(linked.getDisplayName()).isEqualTo("first");
        assertThat(members.existsByRoomIdAndUserId(room.getId(),secondUser.getId())).isFalse();
        assertThat(members.findAllByRoomId(room.getId())).hasSize(2);
    }

    @Test void guardRefreshesOpenStateLoadedBeforeWaitingForAReconfirmation() throws Exception {
        var first=service.confirm(room.getId(),owner.getId(),confirmationRequest("first")).snapshot();
        service.reopen(room.getId(),first.snapshotId(),owner.getId());
        var request=confirmationRequest("second");

        var results=orderedRace(()->service.confirm(room.getId(),owner.getId(),request),()->updateItem(200),
            ()->assertThat(states.findById(room.getId()).orElseThrow().getStatus()).isEqualTo("OPEN"));

        assertThat(results.get(1)).isEqualTo("SETTLEMENT_LOCKED");
        assertThat(items.findById(item.getId()).orElseThrow().getPrice()).isEqualTo(100);
        assertThat(service.state(room.getId(),owner.getId()).status()).isEqualTo("CONFIRMED");
        assertThat(count("settlement_snapshots")).isEqualTo(2);
    }

    @Test void staleReopenWaitingForReconfirmationCannotUnlockTheNewSnapshot() throws Exception {
        var first=service.confirm(room.getId(),owner.getId(),confirmationRequest("first")).snapshot();
        service.reopen(room.getId(),first.snapshotId(),owner.getId());
        var request=confirmationRequest("second");
        var results=orderedRace(()->service.confirm(room.getId(),owner.getId(),request),
            ()->service.reopen(room.getId(),first.snapshotId(),owner.getId()));

        assertThat(results.get(1)).isEqualTo("SETTLEMENT_VERSION_CONFLICT");
        var second=((SettlementSnapshotService.Confirmation)results.get(0)).snapshot();
        assertThat(service.state(room.getId(),owner.getId()))
            .isEqualTo(new SettlementSnapshotService.StateView(room.getId(),"CONFIRMED",second.snapshotId()));
        assertThat(service.detail(room.getId(),first.snapshotId(),owner.getId()).status()).isEqualTo("REOPENED");
    }

    @Test void confirmationWaitingForReopenRejectsThePreviewFromThePreviousGeneration() throws Exception {
        var originalRequest=confirmationRequest("first");
        var first=service.confirm(room.getId(),owner.getId(),originalRequest).snapshot();
        var results=orderedRace(()->service.reopen(room.getId(),first.snapshotId(),owner.getId()),
            ()->service.confirm(room.getId(),owner.getId(),
                new SettlementSnapshotService.ConfirmRequest("second",originalRequest.previewToken())));

        assertThat(results.get(0)).isEqualTo(new SettlementSnapshotService.StateView(room.getId(),"OPEN",null));
        assertThat(results.get(1)).isEqualTo("SETTLEMENT_INPUT_CHANGED");
        assertThat(count("settlement_snapshots")).isEqualTo(1);
    }

    @Test void reopenAndReconfirmPreserveDeletedMembersAmountsAndCompletedTransfersInHistory() {
        var originalRequest=confirmationRequest("first");
        var first=service.confirm(room.getId(),owner.getId(),originalRequest).snapshot();
        service.completion(room.getId(),first.snapshotId(),first.transfers().getFirst().transferId(),owner.getId(),true);
        var historical=service.detail(room.getId(),first.snapshotId(),owner.getId());
        var open=service.reopen(room.getId(),first.snapshotId(),owner.getId());
        assertThat(open).isEqualTo(new SettlementSnapshotService.StateView(room.getId(),"OPEN",null));
        assertThat(service.reopen(room.getId(),first.snapshotId(),owner.getId())).isEqualTo(open);

        updateItem(200);
        var replacement=roomService.addManualMember(room.getId(),owner.getId(),"replacement");
        assignmentService.replaceAssignees(room.getId(),receipt.getId(),item.getId(),owner.getId(),List.of(replacement.memberId()));
        roomService.deleteMember(room.getId(),debtor.getId(),owner.getId());
        var second=service.confirm(room.getId(),owner.getId(),confirmationRequest("second")).snapshot();

        assertThat(second.version()).isEqualTo(2);
        assertThat(second.snapshotId()).isNotEqualTo(first.snapshotId());
        assertThat(second.transfers()).singleElement().satisfies(transfer->{
            assertThat(transfer.amount()).isEqualTo(200);
            assertThat(transfer.status()).isEqualTo("PENDING");
            assertThat(transfer.updatedByUserId()).isNull();
            assertThat(transfer.updatedAt()).isNull();
        });
        var reopened=service.detail(room.getId(),first.snapshotId(),owner.getId());
        assertThat(reopened.status()).isEqualTo("REOPENED");
        assertThat(reopened.reopenedByUserId()).isEqualTo(owner.getId());
        assertThat(reopened.reopenedAt()).isNotNull();
        assertThat(reopened.members()).isEqualTo(historical.members());
        assertThat(reopened.members()).contains(new SettlementSnapshotService.MemberView(debtor.getId(),"debtor",0,100,-100));
        assertThat(reopened.transfers()).isEqualTo(historical.transfers());
        assertThat(service.confirm(room.getId(),owner.getId(),originalRequest).snapshot()).isEqualTo(reopened);
        assertThat(service.state(room.getId(),owner.getId()).currentSnapshotId()).isEqualTo(second.snapshotId());

        service.reopen(room.getId(),second.snapshotId(),owner.getId());
        assertThatThrownBy(()->service.reopen(room.getId(),first.snapshotId(),owner.getId()))
            .isInstanceOfSatisfying(SettlementRequestException.class,
                exception->assertThat(exception.reason()).isEqualTo("SETTLEMENT_VERSION_CONFLICT"));
        assertThat(service.reopen(room.getId(),second.snapshotId(),owner.getId())).isEqualTo(open);
        assertThat(count("settlement_snapshots")).isEqualTo(2);
    }

    @Test void completionWaitingForReopenCannotMutateHistory() throws Exception {
        var snapshot=service.confirm(room.getId(),owner.getId(),confirmationRequest("first")).snapshot();
        long transfer=snapshot.transfers().getFirst().transferId();
        var results=orderedRace(()->service.reopen(room.getId(),snapshot.snapshotId(),owner.getId()),
            ()->service.completion(room.getId(),snapshot.snapshotId(),transfer,owner.getId(),true));
        assertThat(results.get(1)).isEqualTo("SETTLEMENT_VERSION_CONFLICT");
        assertThat(service.detail(room.getId(),snapshot.snapshotId(),owner.getId()).transfers()).isEqualTo(snapshot.transfers());
    }

    @Test void reopenWaitingForCompletionPreservesTheCommittedAudit() throws Exception {
        var snapshot=service.confirm(room.getId(),owner.getId(),confirmationRequest("first")).snapshot();
        long transfer=snapshot.transfers().getFirst().transferId();
        var results=orderedRace(()->service.completion(room.getId(),snapshot.snapshotId(),transfer,owner.getId(),true),
            ()->service.reopen(room.getId(),snapshot.snapshotId(),owner.getId()));
        assertThat(results.get(0)).isInstanceOf(SettlementSnapshotService.TransferView.class);
        var historical=service.detail(room.getId(),snapshot.snapshotId(),owner.getId());
        assertThat(historical.transfers()).containsExactly((SettlementSnapshotService.TransferView)results.get(0));
        var second=service.confirm(room.getId(),owner.getId(),confirmationRequest("second")).snapshot();
        assertThat(second.transfers().getFirst().status()).isEqualTo("PENDING");
        assertThat(second.transfers().getFirst().updatedAt()).isNull();
    }

    @Test void completionRefreshesAuditLoadedBeforeWaitingForAnIdenticalUpdate() throws Exception {
        var snapshot=service.confirm(room.getId(),owner.getId(),confirmationRequest("first")).snapshot();
        long transfer=snapshot.transfers().getFirst().transferId();
        var results=orderedRace(()->service.completion(room.getId(),snapshot.snapshotId(),transfer,owner.getId(),true),
            ()->service.completion(room.getId(),snapshot.snapshotId(),transfer,owner.getId(),true),
            ()->assertThat(entityManager.find(SettlementTransfer.class,transfer).getStatus()).isEqualTo("PENDING"));
        assertThat(results.get(0)).isEqualTo(results.get(1));
    }

    private SettlementSnapshotService.ConfirmRequest confirmationRequest(String requestId) {
        return new SettlementSnapshotService.ConfirmRequest(requestId,service.preview(room.getId(),owner.getId()).previewToken());
    }

    private ItemService.ItemResult updateItem(long price) {
        return itemService.updateItem(room.getId(),receipt.getId(),item.getId(),owner.getId(),"item",price,1);
    }

    private List<Object> orderedRace(Supplier<?> first,Supplier<?> waiting) throws Exception {
        return orderedRace(first,waiting,()->{});
    }

    /** Prove the second operation is blocked by the first DB transaction before committing it. */
    private List<Object> orderedRace(Supplier<?> first,Supplier<?> waiting,Runnable beforeWaiting) throws Exception {
        CountDownLatch firstHasLock=new CountDownLatch(1),releaseFirst=new CountDownLatch(1);
        AtomicInteger firstPid=new AtomicInteger(),waitingPid=new AtomicInteger();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var firstFuture=pool.submit(()->transactionResult(()->{
                firstPid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));
                rooms.findByIdForUpdate(room.getId()).orElseThrow();
                Object result=first.get();
                firstHasLock.countDown();
                awaitLatch(releaseFirst);
                return result;
            }));
            Future<Object> waitingFuture;
            try {
                assertThat(firstHasLock.await(5,TimeUnit.SECONDS)).isTrue();
                waitingFuture=pool.submit(()->transactionResult(()->{
                    waitingPid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));
                    beforeWaiting.run();
                    return waiting.get();
                }));
                await().atMost(5,TimeUnit.SECONDS).untilAsserted(()->{
                    assertThat(waitingPid.get()).isPositive();
                    assertThat(jdbc.queryForObject("select ? = any(pg_blocking_pids(?))",Boolean.class,
                        firstPid.get(),waitingPid.get())).isTrue();
                });
            } finally {
                releaseFirst.countDown();
            }
            return List.of(firstFuture.get(15,TimeUnit.SECONDS),waitingFuture.get(15,TimeUnit.SECONDS));
        }
    }

    private Object transactionResult(Supplier<?> operation) {
        try {
            return new TransactionTemplate(transactionManager).execute(status->{
                jdbc.execute("set local lock_timeout = '10s'");
                return operation.get();
            });
        } catch(SettlementRequestException exception) {
            return exception.reason();
        } catch(ApiException exception) {
            return exception;
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(10,TimeUnit.SECONDS)).isTrue();
        } catch(InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
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
