package com.capstone.itemsplit.ocr;

import com.capstone.itemsplit.assignment.*;
import com.capstone.itemsplit.common.MoneyPolicy;
import com.capstone.itemsplit.common.exception.*;
import com.capstone.itemsplit.item.*;
import com.capstone.itemsplit.receipt.*;
import com.capstone.itemsplit.room.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional
public class OcrDraftService {
    private final RoomAuthorizationService authorization;
    private final ReceiptRepository receipts;
    private final ReceiptService receiptService;
    private final OcrDraftRepository drafts;
    private final OcrDraftItemRepository lines;
    private final OcrJobImageRepository images;
    private final RoomMemberRepository members;
    private final ItemRepository items;
    private final AssignmentRepository assignments;
    private final ReceiptAmountValidator amounts;
    private final OcrViews views;

    public OcrViews.JobView save(Long roomId,Long receiptId,Long jobId,Long userId,SaveRequest request) {
        OcrDraft draft=authorize(roomId,receiptId,jobId,userId);
        editable(draft,request.revision());
        var existing=lines.findAllByDraftIdOrderBySortOrder(draft.getId());
        var byId=existing.stream().collect(Collectors.toMap(OcrDraftItem::getId,i->i));
        Set<Long> retained=new HashSet<>();
        int order=0;
        for(var command:request.items()) {
            OcrDraftItem line;
            if(command.draftItemId()==null) line=OcrDraftItem.manual(draft);
            else {
                line=byId.get(command.draftItemId());
                if(line==null || !retained.add(line.getId())) throw OcrService.invalid("초안 품목 ID가 중복되었거나 해당 초안의 품목이 아닙니다.");
            }
            Set<Long> memberIds=new HashSet<>(command.memberIds());
            resolveMembers(roomId,memberIds);
            if(command.excludedFromSettlement() && !memberIds.isEmpty()) throw OcrService.invalid("제외 품목의 참여자 배정을 해제해 주세요.");
            line.edit(command.name(),command.price(),command.quantity(),memberIds,command.excludedFromSettlement(),command.removed(),order++);
            lines.save(line);
        }
        for(var old:existing) if(!retained.contains(old.getId())) {
            if(old.getImage()!=null) throw OcrService.invalid("인식 품목은 삭제 표시로 제외해 주세요.");
            lines.delete(old);
        }
        var jobImages=images.findAllByJobIdOrderBySortOrder(jobId);
        Set<Long> covered=new HashSet<>(request.coveredImageIds());
        Set<Long> failed=jobImages.stream().filter(i->i.getStatus()==OcrJobImage.Status.FAILED)
            .map(i->i.getImage().getId()).collect(Collectors.toSet());
        if(!failed.containsAll(covered)) throw OcrService.invalid("실패한 사진만 수동 입력 확인할 수 있습니다.");
        jobImages.forEach(i->i.cover(covered.contains(i.getImage().getId())));
        draft.touch();drafts.flush();
        return views.view(draft.getJob());
    }

    public ReceiptService.ReceiptDetailResult apply(Long roomId,Long receiptId,Long jobId,Long userId,ApplyRequest request) {
        OcrDraft draft=authorize(roomId,receiptId,jobId,userId);
        if(!request.reviewConfirmed()) throw OcrService.invalid("품목과 금액 검토를 확인해 주세요.");
        if("APPLIED".equals(draft.getStatus())) {
            if(!Objects.equals(draft.getAppliedRevision(),request.revision())) throw revisionMismatch();
            return receiptService.getReceipt(roomId,receiptId,userId);
        }
        editable(draft,request.revision());
        if(!views.view(draft.getJob()).draft().blockers().isEmpty())
            throw OcrService.conflict("DRAFT_HAS_ISSUES","미입력 항목이나 실패한 사진을 확인한 뒤 반영해 주세요.");
        var selected=lines.findAllByDraftIdOrderBySortOrder(draft.getId()).stream().filter(i->!i.isRemoved()).toList();
        long total=amounts.total(items.findAllByReceiptId(receiptId));
        for(var line:selected) {
            total=MoneyPolicy.addWithin(total,MoneyPolicy.itemTotal(line.getPrice(),line.getQuantity()),MoneyPolicy.RECEIPT_MAX,"영수증 품목 합계 한도를 초과했습니다.");
            resolveMembers(roomId,line.getMemberIds());
            if(line.isExcludedFromSettlement() && !line.getMemberIds().isEmpty()) throw OcrService.invalid("제외 품목의 참여자 배정을 해제해 주세요.");
        }
        amounts.validateReplacement(roomId,receiptId,total);
        for(var line:selected) {
            Item item=Item.create(draft.getReceipt(),line.getName().trim(),line.getPrice(),line.getQuantity());
            item.recordOcrSource(line.getId());item.setExcludedFromSettlement(line.isExcludedFromSettlement());items.save(item);
            assignments.saveAll(resolveMembers(roomId,line.getMemberIds()).stream().map(m->Assignment.create(item,m)).toList());
        }
        draft.apply(userId);drafts.flush();
        return receiptService.getReceipt(roomId,receiptId,userId);
    }

    private OcrDraft authorize(Long roomId,Long receiptId,Long jobId,Long userId) {
        authorization.checkMemberForUpdate(roomId,userId);
        Receipt receipt=receipts.findByIdAndRoomId(receiptId,roomId).orElseThrow(()->new ApiException(ErrorCode.NOT_FOUND));
        if(receipt.getCreatedBy()==null || !receipt.getCreatedBy().getId().equals(userId)) throw new ApiException(ErrorCode.FORBIDDEN,"영수증 작성자만 초안을 저장·반영할 수 있습니다.");
        return drafts.findByJobId(jobId).filter(d->d.getReceipt().getId().equals(receiptId))
            .orElseThrow(()->new ApiException(ErrorCode.NOT_FOUND));
    }
    private void editable(OcrDraft draft,long revision) {
        if(!"EDITING".equals(draft.getStatus())) throw OcrService.conflict("DRAFT_NOT_EDITABLE","이미 정산에 반영한 초안입니다.");
        if(draft.getJob().getStatus()==OcrJob.Status.PENDING || draft.getJob().getStatus()==OcrJob.Status.PROCESSING)
            throw OcrService.conflict("JOB_IN_PROGRESS","사진 인식 완료 후 검토해 주세요.");
        if(draft.getRevision()!=revision) throw revisionMismatch();
    }
    private OcrRequestException revisionMismatch() {return OcrService.conflict("DRAFT_REVISION_MISMATCH","초안이 변경되었습니다. 최신 상태를 불러와 다시 검토해 주세요.");}
    private List<RoomMember> resolveMembers(Long roomId,Set<Long> ids) {
        if(ids.isEmpty()) return List.of();
        var found=members.findAllByRoomIdAndIdIn(roomId,ids);
        if(found.size()!=ids.size()) throw OcrService.invalid("참여자는 해당 방의 멤버여야 합니다.");
        return found;
    }
    public record SaveRequest(@NotNull @Min(0) Long revision,@NotNull @Size(max=3) List<@NotNull Long> coveredImageIds,
        @NotNull @Size(max=500) List<@NotNull @Valid LineRequest> items){}
    public record LineRequest(Long draftItemId,@Size(max=255) String name,@Min(0) Long price,@Min(0) Integer quantity,
        @NotNull List<@NotNull Long> memberIds,boolean excludedFromSettlement,boolean removed){}
    public record ApplyRequest(@NotNull @Min(0) Long revision,boolean reviewConfirmed){}
}
