package com.capstone.itemsplit.settlement;

import com.capstone.itemsplit.assignment.*;
import com.capstone.itemsplit.item.*;
import com.capstone.itemsplit.receipt.*;
import com.capstone.itemsplit.ocr.*;
import com.capstone.itemsplit.room.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Canonical, room/generation-bound input, read only after taking the room write lock. */
@Component @RequiredArgsConstructor
class SettlementConfirmationInput {
    private final RoomMemberRepository members;
    private final ReceiptRepository receipts;
    private final ItemRepository items;
    private final AssignmentRepository assignments;
    private final ReceiptImageRepository receiptImages;
    private final OcrJobRepository jobs;
    private final OcrJobImageRepository jobImages;
    private final OcrDraftRepository drafts;
    private final OcrDraftItemRepository draftItems;
    private final ObjectMapper mapper;

    Input read(Room room,long generation,RoomSettlementState state) {
        List<Object> rows=new ArrayList<>();
        List<SettlementService.SettlementIssue> issues=new ArrayList<>();
        row(rows,"schema",1);
        row(rows,"room",room.getId(),room.getName(),room.getOwner().getId(),generation,
            state==null?"OPEN":state.getStatus(),state==null?null:state.getCurrentSnapshotId());
        members.findAllByRoomId(room.getId()).stream().sorted(Comparator.comparing(RoomMember::getId))
            .forEach(m->row(rows,"member",m.getId(),m.getDisplayName(),m.isLinkedUser()?m.getUser().getId():null));
        var roomReceipts=receipts.findAllByRoomId(room.getId()).stream().sorted(Comparator.comparing(Receipt::getId)).toList();
        for(var receipt:roomReceipts) {
            row(rows,"receipt",receipt.getId(),receipt.getName(),receipt.getSourceType(),receipt.getPurchasedAt(),
                receipt.getPayer()==null?null:receipt.getPayer().getId(),receipt.getCreatedBy()==null?null:receipt.getCreatedBy().getId());
            var receiptItems=items.findAllByReceiptId(receipt.getId()).stream().sorted(Comparator.comparing(Item::getId)).toList();
            for(var item:receiptItems) row(rows,"item",item.getId(),item.getReceipt().getId(),item.getName(),item.getPrice(),
                item.getQuantity(),item.isExcludedFromSettlement(),item.getOcrDraftItemId());
            if(!receiptItems.isEmpty()) assignments.findAllByItemIdInWithUser(receiptItems.stream().map(Item::getId).toList())
                .stream().sorted(Comparator.comparing(Assignment::getId))
                .forEach(a->row(rows,"assignment",a.getId(),a.getItem().getId(),a.getRoomMember()==null?null:a.getRoomMember().getId()));
            receiptImages.findAllByReceiptId(receipt.getId()).stream().sorted(Comparator.comparing(ReceiptImage::getId))
                .forEach(i->row(rows,"image",i.getId(),i.getSortOrder(),i.getStoredPath(),i.getContentType(),i.getFileSize()));
            var maybeJob=jobs.findByReceiptId(receipt.getId());
            if(maybeJob.isEmpty()) continue;
            var job=maybeJob.get();
            row(rows,"job",job.getId(),job.getStatus(),job.getRequestId(),job.getStartedAt(),job.getFinishedAt());
            boolean blocked=job.getStatus()==OcrJob.Status.PENDING || job.getStatus()==OcrJob.Status.PROCESSING;
            for(var image:jobImages.findAllByJobIdOrderBySortOrder(job.getId())) {
                row(rows,"jobImage",image.getId(),image.getImage().getId(),image.getSortOrder(),image.getStatus(),
                    image.getAttemptCount(),image.isManuallyCovered(),image.getErrorCode(),image.getTotalAmount());
                blocked |= image.getStatus()==OcrJobImage.Status.PENDING || image.getStatus()==OcrJobImage.Status.PROCESSING
                    || (image.getStatus()==OcrJobImage.Status.FAILED && !image.isManuallyCovered());
            }
            var maybeDraft=drafts.findByJobId(job.getId());
            if(maybeDraft.isEmpty()) blocked=true;
            else {
                var draft=maybeDraft.get();
                row(rows,"draft",draft.getId(),draft.getStatus(),draft.getRevision(),draft.getAppliedRevision(),draft.getAppliedAt(),draft.getAppliedByUserId());
                blocked |= !"APPLIED".equals(draft.getStatus()) || draft.getAppliedAt()==null || draft.getAppliedRevision()==null;
                draftItems.findAllByDraftIdOrderBySortOrder(draft.getId()).stream().sorted(Comparator.comparing(OcrDraftItem::getId))
                    .forEach(line->row(rows,"draftItem",line.getId(),line.getImage()==null?null:line.getImage().getId(),
                        line.getSortOrder(),line.getName(),line.getPrice(),line.getQuantity(),line.isExcludedFromSettlement(),
                        line.isRemoved(),new TreeSet<>(line.getMemberIds())));
            }
            if(blocked) issues.add(new SettlementService.SettlementIssue("OCR_NOT_APPLIED",receipt.getId(),receipt.getName(),null,
                "진행 중인 OCR과 미반영 초안, 실패 사진의 수동 보완을 완료해 주세요."));
        }
        try {return new Input(hash(mapper.writeValueAsString(rows)),List.copyOf(issues));}
        catch(com.fasterxml.jackson.core.JsonProcessingException exception) {throw new IllegalStateException("Cannot fingerprint settlement",exception);}
    }
    private static void row(List<Object> rows,Object... values) {rows.add(Arrays.asList(values));}
    static String hash(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException exception) {throw new IllegalStateException(exception);}
    }
    record Input(String token,List<SettlementService.SettlementIssue> issues) { }
}
