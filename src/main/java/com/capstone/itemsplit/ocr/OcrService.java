package com.capstone.itemsplit.ocr;

import com.capstone.itemsplit.common.exception.*;
import com.capstone.itemsplit.receipt.*;
import com.capstone.itemsplit.room.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.Objects;

@Service @RequiredArgsConstructor
@ConditionalOnProperty(name="app.ocr.enabled",havingValue="true")
@Transactional
public class OcrService {
    private final RoomAuthorizationService authorization;
    private final ReceiptRepository receipts;
    private final ReceiptImageRepository images;
    private final OcrJobRepository jobs;
    private final OcrJobImageRepository jobImages;
    private final OcrDraftRepository drafts;
    private final OcrViews views;
    private final ApplicationEventPublisher events;
    @Value("${app.ocr.provider}") private String provider;
    @Value("${app.ocr.max-attempts:3}") private int maxAttempts;

    public OcrViews.JobView start(Long roomId,Long receiptId,Long userId,String requestId) {
        authorization.checkMemberForUpdate(roomId,userId);
        Receipt receipt=receipt(roomId,receiptId);
        owner(receipt,userId);
        if(requestId==null || requestId.isBlank() || requestId.length()>64) throw invalid("유효한 requestId를 입력해 주세요.");
        if(receipt.getSourceType()!=ReceiptSourceType.IMAGE_UPLOAD) throw invalid("이미지 영수증만 인식할 수 있습니다.");
        var existing=jobs.findByReceiptId(receiptId);
        if(existing.isPresent()) {
            if(!existing.get().getRequestId().equals(requestId)) throw conflict("JOB_ALREADY_EXISTS","이미 인식 작업이 있습니다.");
            return views.view(existing.get());
        }
        var uploaded=images.findAllByReceiptId(receiptId);
        if(uploaded.isEmpty() || uploaded.size()>3) throw invalid("영수증 이미지가 1~3장 필요합니다.");
        OcrJob job=jobs.save(OcrJob.create(receipt,requestId,userId,"fake".equalsIgnoreCase(provider)?"FAKE_CLOVA_RECEIPT":"CLOVA_RECEIPT"));
        drafts.save(OcrDraft.create(job));
        jobImages.saveAll(uploaded.stream().map(image->OcrJobImage.create(job,image)).toList());
        events.publishEvent(new Requested(job.getId()));
        return views.view(job);
    }
    public OcrViews.JobView retry(Long roomId,Long receiptId,Long jobId,Long imageId,Long userId) {
        authorization.checkMemberForUpdate(roomId,userId);
        owner(receipt(roomId,receiptId),userId);
        OcrJob job=job(receiptId,jobId);
        if(!drafts.findByJobId(jobId).orElseThrow().getStatus().equals("EDITING")) throw conflict("DRAFT_NOT_EDITABLE","이미 정산에 반영한 초안입니다.");
        OcrJobImage image=jobImages.findAllByJobIdOrderBySortOrder(jobId).stream()
            .filter(i->i.getImage().getId().equals(imageId)).findFirst().orElseThrow(()->new ApiException(ErrorCode.NOT_FOUND));
        if(image.getStatus()==OcrJobImage.Status.PENDING || image.getStatus()==OcrJobImage.Status.PROCESSING) return views.view(job);
        if(image.getStatus()!=OcrJobImage.Status.FAILED || !image.isRetryable()) throw conflict("IMAGE_NOT_RETRYABLE","재시도 가능한 실패 이미지가 아닙니다.");
        if(image.getAttemptCount()>=maxAttempts) throw new OcrRequestException(ErrorCode.TOO_MANY_REQUESTS,"OCR_ATTEMPT_LIMIT","이미지 재시도 한도를 초과했습니다.");
        image.retry();job.status(OcrJob.Status.PROCESSING);
        events.publishEvent(new Requested(job.getId()));
        return views.view(job);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public OcrViews.JobView get(Long roomId,Long receiptId,Long jobId,Long userId) {
        authorization.checkMember(roomId,userId);receipt(roomId,receiptId);
        return views.view(job(receiptId,jobId));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public OcrViews.JobView latest(Long roomId,Long receiptId,Long userId) {
        authorization.checkMember(roomId,userId);receipt(roomId,receiptId);
        return jobs.findByReceiptId(receiptId).map(views::view).orElse(null);
    }
    private Receipt receipt(Long roomId,Long receiptId) {
        return receipts.findByIdAndRoomId(receiptId,roomId).orElseThrow(()->new ApiException(ErrorCode.NOT_FOUND));
    }
    private OcrJob job(Long receiptId,Long jobId) {
        return jobs.findById(jobId).filter(j->j.getReceipt().getId().equals(receiptId)).orElseThrow(()->new ApiException(ErrorCode.NOT_FOUND));
    }
    private void owner(Receipt receipt,Long userId) {
        if(!Objects.equals(receipt.getCreatedBy()==null?null:receipt.getCreatedBy().getId(),userId)) throw new ApiException(ErrorCode.FORBIDDEN,"영수증을 만든 사용자만 인식을 요청할 수 있습니다.");
    }
    static OcrRequestException conflict(String reason,String message) {return new OcrRequestException(ErrorCode.CONFLICT,reason,message);}
    static ApiException invalid(String message) {return new ApiException(ErrorCode.VALIDATION_ERROR,message);}
    public record Requested(Long jobId){}
}
