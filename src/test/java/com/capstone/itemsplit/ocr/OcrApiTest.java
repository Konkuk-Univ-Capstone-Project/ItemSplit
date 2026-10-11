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
