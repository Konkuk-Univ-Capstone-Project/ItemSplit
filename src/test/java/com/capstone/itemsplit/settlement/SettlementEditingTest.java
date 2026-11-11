package com.capstone.itemsplit.settlement;

import com.capstone.itemsplit.assignment.*;
import com.capstone.itemsplit.item.*;
import com.capstone.itemsplit.receipt.*;
import com.capstone.itemsplit.room.*;
import com.capstone.itemsplit.ocr.*;
import com.capstone.itemsplit.ocr.engine.*;
import com.capstone.itemsplit.user.*;
import com.capstone.itemsplit.auth.JwtTokenProvider;
import com.capstone.itemsplit.common.exception.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.settlement.snapshots.enabled=true", "spring.datasource.url=jdbc:h2:mem:settlement_editing;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles({"test","ocr-demo"}) @AutoConfigureMockMvc
class SettlementEditingTest {
    @Autowired UserRepository users;
    @Autowired RoomRepository rooms;
    @Autowired RoomMemberRepository members;
    @Autowired ReceiptRepository receipts;
    @Autowired ItemRepository items;
    @Autowired AssignmentRepository assignments;
    @Autowired SettlementSnapshotRepository snapshots;
    @Autowired RoomSettlementStateRepository states;
    @Autowired RoomService roomService;
    @Autowired RoomShareService shares;
    @Autowired ReceiptService receiptService;
    @Autowired ReceiptImageService imageService;
    @Autowired ItemService itemService;
    @Autowired AssignmentService assignmentService;
    @Autowired OcrService ocrService;
    @Autowired OcrDraftService draftService;
    @Autowired OcrDemoService demoService;
    @Autowired OcrJobRepository jobs;
    @Autowired OcrDraftRepository drafts;
    @Autowired ReceiptImageRepository images;
    @Autowired OcrJobImageRepository jobImages;
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider jwt;
    @Autowired JdbcTemplate jdbc;
    User owner, guest, outsider;
    Room room;
    RoomMember payer, debtor, manual;
    Receipt receipt;
    Item item;
    OcrJob job;
    Long imageId,snapshotId;
    String invite;
    @BeforeEach void fixture() {
        owner=user();guest=user();outsider=user();
        room=rooms.save(Room.create("dinner",owner));
        payer=members.save(RoomMember.create(room,owner));debtor=members.save(RoomMember.create(room,guest));
        manual=members.save(RoomMember.createManual(room,"manual"));
        receipt=receipts.save(Receipt.createImageUpload(room,"receipt",owner,payer));
        item=items.save(Item.create(receipt,"rice",100,1));assignments.save(Assignment.create(item,debtor));
        var image=images.save(ReceiptImage.create(receipt,1,new com.capstone.itemsplit.storage.StorageService.StoredFile("receipts/"+room.getId()+"/image.png","image.png","image/png",10)));
        imageId=image.getId();
        job=jobs.save(OcrJob.create(receipt,"existing",owner.getId(),"test"));
        drafts.save(OcrDraft.create(job));jobImages.save(OcrJobImage.create(job,image));
        invite=roomService.issueInviteToken(room.getId(),owner.getId()).token();
        var snapshot=snapshots.saveAndFlush(SettlementSnapshot.create(room,1,owner.getId(),"initial","fingerprint","input",List.of(),List.of()));
        snapshotId=snapshot.getId();jdbc.update("insert into room_settlement_states(room_id,status,current_snapshot_id) values(?,'CONFIRMED',?)",room.getId(),snapshotId);
    }
    @ParameterizedTest
    @ValueSource(strings={"receiptCreate","receiptUpdate","contents","receiptDelete","imageCreate","imageAdd","itemAdd","itemUpdate","itemDelete","assign","ocrStart","ocrRetry","draftSave","draftApply","demo","memberAdd","memberRename","memberDelete","roomDelete","join","claim"})
    void allInputWritesRejectConfirmedRoom(String path) {
        long roomId=room.getId(),receiptId=receipt.getId(),userId=owner.getId();
        var commands=List.of(new ReceiptService.SaveItemCommand(item.getId(),"rice",100,1,List.of(debtor.getId()),false));
        assertThatThrownBy(()->{
            switch(path) {
                case "receiptCreate" -> receiptService.createManualReceipt(roomId,userId,"new","new",payer.getId(),null,List.of(new ReceiptService.SaveItemCommand(null,"rice",100,1,List.of(debtor.getId()),false)));
                case "receiptUpdate" -> receiptService.updateReceipt(roomId,receiptId,userId,"new",payer.getId(),null);
                case "contents" -> receiptService.saveContents(roomId,receiptId,userId,"new",payer.getId(),null,commands);
                case "receiptDelete" -> receiptService.deleteReceipt(roomId,receiptId,userId);
                case "imageCreate" -> imageService.createReceiptWithImage(roomId,userId,file(),"new");
                case "imageAdd" -> imageService.addImage(roomId,receiptId,userId,file());
                case "itemAdd" -> itemService.addItem(roomId,receiptId,userId,"new",100,1);
                case "itemUpdate" -> itemService.updateItem(roomId,receiptId,item.getId(),userId,"new",100,1);
                case "itemDelete" -> itemService.deleteItem(roomId,receiptId,item.getId(),userId);
                case "assign" -> assignmentService.replaceAssignees(roomId,receiptId,item.getId(),userId,List.of(payer.getId()));
                case "ocrStart" -> ocrService.start(roomId,receiptId,userId,"new");
                case "ocrRetry" -> ocrService.retry(roomId,receiptId,job.getId(),imageId,userId);
                case "draftSave" -> draftService.save(roomId,receiptId,job.getId(),userId,new OcrDraftService.SaveRequest(0L,List.of(),List.of()));
                case "draftApply" -> draftService.apply(roomId,receiptId,job.getId(),userId,new OcrDraftService.ApplyRequest(0L,true));
                case "demo" -> demoService.create(roomId,userId,"demo",List.of(FakeOcrScenario.NORMAL));
                case "memberAdd" -> roomService.addManualMember(roomId,userId,"new");
                case "memberRename" -> roomService.updateMemberName(roomId,debtor.getId(),userId,"new");
                case "memberDelete" -> roomService.deleteMember(roomId,debtor.getId(),userId);
                case "roomDelete" -> roomService.deleteRoom(roomId,userId);
                case "join" -> roomService.joinRoom(invite,outsider.getId(),null,"new");
                case "claim" -> roomService.joinRoom(invite,outsider.getId(),manual.getId(),"new");
                default -> throw new AssertionError(path);
            }
        }).isInstanceOfSatisfying(SettlementRequestException.class,e->assertThat(e.reason()).isEqualTo("SETTLEMENT_LOCKED"));
        assertThat(receipts.findById(receiptId)).isPresent();
        assertThat(items.findById(item.getId()).orElseThrow().getName()).isEqualTo("rice");
        assertThat(members.findAllByRoomId(roomId)).hasSize(3);
        assertThat(jdbc.queryForObject("select count(*) from ocr_daily_usage where quota_key like ?",Integer.class,"USER:"+userId+":%")).isZero();
    }
    @Test void lockedApiHasStructuredReasonAndAuthorizationStillPrecedesGuard() throws Exception {
        mvc.perform(delete("/api/rooms/{room}/receipts/{receipt}",room.getId(),receipt.getId()).header("Authorization",bearer(owner)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CONFLICT"))
            .andExpect(jsonPath("$.error.details[0].field").value("settlement"))
            .andExpect(jsonPath("$.error.details[0].reason").value("SETTLEMENT_LOCKED"));
        assertThatThrownBy(()->imageService.addImage(room.getId(),receipt.getId(),guest.getId(),file()))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(()->roomService.deleteRoom(room.getId(),guest.getId()))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(()->receiptService.updateReceipt(room.getId(),receipt.getId(),outsider.getId(),"new",payer.getId(),null))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }
    @ParameterizedTest
    @ValueSource(strings={"update","delete","contents"})
    void ocrItemEditorPermissionPrecedesSettlementGuard(String operation) {
        item.recordOcrSource(item.getId());items.save(item);
        assertThatThrownBy(()->{
            switch(operation) {
                case "update" -> itemService.updateItem(room.getId(),receipt.getId(),item.getId(),guest.getId(),"changed",100,1);
                case "delete" -> itemService.deleteItem(room.getId(),receipt.getId(),item.getId(),guest.getId());
                case "contents" -> receiptService.saveContents(room.getId(),receipt.getId(),guest.getId(),"receipt",payer.getId(),null,
                    List.of(new ReceiptService.SaveItemCommand(item.getId(),"changed",100,1,List.of(debtor.getId()),false)));
                default -> throw new AssertionError(operation);
            }
        }).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(items.findById(item.getId()).orElseThrow().getName()).isEqualTo("rice");
    }
    @Test void confirmedReadsInviteAndExistingMemberJoinRemainAvailable() throws Exception {
        for(String suffix:List.of("/members","/receipts","/receipts/"+receipt.getId(),"/receipts/"+receipt.getId()+"/images","/settlements"))
            mvc.perform(get("/api/rooms/"+room.getId()+suffix).header("Authorization",bearer(owner))).andExpect(status().isOk());
        assertThat(shares.issueShareToken(room.getId(),owner.getId()).readOnly()).isTrue();
        var reissued=roomService.issueInviteToken(room.getId(),owner.getId());
        assertThat(roomService.joinRoom(reissued.token(),owner.getId(),null,null).joined()).isFalse();
    }
    private User user(){return users.save(User.create(UUID.randomUUID()+"@test.com","encoded","tester"));}
    private MockMultipartFile file(){return new MockMultipartFile("file","demo.png","image/png",FakeClovaTransport.demoImage(FakeOcrScenario.NORMAL));}
    private String bearer(User user){return "Bearer "+jwt.createAccessToken(user).accessToken();}
}
