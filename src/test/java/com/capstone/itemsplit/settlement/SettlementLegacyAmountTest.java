package com.capstone.itemsplit.settlement;

import com.capstone.itemsplit.assignment.Assignment;
import com.capstone.itemsplit.assignment.AssignmentRepository;
import com.capstone.itemsplit.item.Item;
import com.capstone.itemsplit.item.ItemRepository;
import com.capstone.itemsplit.receipt.Receipt;
import com.capstone.itemsplit.receipt.ReceiptRepository;
import com.capstone.itemsplit.room.*;
import com.capstone.itemsplit.user.User;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementLegacyAmountTest {
    @Mock RoomAuthorizationService authorization;
    @Mock RoomMemberRepository members;
    @Mock ReceiptRepository receipts;
    @Mock ItemRepository items;
    @Mock AssignmentRepository assignments;
    @InjectMocks SettlementService service;
    Room room;
    RoomMember member;

    @BeforeEach
    void setup() {
        User user = User.create("owner@example.com", "encoded", "owner");
        room = Room.create("room", user);
        ReflectionTestUtils.setField(room, "id", 1L);
        member = RoomMember.create(room, user);
        ReflectionTestUtils.setField(member, "id", 2L);
        when(authorization.checkMember(1L, 1L)).thenReturn(room);
        when(members.findAllByRoomId(1L)).thenReturn(List.of(member));
    }

    @ParameterizedTest
    @CsvSource({"0,1,1", "-1,1,1", "1000001,1,1000001", "1,0,1", "1,1000,1000", "1000000,11,10000000", "2147483647,2147483647,1", "9223372036854775807,999,1"})
    void legacyInvalidItemCannotProduceSettlement(long price, int quantity, int total) {
        Receipt receipt = Receipt.createManual(room, "receipt", member, (long) total, null);
        ReflectionTestUtils.setField(receipt, "id", 3L);
        Item item = Item.create(receipt, "item", price, quantity);
        ReflectionTestUtils.setField(item, "id", 4L);
        when(receipts.findAllByRoomId(1L)).thenReturn(List.of(receipt));
        when(items.findAllByReceiptIdIn(any())).thenReturn(List.of(item));
        when(assignments.findAllByItemIdInWithUser(any())).thenReturn(List.of(Assignment.create(item, member)));
        assertThat(service.calculate(1L, 1L).members()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"0", "-1", "10000001"})
    void legacyInvalidDeclaredTotalCannotProduceSettlement(int declaredTotal) {
        Receipt receipt = Receipt.createManual(room, "receipt", member, (long) declaredTotal, null);
        ReflectionTestUtils.setField(receipt, "id", 3L);
        Item item = Item.create(receipt, "item", 1, 1);
        ReflectionTestUtils.setField(item, "id", 4L);
        when(receipts.findAllByRoomId(1L)).thenReturn(List.of(receipt));
        when(items.findAllByReceiptIdIn(any())).thenReturn(List.of(item));
        when(assignments.findAllByItemIdInWithUser(any())).thenReturn(List.of(Assignment.create(item, member)));
        assertThat(service.calculate(1L, 1L).members()).isEmpty();
    }

    @Test
    void legacyReceiptAboveTotalCapCannotProduceSettlement() {
        Receipt receipt = Receipt.createManual(room, "receipt", member, 10000000L, null);
        ReflectionTestUtils.setField(receipt, "id", 3L);
        Item first = Item.create(receipt, "first", 1000000, 10);
        Item second = Item.create(receipt, "second", 1, 1);
        ReflectionTestUtils.setField(first, "id", 4L);
        ReflectionTestUtils.setField(second, "id", 5L);
        when(receipts.findAllByRoomId(1L)).thenReturn(List.of(receipt));
        when(items.findAllByReceiptIdIn(any())).thenReturn(List.of(first, second));
        when(assignments.findAllByItemIdInWithUser(any())).thenReturn(List.of(Assignment.create(first, member), Assignment.create(second, member)));
        assertThat(service.calculate(1L, 1L).members()).isEmpty();
    }

    @Test
    void legacyRoomAboveTotalCapCannotProduceSettlement() {
        List<Receipt> savedReceipts = new ArrayList<>();
        List<Item> savedItems = new ArrayList<>();
        List<Assignment> savedAssignments = new ArrayList<>();
        for (long id = 1; id <= 11; id++) {
            Receipt receipt = Receipt.createManual(room, "receipt", member, 10000000L, null);
            ReflectionTestUtils.setField(receipt, "id", id);
            Item item = Item.create(receipt, "item", 1000000, 10);
            ReflectionTestUtils.setField(item, "id", id);
            savedReceipts.add(receipt);
            savedItems.add(item);
            savedAssignments.add(Assignment.create(item, member));
        }
        when(receipts.findAllByRoomId(1L)).thenReturn(savedReceipts);
        when(items.findAllByReceiptIdIn(any())).thenReturn(savedItems);
        when(assignments.findAllByItemIdInWithUser(any())).thenReturn(savedAssignments);
        assertThat(service.calculate(1L, 1L).members()).isEmpty();
    }
}
