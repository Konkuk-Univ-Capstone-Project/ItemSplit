package com.capstone.itemsplit.settlement;

import com.capstone.itemsplit.auth.JwtTokenProvider;
import com.capstone.itemsplit.user.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP/security/transaction boundary regression; only identities and clock expiry use DB fixtures. */
@SpringBootTest(properties={"app.settlement.snapshots.enabled=true","app.storage.local.root-path=./build/test-storage"})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker=true)
class SettlementLifecyclePostgresApiTest {
    @Container @ServiceConnection static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtTokenProvider jwt;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    @Test void authenticatedManualInputThroughReconfirmationPreservesHistoryAndRevokesDeletedRoomLinks() throws Exception {
        User owner=actor("owner"), member=actor("member"), outsider=actor("outsider");
        long room=data(send(post("/api/rooms"),owner,Map.of("name","lifecycle dinner")),201).path("roomId").asLong();
        String base="/api/rooms/"+room;
        long payer=data(send(get(base+"/members"),owner,null),200).path("members").get(0).path("memberId").asLong();
        String invite=data(send(post(base+"/invite-token"),owner,null),200).path("token").asText();
        long debtor=data(send(post("/api/rooms/join").param("token",invite),member,null),200).path("memberId").asLong();
        Map<String,Object> receiptBody=Map.of("requestId","manual-1","name","dinner","payerMemberId",payer,
            "items",List.of(Map.of("name","rice","price",10001,"quantity",1,"memberIds",List.of(debtor),"excludedFromSettlement",false)));
        JsonNode receipt=data(send(post(base+"/receipts/manual"),owner,receiptBody),201);
        long receiptId=receipt.path("receiptId").asLong(), itemId=receipt.path("items").get(0).path("itemId").asLong();
        String receiptPath=base+"/receipts/"+receiptId, itemPath=receiptPath+"/items/"+itemId;
        send(get(base+"/settlement-state"),outsider,null).andExpect(status().isForbidden());
        mvc.perform(get(base+"/settlement-state")).andExpect(status().isUnauthorized());
        JsonNode preview=data(send(get(base+"/settlements/confirmation-preview"),member,null),200);
        assertThat(preview.path("ready").asBoolean()).isTrue();
        Map<String,Object> confirmation=Map.of("requestId","first","previewToken",preview.path("previewToken").asText());
        send(post(base+"/settlement-snapshots"),member,confirmation).andExpect(status().isForbidden());
        JsonNode first=data(send(post(base+"/settlement-snapshots"),owner,confirmation),201);
        assertThat(data(send(post(base+"/settlement-snapshots"),owner,confirmation),200)).isEqualTo(first);
        long firstId=first.path("snapshotId").asLong(), transfer=first.path("transfers").get(0).path("transferId").asLong();
        String snapshot=base+"/settlement-snapshots/"+firstId;
        Map<String,Object> edit=Map.of("name","changed","price",20000,"quantity",1);
        locked(send(put(itemPath),owner,edit));
        locked(send(delete(itemPath),owner,null));
        locked(send(post(receiptPath+"/items"),owner,edit));
        locked(send(put(itemPath+"/assignees"),member,Map.of("memberIds",List.of(payer))));
        locked(send(put(receiptPath),owner,Map.of("name","changed","payerMemberId",payer)));
        locked(send(put(receiptPath+"/contents"),owner,Map.of("name","changed","payerMemberId",payer,"items",receiptBody.get("items"))));
        locked(send(delete(receiptPath),owner,null));
        locked(send(post(base+"/receipts/manual"),owner,receiptBody));
        locked(send(post(base+"/members/manual"),owner,Map.of("nickname","new")));
        locked(send(put(base+"/members/"+debtor),owner,Map.of("nickname","renamed")));
        locked(send(delete(base+"/members/"+debtor),owner,null));
        locked(send(delete(base),owner,null));
        MockMultipartFile image=new MockMultipartFile("file","receipt.png","image/png",new byte[]{1});
        locked(send(multipart(base+"/receipts/image").file(image),owner,null));
        String completion=snapshot+"/transfers/"+transfer+"/completion";
        send(put(completion),member,Map.of("completed",true)).andExpect(status().isForbidden());
        JsonNode completed=data(send(put(completion),owner,Map.of("completed",true)),200);
        assertThat(data(send(put(completion),owner,Map.of("completed",true)),200)).isEqualTo(completed);
        JsonNode cancelled=data(send(put(completion),owner,Map.of("completed",false)),200);
        assertThat(cancelled.path("status").asText()).isEqualTo("PENDING");
        assertThat(data(send(put(completion),owner,Map.of("completed",false)),200)).isEqualTo(cancelled);
        completed=data(send(put(completion),owner,Map.of("completed",true)),200);
        assertThat(completed.path("status").asText()).isEqualTo("COMPLETED");
        send(post(snapshot+"/share-token"),member,null).andExpect(status().isForbidden());
        String old=token(snapshot,owner), retained=token(snapshot,owner);
        shared(old).andExpect(status().isNotFound());
        JsonNode publicFirst=data(shared(retained).andExpect(header().string("Cache-Control","no-store")),200);
        assertThat(publicFirst.toString()).doesNotContain("userId","UserId","email","requestId","fingerprint","tokenHash","receipt","image","updatedAt","transferId","roomId");
        assertThat(publicFirst.path("transfers").get(0).path("status").asText()).isEqualTo("COMPLETED");
        send(post(snapshot+"/reopen"),member,null).andExpect(status().isForbidden());
        data(send(post(snapshot+"/reopen"),owner,null),200);
        JsonNode historical=data(send(get(snapshot),member,null),200);
        assertThat(historical.path("status").asText()).isEqualTo("REOPENED");
        assertThat(historical.path("transfers").get(0)).isEqualTo(completed);
        shared(retained).andExpect(jsonPath("$.data.status").value("REOPENED"));
        send(put(completion),owner,Map.of("completed",false)).andExpect(status().isConflict());
        data(send(put(itemPath),owner,edit),200);
        data(send(put(base+"/members/"+debtor),owner,Map.of("nickname","renamed")),200);
        preview=data(send(get(base+"/settlements/confirmation-preview"),owner,null),200);
        JsonNode second=data(send(post(base+"/settlement-snapshots"),owner,Map.of("requestId","second","previewToken",preview.path("previewToken").asText())),201);
        assertThat(second.path("version").asInt()).isEqualTo(2);
        assertThat(second.path("snapshotId")).isNotEqualTo(first.path("snapshotId"));
        assertThat(second.path("transfers").get(0).path("amount").asLong()).isEqualTo(20000);
        assertThat(second.path("transfers").get(0).path("status").asText()).isEqualTo("PENDING");
        assertThat(second.path("transfers").get(0).path("updatedAt").isNull()).isTrue();
        assertThat(data(send(get(snapshot),owner,null),200)).isEqualTo(historical);
        assertThat(data(send(post(base+"/settlement-snapshots"),owner,confirmation),200)).isEqualTo(historical);
        long secondId=second.path("snapshotId").asLong();
        String secondPath=base+"/settlement-snapshots/"+secondId;
        String expired=token(secondPath,owner);
        jdbc.update("update settlement_snapshot_share_tokens set created_at=CURRENT_TIMESTAMP-interval '15 days', expires_at=CURRENT_TIMESTAMP-interval '1 day' where snapshot_id=?",secondId);
        shared(expired).andExpect(status().isNotFound());
        String current=token(secondPath,owner);
        shared(current).andExpect(jsonPath("$.data.snapshotId").value(secondId));
        shared(retained).andExpect(jsonPath("$.data.transfers[0].amount").value(10001));
        data(send(post(secondPath+"/reopen"),owner,null),200);
        send(delete(base),owner,null).andExpect(status().isNoContent());
        shared(retained).andExpect(status().isNotFound());
        shared(current).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from settlement_snapshots where room_id=?",Long.class,room)).isZero();
    }
    private User actor(String name) {return users.save(User.create(UUID.randomUUID()+"@test.com","encoded",name));}
    private ResultActions send(MockHttpServletRequestBuilder request,User user,Object body) throws Exception {
        request.header("Authorization","Bearer "+jwt.createAccessToken(user).accessToken());
        if(body!=null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        return mvc.perform(request);
    }
    private JsonNode data(ResultActions result,int code) throws Exception {
        return mapper.readTree(result.andExpect(status().is(code)).andReturn().getResponse().getContentAsString()).path("data");
    }
    private void locked(ResultActions result) throws Exception {result.andExpect(status().isConflict()).andExpect(jsonPath("$.error.details[0].reason").value("SETTLEMENT_LOCKED"));}
    private String token(String path,User owner) throws Exception {return data(send(post(path+"/share-token"),owner,null),200).path("token").asText();}
    private ResultActions shared(String token) throws Exception {return mvc.perform(get("/api/shared/settlements/"+token));}
}
