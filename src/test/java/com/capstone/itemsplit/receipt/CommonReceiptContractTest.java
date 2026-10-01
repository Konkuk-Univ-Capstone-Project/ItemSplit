package com.capstone.itemsplit.receipt;

import com.capstone.itemsplit.auth.JwtTokenProvider;
import com.capstone.itemsplit.assignment.*;
import com.capstone.itemsplit.item.*;
import com.capstone.itemsplit.room.*;
import com.capstone.itemsplit.user.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CommonReceiptContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtTokenProvider jwt;
    @Autowired UserRepository users;
    @Autowired RoomRepository rooms;
    @Autowired RoomMemberRepository members;
    @Autowired ReceiptRepository receipts;
    @Autowired ItemRepository items;
    @Autowired AssignmentRepository assignments;
    @Autowired ReceiptService service;
    User owner;
    Room room;
    RoomMember member;
    String token;

    @BeforeEach void setUp() {
        assignments.deleteAll(); items.deleteAll(); receipts.deleteAll(); members.deleteAll(); rooms.deleteAll(); users.deleteAll();
        owner = users.save(User.create("contract@example.com", "encoded", "owner"));
        room = rooms.save(Room.create("contract", owner));
        member = members.save(RoomMember.create(room, owner));
        token = "Bearer " + jwt.createAccessToken(owner).accessToken();
    }

    String base() { return "/api/rooms/" + room.getId() + "/receipts"; }
    Map<String,Object> row(Long id, long price, int quantity, List<Long> memberIds, boolean excluded) {
        Map<String,Object> row = new HashMap<>();
        row.put("itemId", id); row.put("name", "meal"); row.put("price", price); row.put("quantity", quantity);
        row.put("memberIds", memberIds); row.put("excludedFromSettlement", excluded); return row;
    }
    Map<String,Object> payload(List<Map<String,Object>> rows) {
        Map<String,Object> p = new HashMap<>();
        p.put("name", "receipt"); p.put("payerMemberId", member.getId()); p.put("declaredTotal", 1000);
        p.put("items", rows); p.put("requestId", UUID.randomUUID().toString()); return p;
    }

    @ParameterizedTest
    @CsvSource({"1000001,1", "1,1000", "1000000,11", "0,1", "-1,1"})
    void rejectsOutOfRangeItemsWithoutSaving(long price, int quantity) throws Exception {
        mvc.perform(post(base()+"/manual").header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(payload(List.of(row(null,price,quantity,List.of(),false))))))
            .andExpect(status().isBadRequest());
        assertThat(receipts.count()).isZero(); assertThat(items.count()).isZero();
    }

    @Test void rejectsFractionalQuantityWithoutTruncating() throws Exception {
        var p = payload(List.of(row(null,1000,1,List.of(),false)));
        String body = json.writeValueAsString(p).replace("\"quantity\":1", "\"quantity\":1.5");
        mvc.perform(post(base()+"/manual").header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());
        assertThat(receipts.count()).isZero();
    }

    @Test void rejectsReceiptAndRoomSumOverLimit() throws Exception {
        mvc.perform(post(base()+"/manual").header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(payload(List.of(row(null,1000000,10,List.of(),false),row(null,1,1,List.of(),false))))))
            .andExpect(status().isBadRequest());
        for(int i=0;i<10;i++) {
            Receipt r = receipts.save(Receipt.createManual(room,"full",null,null,null));
            items.save(Item.create(r,"line",1000000,10));
        }
        mvc.perform(post(base()+"/manual").header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(payload(List.of(row(null,1,1,List.of(),false))))))
            .andExpect(status().isBadRequest());
        assertThat(receipts.count()).isEqualTo(10);
    }

    @Test void createStoresAssignmentsAndRetryDoesNotDuplicate() throws Exception {
        var p = payload(List.of(row(null,1000,1,List.of(member.getId()),false)));
        String body=json.writeValueAsString(p);
        for(int i=0;i<2;i++) {
            mvc.perform(post(base()+"/manual").header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.items[0].memberIds[0]").value(member.getId()));
        }
        assertThat(receipts.count()).isEqualTo(1); assertThat(assignments.count()).isEqualTo(1);
        p.put("name","different");
        mvc.perform(post(base()+"/manual").header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(p))).andExpect(status().isBadRequest());
    }

    @Test void atomicSavePreservesIdsAndRollsBackAllOnInvalidMember() throws Exception {
        Receipt receipt=receipts.save(Receipt.createManual(room,"original",member,null,null));
        Item item=items.save(Item.create(receipt,"meal",1000,1));
        assignments.save(Assignment.create(item,member));
        var p=payload(List.of(row(item.getId(),2000,1,List.of(member.getId()),false),row(null,1000,1,List.of(Long.MAX_VALUE),false)));
        mvc.perform(put(base()+"/"+receipt.getId()+"/contents").header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(p))).andExpect(status().isBadRequest());
        assertThat(receipts.findById(receipt.getId()).orElseThrow().getName()).isEqualTo("original");
        assertThat(items.findById(item.getId()).orElseThrow().getPrice()).isEqualTo(1000);
        assertThat(assignments.count()).isEqualTo(1); assertThat(items.count()).isEqualTo(1);
        p=payload(List.of(row(item.getId(),2000,1,List.of(member.getId()),false)));
        mvc.perform(put(base()+"/"+receipt.getId()+"/contents").header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(p))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].itemId").value(item.getId()))
            .andExpect(jsonPath("$.data.items[0].memberIds[0]").value(member.getId()));
    }

    @Test void explicitExclusionClearsAssignmentsAndOmittedItemIsDeleted() throws Exception {
        Receipt receipt=receipts.save(Receipt.createManual(room,"original",member,null,null));
        Item item=items.save(Item.create(receipt,"meal",1000,1));
        Item removed=items.save(Item.create(receipt,"remove",500,1));
        assignments.save(Assignment.create(item,member)); assignments.save(Assignment.create(removed,member));
        var p=payload(List.of(row(item.getId(),1000,1,List.of(),true)));
        mvc.perform(put(base()+"/"+receipt.getId()+"/contents").header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(p))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].excludedFromSettlement").value(true));
        assertThat(items.existsById(removed.getId())).isFalse(); assertThat(assignments.count()).isZero();
    }

    @Test void rejectsExcludedItemWithParticipants() throws Exception {
        mvc.perform(post(base()+"/manual").header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(payload(List.of(row(null,1000,1,List.of(member.getId()),true))))))
            .andExpect(status().isBadRequest());
        assertThat(receipts.count()).isZero();
    }

    @Test void legacyOverflowIsReportedWithoutWrappedTotal() throws Exception {
        Receipt receipt=receipts.save(Receipt.createManual(room,"legacy",member,1L,null));
        items.save(Item.create(receipt,"overflow",Long.MAX_VALUE,2));
        mvc.perform(get(base()+"/"+receipt.getId()).header("Authorization",token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.warning").value(org.hamcrest.Matchers.containsString("계산 범위")));
    }

    @Test void rejectsForeignOrDuplicateItemIdsWithoutDeletingOriginal() throws Exception {
        Receipt receipt=receipts.save(Receipt.createManual(room,"original",member,null,null));
        Item item=items.save(Item.create(receipt,"meal",1000,1));
        Receipt other=receipts.save(Receipt.createManual(room,"other",member,null,null));
        Item foreign=items.save(Item.create(other,"foreign",1000,1));
        for(var rows: List.of(List.of(row(foreign.getId(),1000,1,List.of(),false)),
                List.of(row(item.getId(),1000,1,List.of(),false),row(item.getId(),1000,1,List.of(),false)))) {
            mvc.perform(put(base()+"/"+receipt.getId()+"/contents").header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload(rows)))).andExpect(status().isBadRequest());
            assertThat(items.count()).isEqualTo(2);
            assertThat(receipts.findById(receipt.getId()).orElseThrow().getName()).isEqualTo("original");
        }
    }

    @Test void exactCapsAllowedAndExcludedItemsCannotBypassCaps() throws Exception {
        var p=payload(List.of(row(null,1000000,10,List.of(),true))); p.put("declaredTotal",10000000);
        mvc.perform(post(base()+"/manual").header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(p))).andExpect(status().isCreated());
        p=payload(List.of(row(null,1000000,10,List.of(),true),row(null,1,1,List.of(),true)));
        mvc.perform(post(base()+"/manual").header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(p))).andExpect(status().isBadRequest());
        assertThat(receipts.count()).isEqualTo(1);
    }

    @Test void sameCreateRequestIsSerializedAcrossThreads() throws Exception {
        var commands=List.of(new ReceiptService.SaveItemCommand(null,"meal",1000,1,List.of(member.getId()),false));
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Long> create=() -> {
                start.await();
                return service.createManualReceipt(room.getId(),owner.getId(),"same-create","receipt",member.getId(),1000L,null,commands).receiptId();
            };
            var first=pool.submit(create); var second=pool.submit(create); start.countDown();
            assertThat(first.get(10,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(second.get(10,java.util.concurrent.TimeUnit.SECONDS));
        }
        assertThat(receipts.count()).isEqualTo(1); assertThat(assignments.count()).isEqualTo(1);
    }

    @Test void concurrentCreatesCannotExceedRoomCap() throws Exception {
        for(int i=0;i<10;i++) {
            Receipt receipt=receipts.save(Receipt.createManual(room,"seed",member,null,null));
            items.save(Item.create(receipt,"line",i==9?900000:1000000,10));
        }
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> create=() -> {
                start.await();
                try {
                    service.createManualReceipt(room.getId(),owner.getId(),UUID.randomUUID().toString(),"new",member.getId(),600000L,null,
                        List.of(new ReceiptService.SaveItemCommand(null,"meal",600000,1,List.of(),false)));
                    return true;
                } catch(com.capstone.itemsplit.common.exception.ApiException exception) {
                    assertThat(exception.getErrorCode()).isEqualTo(com.capstone.itemsplit.common.exception.ErrorCode.VALIDATION_ERROR);
                    return false;
                }
            };
            var first=pool.submit(create); var second=pool.submit(create); start.countDown();
            assertThat(List.of(first.get(10,java.util.concurrent.TimeUnit.SECONDS),second.get(10,java.util.concurrent.TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(true,false);
        }
        assertThat(receipts.count()).isEqualTo(11);
        assertThat(items.findAll().stream().mapToLong(item -> item.getPrice()*item.getQuantity()).sum()).isEqualTo(99600000);
    }
}
