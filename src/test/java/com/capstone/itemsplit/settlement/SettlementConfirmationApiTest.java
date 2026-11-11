package com.capstone.itemsplit.settlement;

import com.capstone.itemsplit.auth.JwtTokenProvider;
import com.capstone.itemsplit.assignment.*;
import com.capstone.itemsplit.item.*;
import com.capstone.itemsplit.receipt.*;
import com.capstone.itemsplit.room.*;
import com.capstone.itemsplit.ocr.*;
import com.capstone.itemsplit.user.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.settlement.snapshots.enabled=true", "spring.datasource.url=jdbc:h2:mem:snapshot_api;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class SettlementConfirmationApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtTokenProvider jwt;
    @Autowired UserRepository users;
    @Autowired RoomRepository rooms;
    @Autowired RoomMemberRepository members;
    @Autowired ReceiptRepository receipts;
    @Autowired ItemRepository items;
    @Autowired AssignmentRepository assignments;
    @Autowired OcrJobRepository jobs;
    @Autowired OcrDraftRepository drafts;
    @Autowired ReceiptImageRepository receiptImages;
    @Autowired OcrJobImageRepository jobImages;
    @Autowired OcrViews ocrViews;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    User owner, guest, outsider;
    Room room;
    RoomMember payer, debtor;
    Receipt receipt;
    Item item;
    @BeforeEach void fixture() {
        String unique=UUID.randomUUID().toString();
        owner=users.save(User.create(unique+"@owner.test", "encoded", "owner"));
        guest=users.save(User.create(unique+"@guest.test", "encoded", "guest"));
        outsider=users.save(User.create(unique+"@out.test", "encoded", "outsider"));
        room=rooms.save(Room.create("dinner",owner));
        payer=members.save(RoomMember.create(room,owner));
        debtor=members.save(RoomMember.create(room,guest));
        receipt=receipts.save(Receipt.createManual(room,"food",payer,null));
        item=items.save(Item.create(receipt,"rice",10001,1));
        assignments.save(Assignment.create(item,debtor));
    }
    @Test void savesImmutableSnapshotAndIdempotentRetry() throws Exception {
        read("/settlement-state",owner).andExpect(jsonPath("$.data.status").value("OPEN"))
            .andExpect(jsonPath("$.data.currentSnapshotId").isEmpty());
        JsonNode preview=preview();
        assertThat(preview.path("ready").asBoolean()).isTrue();
        assertThat(preview.path("transfers").get(0).path("amount").asLong()).isEqualTo(10001);
        String token=preview.path("previewToken").asText();
        JsonNode saved=data(confirm(owner,"request-1",token).andExpect(status().isCreated()));
        long snapshot=saved.path("snapshotId").asLong();
        assertThat(snapshot).isPositive();
        assertThat(saved.path("version").asInt()).isEqualTo(1);
        assertThat(saved.path("transfers").get(0).path("status").asText()).isEqualTo("PENDING");
        assertThat(data(confirm(owner,"request-1",token).andExpect(status().isOk()))).isEqualTo(saved);
        read("/settlement-state",guest).andExpect(jsonPath("$.data.currentSnapshotId").value(snapshot));
        debtor.rename("renamed"); members.save(debtor);
        item.update("changed",12345,1); items.save(item);
        assignments.deleteAll(assignments.findAllByItemIdInWithUser(List.of(item.getId())));
        members.delete(debtor);
        assertThat(data(read("/settlement-snapshots/"+snapshot,owner))).isEqualTo(saved);
        confirm(owner,"request-1",token+"different").andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.details[0].reason").value("IDEMPOTENCY_KEY_REUSED"));
    }
    @Test void sameTotalInputChangesInvalidatePreview() throws Exception {
        String token=preview().path("previewToken").asText();
        item.update("renamed only",10001,1); items.save(item);
        confirm(owner,"changed",token).andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.details[0].reason").value("SETTLEMENT_INPUT_CHANGED"));
        token=preview().path("previewToken").asText();
        debtor.rename("new name"); members.save(debtor);
        confirm(owner,"renamed",token).andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.details[0].reason").value("SETTLEMENT_INPUT_CHANGED"));
    }
    @Test void sameTotalAssignmentAndPayerChangesInvalidatePreview() throws Exception {
        String token=preview().path("previewToken").asText();
        assignments.deleteAll(assignments.findAllByItemIdInWithUser(List.of(item.getId())));
        assignments.save(Assignment.create(item,payer));
        confirm(owner,"assignments",token).andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.details[0].reason").value("SETTLEMENT_INPUT_CHANGED"));
        token=preview().path("previewToken").asText();
        receipt.update("food",debtor,null);receipts.save(receipt);
        confirm(owner,"payer",token).andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.details[0].reason").value("SETTLEMENT_INPUT_CHANGED"));
    }
    @Test void reopenedRetryReturnsOldSnapshotAndOldGenerationTokenCannotConfirm() throws Exception {
        String token=preview().path("previewToken").asText();
        JsonNode original=data(confirm(owner,"original",token).andExpect(status().isCreated()));
        long id=original.path("snapshotId").asLong();
        mvc.perform(post("/api/rooms/{room}/settlement-snapshots/{id}/reopen",room.getId(),id)
            .header("Authorization",bearer(owner))).andExpect(status().isOk());
        JsonNode retry=data(confirm(owner,"original",token).andExpect(status().isOk()));
        assertThat(retry.path("snapshotId").asLong()).isEqualTo(id);
        assertThat(retry.path("status").asText()).isEqualTo("REOPENED");
        read("/settlement-state",owner).andExpect(jsonPath("$.data.status").value("OPEN"));
        confirm(owner,"new-old-token",token).andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.details[0].reason").value("SETTLEMENT_INPUT_CHANGED"));
        confirm(owner,"new",preview().path("previewToken").asText()).andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.version").value(2));
    }
    @Test void authorizationAndRoomBinding() throws Exception {
        String token=preview().path("previewToken").asText();
        read("/settlements/confirmation-preview",outsider).andExpect(status().isForbidden());
        read("/settlement-state",outsider).andExpect(status().isForbidden());
        confirm(guest,"member",token).andExpect(status().isForbidden());
        mvc.perform(get("/api/rooms/{id}/settlement-state",room.getId())).andExpect(status().isUnauthorized());
        long snapshot=data(confirm(owner,"owner",token).andExpect(status().isCreated())).path("snapshotId").asLong();
        Room another=rooms.save(Room.create("other",owner)); members.save(RoomMember.create(another,owner));
        mvc.perform(get("/api/rooms/{room}/settlement-snapshots/{id}",another.getId(),snapshot)
            .header("Authorization",bearer(owner))).andExpect(status().isNotFound());
        mvc.perform(post("/api/rooms/{room}/settlement-snapshots",another.getId()).header("Authorization",bearer(owner))
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("requestId","other","previewToken",token))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.details[0].reason").value("SETTLEMENT_INPUT_CHANGED"));
    }
    @Test void missingAssignmentsAndActiveOcrPreventConfirmation() throws Exception {
        assignments.deleteAll(assignments.findAllByItemIdInWithUser(List.of(item.getId())));
        JsonNode preview=preview();
        assertThat(preview.path("ready").asBoolean()).isFalse();
        confirm(owner,"invalid",preview.path("previewToken").asText()).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.details[0].reason").value("SETTLEMENT_NOT_READY"));
        assignments.save(Assignment.create(item,debtor));
        jobs.save(OcrJob.create(receipt,"ocr",owner.getId(),"test"));
        preview=preview();
        assertThat(preview.path("ready").asBoolean()).isFalse();
        confirm(owner,"ocr",preview.path("previewToken").asText()).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.details[0].reason").value("SETTLEMENT_NOT_READY"));
    }
    @Test void pendingDraftBlocksButAppliedDraftDoesNot() throws Exception {
        OcrJob job=jobs.save(OcrJob.create(receipt,"ocr",owner.getId(),"test"));
        jdbc.update("update ocr_jobs set status='REVIEWABLE' where id=?",job.getId());
        jdbc.update("insert into ocr_drafts(job_id,receipt_id,status,revision,created_at,updated_at) values(?,?,'EDITING',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",job.getId(),receipt.getId());
        assertThat(preview().path("ready").asBoolean()).isFalse();
        jdbc.update("update ocr_drafts set status='APPLIED',applied_revision=0,applied_at=CURRENT_TIMESTAMP,applied_by_user_id=? where job_id=?",owner.getId(),job.getId());
        JsonNode preview=preview();
        assertThat(preview.path("ready").asBoolean()).isTrue();
        confirm(owner,"applied",preview.path("previewToken").asText()).andExpect(status().isCreated());
    }
    @Test void uncoveredFailedPhotoBlocksButCoveredAppliedDraftWithTotalWarningCanConfirm() throws Exception {
        OcrJob job=jobs.save(OcrJob.create(receipt,"ocr",owner.getId(),"test"));
        var first=receiptImages.save(ReceiptImage.create(receipt,1,new com.capstone.itemsplit.storage.StorageService.StoredFile("test/failed.png","failed.png","image/png",10)));
        var second=receiptImages.save(ReceiptImage.create(receipt,2,new com.capstone.itemsplit.storage.StorageService.StoredFile("test/success.png","success.png","image/png",10)));
        var failed=jobImages.save(OcrJobImage.create(job,first));
        var success=jobImages.save(OcrJobImage.create(job,second));
        var draft=drafts.save(OcrDraft.create(job));
        jdbc.update("update ocr_jobs set status='PARTIALLY_FAILED' where id=?",job.getId());
        jdbc.update("update ocr_job_images set status='FAILED' where id=?",failed.getId());
        jdbc.update("update ocr_job_images set status='SUCCEEDED',total_amount=20000 where id=?",success.getId());
        jdbc.update("insert into ocr_draft_items(draft_id,row_index,sort_order,name,price,quantity,excluded_from_settlement,removed) values(?,0,0,'reviewed rice',10001,1,false,false)",draft.getId());
        jdbc.update("update ocr_drafts set status='APPLIED',applied_revision=0,applied_at=CURRENT_TIMESTAMP,applied_by_user_id=? where id=?",owner.getId(),draft.getId());
        var warning=new org.springframework.transaction.support.TransactionTemplate(transactions).execute(tx->ocrViews.view(jobs.findById(job.getId()).orElseThrow()).draft().warnings());
        assertThat(warning).contains("OCR_TOTAL_MISMATCH");
        JsonNode blocked=preview();
        assertThat(blocked.path("ready").asBoolean()).isFalse();
        confirm(owner,"uncovered",blocked.path("previewToken").asText()).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.details[0].reason").value("SETTLEMENT_NOT_READY"));
        jdbc.update("update ocr_job_images set manually_covered=true where id=?",failed.getId());
        JsonNode ready=preview();
        assertThat(ready.path("ready").asBoolean()).isTrue();
        confirm(owner,"covered",ready.path("previewToken").asText()).andExpect(status().isCreated());
    }
    @Test void reopenReturnsOpenAndProtectsHistory() throws Exception {
        long id=data(confirm(owner,"first",preview().path("previewToken").asText()).andExpect(status().isCreated())).path("snapshotId").asLong();
        mvc.perform(post("/api/rooms/{room}/settlement-snapshots/{id}/reopen",room.getId(),id).header("Authorization",bearer(guest))).andExpect(status().isForbidden());
        mvc.perform(post("/api/rooms/{room}/settlement-snapshots/{id}/reopen",room.getId(),id).header("Authorization",bearer(outsider))).andExpect(status().isForbidden());
        mvc.perform(post("/api/rooms/{room}/settlement-snapshots/{id}/reopen",room.getId(),id)).andExpect(status().isUnauthorized());
        Room other=rooms.save(Room.create("other",owner));members.save(RoomMember.create(other,owner));
        mvc.perform(post("/api/rooms/{room}/settlement-snapshots/{id}/reopen",other.getId(),id).header("Authorization",bearer(owner))).andExpect(status().isNotFound());
        jdbc.update("update settlement_transfers set status='COMPLETED',updated_by_user_id=?,updated_at=CURRENT_TIMESTAMP where snapshot_id=?",owner.getId(),id);
        mvc.perform(post("/api/rooms/{room}/settlement-snapshots/{id}/reopen",room.getId(),id).header("Authorization",bearer(owner)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("OPEN")).andExpect(jsonPath("$.data.currentSnapshotId").isEmpty());
        JsonNode reopened=data(read("/settlement-snapshots/"+id,owner));
        assertThat(reopened.path("status").asText()).isEqualTo("REOPENED");
        assertThat(reopened.path("reopenedByUserId").asLong()).isEqualTo(owner.getId());
        assertThat(java.time.LocalDateTime.parse(reopened.path("reopenedAt").asText()).getNano()%1000).isZero();
        mvc.perform(post("/api/rooms/{room}/settlement-snapshots/{id}/reopen",room.getId(),id).header("Authorization",bearer(owner))).andExpect(status().isOk());
        assertThat(data(read("/settlement-snapshots/"+id,owner))).isEqualTo(reopened);
        assertThat(reopened.path("transfers").get(0).path("status").asText()).isEqualTo("COMPLETED");
        JsonNode second=data(confirm(owner,"second",preview().path("previewToken").asText()).andExpect(status().isCreated()).andExpect(jsonPath("$.data.version").value(2)));
        assertThat(second.path("transfers").get(0).path("status").asText()).isEqualTo("PENDING");
        mvc.perform(post("/api/rooms/{room}/settlement-snapshots/{id}/reopen",room.getId(),id).header("Authorization",bearer(owner)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.details[0].reason").value("SETTLEMENT_VERSION_CONFLICT"));
        mvc.perform(post("/api/rooms/{room}/settlement-snapshots/{id}/reopen",room.getId(),second.path("snapshotId").asLong()).header("Authorization",bearer(owner))).andExpect(status().isOk());
        mvc.perform(post("/api/rooms/{room}/settlement-snapshots/{id}/reopen",room.getId(),id).header("Authorization",bearer(owner))).andExpect(status().isConflict());
        assertThat(data(read("/settlement-snapshots/"+id,owner))).isEqualTo(reopened);
    }
    @Test void confirmedReceiptDeletionIsStructuredConflict() throws Exception {
        confirm(owner,"first",preview().path("previewToken").asText()).andExpect(status().isCreated());
        mvc.perform(delete("/api/rooms/{room}/receipts/{receipt}",room.getId(),receipt.getId()).header("Authorization",bearer(owner)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.details[0].reason").value("SETTLEMENT_LOCKED"));
    }
    @Test void confirmedRoomRejectsAnotherRequest() throws Exception {
        String token=preview().path("previewToken").asText();
        confirm(owner,"first",token).andExpect(status().isCreated());
        confirm(owner,"second",token).andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.details[0].reason").value("SETTLEMENT_VERSION_CONFLICT"));
    }
    @Test void missingRequestFieldsHaveStructuredReason() throws Exception {
        confirm(owner,"","").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.details[0].reason").value("SETTLEMENT_NOT_READY"));
    }
    @Test void completionIsExplicitIdempotentAndKeepsCancellationAudit() throws Exception {
        JsonNode snapshot=data(confirm(owner,"completion",preview().path("previewToken").asText()).andExpect(status().isCreated()));
        long id=snapshot.path("snapshotId").asLong(), transfer=snapshot.path("transfers").get(0).path("transferId").asLong();
        JsonNode pending=data(completion(id,transfer,owner,"{\"completed\":false}").andExpect(status().isOk()));
        assertThat(pending.path("updatedAt").isNull()).isTrue();
        JsonNode completed=data(completion(id,transfer,owner,"{\"completed\":true}").andExpect(status().isOk()));
        assertThat(completed.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(completed.path("updatedByUserId").asLong()).isEqualTo(owner.getId());
        assertThat(java.time.LocalDateTime.parse(completed.path("updatedAt").asText()).getNano()%1000).isZero();
        assertThat(data(completion(id,transfer,owner,"{\"completed\":true}").andExpect(status().isOk()))).isEqualTo(completed);
        JsonNode cancelled=data(completion(id,transfer,owner,"{\"completed\":false}").andExpect(status().isOk()));
        assertThat(cancelled.path("status").asText()).isEqualTo("PENDING");
        assertThat(cancelled.path("updatedByUserId").asLong()).isEqualTo(owner.getId());
        assertThat(cancelled.path("updatedAt").isNull()).isFalse();
        assertThat(data(completion(id,transfer,owner,"{\"completed\":false}").andExpect(status().isOk()))).isEqualTo(cancelled);
    }
    @Test void completionValidatesPayloadAuthorityScopeAndHistoricalVersion() throws Exception {
        JsonNode snapshot=data(confirm(owner,"completion",preview().path("previewToken").asText()).andExpect(status().isCreated()));
        long id=snapshot.path("snapshotId").asLong(), transfer=snapshot.path("transfers").get(0).path("transferId").asLong();
        for(String body:List.of("{}","{\"completed\":null}","{\"completed\":\"true\"}","{\"completed\":1}","null","[]"))
            completion(id,transfer,owner,body).andExpect(status().isBadRequest());
        completion(id,transfer,guest,"{\"completed\":true}").andExpect(status().isForbidden());
        completion(id,transfer,outsider,"{\"completed\":true}").andExpect(status().isForbidden());
        mvc.perform(put("/api/rooms/{room}/settlement-snapshots/{id}/transfers/{transfer}/completion",room.getId(),id,transfer)
            .contentType(MediaType.APPLICATION_JSON).content("{\"completed\":true}")).andExpect(status().isUnauthorized());
        completion(id,Long.MAX_VALUE,owner,"{\"completed\":true}").andExpect(status().isNotFound());
        Room other=rooms.save(Room.create("other",owner));members.save(RoomMember.create(other,owner));
        mvc.perform(put("/api/rooms/{room}/settlement-snapshots/{id}/transfers/{transfer}/completion",other.getId(),id,transfer)
            .header("Authorization",bearer(owner)).contentType(MediaType.APPLICATION_JSON).content("{\"completed\":true}")).andExpect(status().isNotFound());
        JsonNode completed=data(completion(id,transfer,owner,"{\"completed\":true}").andExpect(status().isOk()));
        mvc.perform(post("/api/rooms/{room}/settlement-snapshots/{id}/reopen",room.getId(),id).header("Authorization",bearer(owner))).andExpect(status().isOk());
        completion(id,transfer,owner,"{\"completed\":false}").andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.details[0].reason").value("SETTLEMENT_VERSION_CONFLICT"));
        JsonNode second=data(confirm(owner,"second",preview().path("previewToken").asText()).andExpect(status().isCreated()));
        assertThat(second.path("transfers").get(0).path("status").asText()).isEqualTo("PENDING");
        completion(second.path("snapshotId").asLong(),transfer,owner,"{\"completed\":true}").andExpect(status().isNotFound());
        completion(id,transfer,owner,"{\"completed\":true}").andExpect(status().isConflict());
        assertThat(data(read("/settlement-snapshots/"+id,owner)).path("transfers").get(0)).isEqualTo(completed);
    }
    ResultActions completion(long snapshot,long transfer,User user,String body) throws Exception {
        return mvc.perform(put("/api/rooms/{room}/settlement-snapshots/{snapshot}/transfers/{transfer}/completion",room.getId(),snapshot,transfer)
            .header("Authorization",bearer(user)).contentType(MediaType.APPLICATION_JSON).content(body));
    }
    JsonNode preview() throws Exception {return data(read("/settlements/confirmation-preview",owner).andExpect(status().isOk()));}
    ResultActions read(String suffix,User user) throws Exception {return mvc.perform(get("/api/rooms/"+room.getId()+suffix).header("Authorization",bearer(user)));}
    ResultActions confirm(User user,String request,String token) throws Exception {
        return mvc.perform(post("/api/rooms/{id}/settlement-snapshots",room.getId()).header("Authorization",bearer(user))
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("requestId",request,"previewToken",token))));
    }
    JsonNode data(ResultActions result) throws Exception {return mapper.readTree(result.andReturn().getResponse().getContentAsString()).path("data");}
    String bearer(User user) {return "Bearer "+jwt.createAccessToken(user).accessToken();}
}
