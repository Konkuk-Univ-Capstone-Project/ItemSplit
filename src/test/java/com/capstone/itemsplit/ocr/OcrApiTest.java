package com.capstone.itemsplit.ocr;

import com.capstone.itemsplit.auth.JwtTokenProvider;
import com.capstone.itemsplit.room.*;
import com.capstone.itemsplit.user.*;
import com.capstone.itemsplit.receipt.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:ocr-api;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc @ActiveProfiles({"test", "ocr-demo"})
class OcrApiTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RoomRepository rooms;
    @Autowired RoomMemberRepository members;
    @Autowired ReceiptRepository receipts;
    @Autowired ReceiptImageRepository receiptImages;
    @Autowired JwtTokenProvider tokens;
    @Autowired ObjectMapper json;
    @Autowired OcrService service;
    @Autowired OcrJobRepository jobs;
    @Autowired OcrDraftItemRepository draftItems;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.capstone.itemsplit.storage.StorageService storage;

    @Test void uploadedImageUsesSharedStorageAndFreezesImagesAfterOcrStarts() throws Exception {
        Actor a=actor();
        byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aP1kAAAAASUVORK5CYII=");
        var upload=new org.springframework.mock.web.MockMultipartFile("file","receipt.png","image/png",png);
        var response=mvc.perform(multipart("/api/rooms/"+a.room().getId()+"/receipts/image").file(upload)
            .header("Authorization",a.token())).andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.createdByUserId").value(a.user().getId()))
            .andReturn().getResponse().getContentAsString();
        long id=json.readTree(response).at("/data/receiptId").asLong();
        var image=receiptImages.findAllByReceiptId(id).getFirst();
        assertThat(storage.read(image.getStoredPath())).isEqualTo(png);
        start(a,id,"actual-upload");
        var done=complete(a,id);
        assertThat(done.path("status").asText()).isEqualTo("REVIEWABLE");
        assertThat(done.at("/draft/itemsTotal").asLong()).isEqualTo(18000);
        mvc.perform(multipart("/api/rooms/"+a.room().getId()+"/receipts/"+id+"/images").file(upload)
            .header("Authorization",a.token())).andExpect(status().isConflict());
        assertThat(receiptImages.countByReceiptId(id)).isEqualTo(1);
        mvc.perform(delete("/api/rooms/"+a.room().getId()+"/receipts/"+id).header("Authorization",a.token()))
            .andExpect(status().isNoContent());
        assertThatThrownBy(()->storage.read(image.getStoredPath())).isInstanceOf(java.io.IOException.class);
    }

    @Test void syntheticImageToParsedDatabaseDraftWithoutChangingItems() throws Exception {
        Actor a=actor(); long id=demo(a,"NORMAL");
        mvc.perform(get(path(a,id)+"/latest").header("Authorization",a.token())).andExpect(jsonPath("$.data").doesNotExist());
        var result=start(a,id,"one");long jobId=result.path("jobId").asLong();
        var done=complete(a,id);
        assertThat(done.path("jobId").asLong()).isEqualTo(jobId);
        assertThat(done.path("status").asText()).isEqualTo("REVIEWABLE");
        assertThat(done.path("provider").asText()).isEqualTo("FAKE_CLOVA_RECEIPT");
        assertThat(done.at("/draft/items").size()).isEqualTo(2);
        assertThat(done.at("/draft/itemsTotal").asLong()).isEqualTo(18000);
        assertThat(done.at("/draft/items/0/price").asLong()).isEqualTo(8000);
        assertThat(done.at("/draft/items/0/quantity").asInt()).isEqualTo(2);
        assertThat(done.at("/images/0/attemptCount").asInt()).isEqualTo(1);
        assertThat(done.toString()).doesNotContain("storedPath","ocr-fixture/","cardNumber");
        assertThat(jdbc.queryForObject("select count(*) from items where receipt_id=?",Integer.class,id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ocr_draft_items where draft_id=?",Integer.class,done.at("/draft/draftId").asLong())).isEqualTo(2);
        var again=start(a,id,"one"); assertThat(again.path("jobId").asLong()).isEqualTo(jobId);
        mvc.perform(post(path(a,id)).header("Authorization",a.token()).contentType("application/json").content("{\"requestId\":\"different\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.details[0].reason").value("JOB_ALREADY_EXISTS"));
    }
    @Test void missingValuesStayNullAndMismatchIsOnlyAWarning() throws Exception {
        Actor a=actor();long id=demo(a,"MISSING_VALUES");start(a,id,"missing");var done=complete(a,id);
        assertThat(done.path("status").asText()).isEqualTo("REVIEWABLE");
        assertThat(done.at("/draft/blockers").toString()).contains("ITEM_ISSUES");
        assertThat(done.at("/draft/items").toString()).contains("MISSING_PRICE","MISSING_QUANTITY");
        assertThat(done.at("/draft/items/0/price").isNull()).isTrue();
        long mismatch=demo(a,"TOTAL_MISMATCH");start(a,mismatch,"mismatch");var other=complete(a,mismatch);
        assertThat(other.at("/draft/warnings").toString()).contains("OCR_TOTAL_MISMATCH");
        assertThat(other.at("/draft/itemsTotal").asLong()).isEqualTo(18000);
        assertThat(other.at("/draft/blockers").isEmpty()).isTrue();
    }
    @Test void partialFailuresKeepSuccessfulRowsAndRetryCannotDuplicateThem() throws Exception {
        Actor a=actor();long id=demo(a,"NORMAL","TIMEOUT");start(a,id,"partial");var done=complete(a,id);
        assertThat(done.path("status").asText()).isEqualTo("PARTIALLY_FAILED");
        assertThat(done.at("/images/1/errorCode").asText()).isEqualTo("TIMEOUT");
        long image=done.at("/images/1/imageId").asLong();long job=done.path("jobId").asLong();
        for(int attempt=2;attempt<=3;attempt++) {
            mvc.perform(post(path(a,id)+"/"+job+"/images/"+image+"/retry").header("Authorization",a.token())).andExpect(status().isAccepted());
            done=complete(a,id);assertThat(done.at("/images/1/attemptCount").asInt()).isEqualTo(attempt);
            assertThat(done.at("/draft/items").size()).isEqualTo(2);
        }
        mvc.perform(post(path(a,id)+"/"+job+"/images/"+image+"/retry").header("Authorization",a.token()))
            .andExpect(status().isTooManyRequests());
    }
    @Test void providerAndStorageErrorsArePersistedWithoutLeakingBodies() throws Exception {
        Actor a=actor();
        for(var entry:Map.of("MALFORMED_RESPONSE","PROVIDER_ERROR","RECOGNITION_FAILED","RECOGNITION_FAILED","IMAGE_UNAVAILABLE","IMAGE_UNAVAILABLE").entrySet()) {
            long id=demo(a,entry.getKey());start(a,id,entry.getKey());var done=complete(a,id);
            assertThat(done.path("status").asText()).isEqualTo("FAILED");
            assertThat(done.at("/images/0/errorCode").asText()).isEqualTo(entry.getValue());
            assertThat(done.at("/draft/items").isEmpty()).isTrue();
            if(entry.getKey().equals("RECOGNITION_FAILED"))mvc.perform(post(path(a,id)+"/"+done.path("jobId").asLong()+"/images/"+done.at("/images/0/imageId").asLong()+"/retry")
                .header("Authorization",a.token())).andExpect(status().isConflict());
        }
    }
    @Test void authorizationAndPathScopeAreChecked() throws Exception {
        Actor a=actor();long id=demo(a,"NORMAL");Actor outsider=actor();
        mvc.perform(post(path(a,id)).contentType("application/json").content("{\"requestId\":\"a\"}")).andExpect(status().isUnauthorized());
        mvc.perform(get(path(a,id)+"/latest").header("Authorization",outsider.token())).andExpect(status().isForbidden());
        User teammate=users.save(User.create(UUID.randomUUID()+"@test.com","pw","member"));members.save(RoomMember.create(a.room(),teammate));
        String token="Bearer "+tokens.createAccessToken(teammate).accessToken();
        mvc.perform(post(path(a,id)).header("Authorization",token).contentType("application/json").content("{\"requestId\":\"a\"}"))
            .andExpect(status().isForbidden());
        start(a,id,"owner");var done=complete(a,id);
        mvc.perform(get(path(a,id)+"/latest").header("Authorization",token)).andExpect(status().isOk());
        long other=demo(a,"NORMAL");
        mvc.perform(get(path(a,other)+"/"+done.path("jobId").asLong()).header("Authorization",a.token())).andExpect(status().isNotFound());
        mvc.perform(post(path(a,id)).header("Authorization",a.token()).contentType("application/json").content("{\"requestId\":\" \"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/rooms/"+a.room().getId()+"/ocr-demo/receipts").header("Authorization",a.token()).contentType("application/json")
            .content("{\"name\":\"demo\",\"scenarios\":[\"UNKNOWN\"]}")).andExpect(status().isBadRequest());
    }
    @Test void concurrentStartsReturnTheSameJob() throws Exception {
        Actor a=actor();long id=demo(a,"NORMAL");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var f1=pool.submit(()->service.start(a.room().getId(),id,a.user().getId(),"same"));
            var f2=pool.submit(()->service.start(a.room().getId(),id,a.user().getId(),"same"));
            assertThat(f1.get(10,TimeUnit.SECONDS).jobId()).isEqualTo(f2.get(10,TimeUnit.SECONDS).jobId());
        }
        var done=complete(a,id);assertThat(done.at("/draft/items").size()).isEqualTo(2);
        assertThat(done.at("/images/0/attemptCount").asInt()).isEqualTo(1);
    }
    @Test void deletingReceiptOrRoomCleansOcrRows() throws Exception {
        for(boolean roomDelete:List.of(false,true)) {
            Actor a=actor();long id=demo(a,"NORMAL");start(a,id,"cleanup");var done=complete(a,id);
            long job=done.path("jobId").asLong(),draft=done.at("/draft/draftId").asLong();
            mvc.perform(delete("/api/rooms/"+a.room().getId()+(roomDelete?"":"/receipts/"+id)).header("Authorization",a.token()))
                .andExpect(status().isNoContent());
            assertThat(jobs.findById(job)).isEmpty();
            assertThat(draftItems.findAllByDraftIdOrderBySortOrder(draft)).isEmpty();
            assertThat(receiptImages.findAllByReceiptId(id)).isEmpty();
        }
    }
    @Test void reviewedDraftAppendsItemsAndAssignmentsOnceAndSettlesWithoutDeclaredTotal() throws Exception {
        Actor a=actor(); long id=demo(a,"TOTAL_MISMATCH"); start(a,id,"apply"); var job=complete(a,id);
        long member=members.findAllByRoomId(a.room().getId()).getFirst().getId();
        var payload=draftPayload(job,member);
        String draftPath=path(a,id)+"/"+job.path("jobId").asLong()+"/draft";
        var saved=json.readTree(mvc.perform(put(draftPath).header("Authorization",a.token()).contentType("application/json")
            .content(json.writeValueAsString(payload))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        long revision=saved.at("/draft/revision").asLong();
        assertThat(revision).isGreaterThan(job.at("/draft/revision").asLong());
        mvc.perform(put(draftPath).header("Authorization",a.token()).contentType("application/json")
            .content(json.writeValueAsString(payload))).andExpect(status().isConflict());
        String body=json.writeValueAsString(Map.of("revision",revision,"reviewConfirmed",true));
        for(int i=0;i<2;i++) mvc.perform(post(draftPath+"/apply").header("Authorization",a.token()).contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(2));
        assertThat(jdbc.queryForObject("select count(*) from items where receipt_id=?",Integer.class,id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from assignments where item_id in (select id from items where receipt_id=?)",Integer.class,id)).isEqualTo(2);
        assertThat(latest(a,id).at("/draft/status").asText()).isEqualTo("APPLIED");
        mvc.perform(get("/api/rooms/"+a.room().getId()+"/settlements").header("Authorization",a.token()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.ready").value(true))
            .andExpect(jsonPath("$.data.members[0].burden").value(18000));
        mvc.perform(put(draftPath).header("Authorization",a.token()).contentType("application/json")
            .content(json.writeValueAsString(payload))).andExpect(status().isConflict());
    }

    @Test void applyRequiresOwnerCurrentRevisionReviewAndValidDraft() throws Exception {
        Actor a=actor();long id=demo(a,"MISSING_VALUES");start(a,id,"invalid");var job=complete(a,id);
        String url=path(a,id)+"/"+job.path("jobId").asLong()+"/draft/apply";
        long revision=job.at("/draft/revision").asLong();
        User teammate=users.save(User.create(UUID.randomUUID()+"@test.com","pw","teammate"));members.save(RoomMember.create(a.room(),teammate));
        String teammateToken="Bearer "+tokens.createAccessToken(teammate).accessToken();
        mvc.perform(post(url).header("Authorization",teammateToken).contentType("application/json")
            .content(json.writeValueAsString(Map.of("revision",revision,"reviewConfirmed",true)))).andExpect(status().isForbidden());
        for(var body:List.of(Map.of("revision",revision+1,"reviewConfirmed",true),Map.of("revision",revision,"reviewConfirmed",false),
            Map.of("revision",revision,"reviewConfirmed",true))) {
            mvc.perform(post(url).header("Authorization",a.token()).contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().is4xxClientError());
        }
        assertThat(jdbc.queryForObject("select count(*) from items where receipt_id=?",Integer.class,id)).isZero();
    }

    @Test void manualRecoveryOfFailedImageAndRemovedInvalidRowsCanBeApplied() throws Exception {
        Actor a=actor();long id=demo(a,"MISSING_VALUES","TIMEOUT");start(a,id,"recover");var job=complete(a,id);
        String url=path(a,id)+"/"+job.path("jobId").asLong()+"/draft";
        var payload=json.createObjectNode().put("revision",job.at("/draft/revision").asLong());
        payload.putArray("coveredImageIds").add(job.at("/images/1/imageId").asLong());
        var rows=payload.putArray("items");
        for(var old:job.at("/draft/items")) {
            var row=rows.addObject().put("draftItemId",old.path("draftItemId").asLong()).put("removed",true);
            row.putArray("memberIds");
        }
        rows.addObject().put("name","수동 복구").put("price",5000).put("quantity",2).putArray("memberIds");
        var saved=json.readTree(mvc.perform(put(url).header("Authorization",a.token()).contentType("application/json").content(payload.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.draft.itemsTotal").value(10000))
            .andReturn().getResponse().getContentAsString()).path("data");
        mvc.perform(post(url+"/apply").header("Authorization",a.token()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("revision",saved.at("/draft/revision").asLong(),"reviewConfirmed",true))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(get("/api/rooms/"+a.room().getId()+"/settlements").header("Authorization",a.token()))
            .andExpect(jsonPath("$.data.ready").value(false)).andExpect(jsonPath("$.data.issues[0].code").value("MISSING_ASSIGNEES"));
        mvc.perform(post(path(a,id)+"/"+job.path("jobId").asLong()+"/images/"+job.at("/images/1/imageId").asLong()+"/retry")
            .header("Authorization",a.token())).andExpect(status().isConflict());
    }

    @Test void concurrentApplyAppendsAndPreservesExistingItemsAndMetadata() throws Exception {
        Actor a=actor();long id=demo(a,"NORMAL");start(a,id,"concurrent-apply");var job=complete(a,id);
        jdbc.update("insert into items(receipt_id,name,price,quantity,excluded_from_settlement) values(?,?,?,?,?)",id,"기존 수기 품목",100,1,true);
        jdbc.execute("alter table receipts add column if not exists declared_total bigint");
        jdbc.update("update receipts set name=?,declared_total=? where id=?","사용자가 입력한 이름",100,id);
        String url=path(a,id)+"/"+job.path("jobId").asLong()+"/draft/apply";
        String body=json.writeValueAsString(Map.of("revision",job.at("/draft/revision").asLong(),"reviewConfirmed",true));
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Integer> request=()->mvc.perform(post(url).header("Authorization",a.token()).contentType("application/json").content(body))
                .andReturn().getResponse().getStatus();
            var f1=pool.submit(request);var f2=pool.submit(request);
            assertThat(f1.get(10,TimeUnit.SECONDS)).isEqualTo(200);assertThat(f2.get(10,TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(jdbc.queryForObject("select count(*) from items where receipt_id=?",Integer.class,id)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select declared_total from receipts where id=?", Long.class, id)).isEqualTo(100);
        assertThat(receipts.findById(id).orElseThrow().getName()).isEqualTo("사용자가 입력한 이름");
    }

    @Test void foreignMembersAndExcessTotalsAreRejectedWithoutPartialWrites() throws Exception {
        Actor a=actor();Actor other=actor();long id=demo(a,"NORMAL");start(a,id,"limits");var job=complete(a,id);
        String url=path(a,id)+"/"+job.path("jobId").asLong()+"/draft";
        long member=members.findAllByRoomId(other.room().getId()).getFirst().getId();
        mvc.perform(put(url).header("Authorization",a.token()).contentType("application/json").content(json.writeValueAsString(draftPayload(job,member))))
            .andExpect(status().isBadRequest());
        jdbc.update("insert into items(receipt_id,name,price,quantity,excluded_from_settlement) values(?,?,?,?,?)",id,"한도 품목",1000000,10,true);
        mvc.perform(post(url+"/apply").header("Authorization",a.token()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("revision",job.at("/draft/revision").asLong(),"reviewConfirmed",true))))
            .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from items where receipt_id=?",Integer.class,id)).isEqualTo(1);
        assertThat(latest(a,id).at("/draft/status").asText()).isEqualTo("EDITING");
    }

    private Map<String,Object> draftPayload(JsonNode job,long member) {
        List<Map<String,Object>> lines=new ArrayList<>();
        for(var line:job.at("/draft/items")) lines.add(Map.of("draftItemId",line.path("draftItemId").asLong(),
            "name",line.path("name").asText(),"price",line.path("price").asLong(),"quantity",line.path("quantity").asInt(),
            "memberIds",List.of(member),"excludedFromSettlement",false,"removed",false));
        return Map.of("revision",job.at("/draft/revision").asLong(),"items",lines,"coveredImageIds",List.of());
    }

    private Actor actor() {
        User user=users.save(User.create(UUID.randomUUID()+"@test.com","password","tester"));
        Room room=rooms.save(Room.create("OCR test",user));members.save(RoomMember.create(room,user));
        return new Actor(user,room,"Bearer "+tokens.createAccessToken(user).accessToken());
    }
    private long demo(Actor a,String... scenarios) throws Exception {
        var response=mvc.perform(post("/api/rooms/"+a.room().getId()+"/ocr-demo/receipts").header("Authorization",a.token())
            .contentType("application/json").content(json.writeValueAsString(Map.of("name","가짜 영수증","scenarios",scenarios))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).at("/data/receiptId").asLong();
    }
    private JsonNode start(Actor a,long receipt,String request) throws Exception {
        return json.readTree(mvc.perform(post(path(a,receipt)).header("Authorization",a.token()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("requestId",request)))).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).path("data");
    }
    private JsonNode complete(Actor a,long receipt) throws Exception {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(latest(a,receipt).path("status").asText()).isIn("REVIEWABLE","PARTIALLY_FAILED","FAILED"));
        return latest(a,receipt);
    }
    private JsonNode latest(Actor a,long receipt) throws Exception {
        return json.readTree(mvc.perform(get(path(a,receipt)+"/latest").header("Authorization",a.token())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    private String path(Actor a,long receipt){return "/api/rooms/"+a.room().getId()+"/receipts/"+receipt+"/ocr/jobs";}
    private record Actor(User user,Room room,String token){}
}
