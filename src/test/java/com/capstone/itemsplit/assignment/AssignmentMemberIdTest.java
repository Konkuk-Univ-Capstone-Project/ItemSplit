package com.capstone.itemsplit.assignment;

import com.capstone.itemsplit.common.exception.ApiException;
import com.capstone.itemsplit.common.exception.ErrorCode;
import com.capstone.itemsplit.item.Item;
import com.capstone.itemsplit.item.ItemRepository;
import com.capstone.itemsplit.receipt.Receipt;
import com.capstone.itemsplit.receipt.ReceiptRepository;
import com.capstone.itemsplit.room.*;
import com.capstone.itemsplit.user.User;
import com.capstone.itemsplit.user.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class AssignmentMemberIdTest {
    @Mock AssignmentRepository assignments;
    @Mock ItemRepository items;
    @Mock ReceiptRepository receipts;
    @Mock UserRepository users;
    @Mock RoomMemberRepository members;
    @Mock RoomAuthorizationService authorization;
    @InjectMocks AssignmentService service;

    @Test
    void userIdCannotBeReinterpretedAsRoomMemberId() {
        User user = User.create("owner@example.com", "encoded", "owner");
        ReflectionTestUtils.setField(user, "id", 7L);
        Room room = Room.create("room", user);
        ReflectionTestUtils.setField(room, "id", 1L);
        RoomMember member = RoomMember.create(room, user);
        ReflectionTestUtils.setField(member, "id", 42L);
        Receipt receipt = Receipt.createManual(room, "receipt", member, null, null);
        ReflectionTestUtils.setField(receipt, "id", 2L);
        Item item = Item.create(receipt, "item", 100, 1);
        ReflectionTestUtils.setField(item, "id", 3L);
        when(receipts.findByIdWithRoom(2L)).thenReturn(Optional.of(receipt));
        when(items.findByIdWithReceipt(3L)).thenReturn(Optional.of(item));
        when(members.findAllByRoomIdAndIdIn(eq(1L), any())).thenReturn(List.of());
        lenient().when(members.findAllByRoomIdAndUserIdIn(eq(1L), any())).thenReturn(List.of(member));

        assertThatThrownBy(() -> service.replaceAssignees(1L, 2L, 3L, 7L, List.of(7L)))
            .isInstanceOfSatisfying(ApiException.class,
                exception -> org.assertj.core.api.Assertions.assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }
}
