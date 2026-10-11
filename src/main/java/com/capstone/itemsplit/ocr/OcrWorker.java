package com.capstone.itemsplit.ocr;

import com.capstone.itemsplit.ocr.engine.*;
import com.capstone.itemsplit.ocr.engine.ReceiptOcrEngine.*;
import com.capstone.itemsplit.ocr.storage.ReceiptImageReader;
import com.capstone.itemsplit.room.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.*;
import org.springframework.transaction.event.*;
import org.springframework.transaction.support.TransactionTemplate;

@Component @ConditionalOnProperty(name="app.ocr.enabled",havingValue="true")
public class OcrWorker {
    private final OcrJobRepository jobs;
    private final OcrJobImageRepository images;
    private final OcrDraftRepository drafts;
    private final OcrDraftItemRepository items;
    private final RoomRepository rooms;
    private final RoomMemberRepository members;
    private final ReceiptImageReader reader;
    private final ReceiptOcrEngine engine;
    private final ObjectMapper mapper;
    private final ThreadPoolTaskExecutor executor;
    private final TransactionTemplate tx;
    private final Duration stuckAfter;

    public OcrWorker(OcrJobRepository jobs,OcrJobImageRepository images,OcrDraftRepository drafts,OcrDraftItemRepository items,
        RoomRepository rooms,RoomMemberRepository members,ReceiptImageReader reader,ReceiptOcrEngine engine,ObjectMapper mapper,
        @Qualifier("ocrExecutor") ThreadPoolTaskExecutor executor,PlatformTransactionManager transactions,
        @Value("${app.ocr.stuck-after:3m}") Duration stuckAfter) {
        this.jobs=jobs;this.images=images;this.drafts=drafts;this.items=items;this.rooms=rooms;this.members=members;
        this.reader=reader;this.engine=engine;this.mapper=mapper;this.executor=executor;this.stuckAfter=stuckAfter;
        if(stuckAfter.isNegative() || stuckAfter.isZero())throw new IllegalArgumentException("stuck-after must be positive");
        tx=new TransactionTemplate(transactions);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void requested(OcrService.Requested event) {
        try {executor.execute(()->process(event.jobId()));}
        catch(org.springframework.core.task.TaskRejectedException e) {interrupt(event.jobId(),true);}
    }
    /** No DB transaction or lock is held while calling either adapter. */
    public void process(Long jobId) {
        while(true) {
            Claim claim=tx.execute(status->claim(jobId));
            if(claim==null) return;
            long started=System.nanoTime();
            try {
                if(!List.of("image/jpeg","image/png").contains(claim.contentType()) || claim.fileSize()>10_485_760)
                    throw new OcrEngineException("UNSUPPORTED_INPUT",false);
                byte[] bytes;
                try {bytes=reader.read(claim.key());}
                catch(IOException e){throw new OcrEngineException("IMAGE_UNAVAILABLE",true);}
                if(bytes==null || bytes.length==0) throw new OcrEngineException("IMAGE_UNAVAILABLE",true);
                if(bytes.length>10_485_760)throw new OcrEngineException("UNSUPPORTED_INPUT",false);
                OcrImageResult result=engine.recognize(new OcrImageInput(bytes,claim.contentType(),claim.filename()));
                if(result==null || result.lines()==null)throw new OcrEngineException("PROVIDER_ERROR",true);
                if(result.lines().isEmpty())throw new OcrEngineException("EMPTY_RESULT",false);
                tx.executeWithoutResult(status->complete(claim,result,(System.nanoTime()-started)/1_000_000));
            } catch(OcrEngineException e) {
                fail(claim,e.code(),e.retryable());
            } catch(RuntimeException e) {
                // Do not persist/log provider bodies, secrets or raw images in an error message.
                fail(claim,"PROVIDER_ERROR",true);
            }
        }
    }
    private Claim claim(Long jobId) {
        OcrJob job=lockJob(jobId);if(job==null)return null;
        var rows=images.findAllByJobIdOrderBySortOrder(jobId);
        if(!members.existsByRoomIdAndUserId(job.getReceipt().getRoom().getId(),job.getRequestedByUserId())) {
            rows.stream().filter(this::active).forEach(i->i.fail("ACCESS_REVOKED",false));summarize(job,rows);return null;
        }
        // Only one image of a job may be in flight, including duplicate executor deliveries.
        if(rows.stream().anyMatch(i->i.getStatus()==OcrJobImage.Status.PROCESSING))return null;
        var next=rows.stream().filter(i->i.getStatus()==OcrJobImage.Status.PENDING).findFirst();
        if(next.isEmpty()){summarize(job,rows);return null;}
        var row=next.get();row.start();job.status(OcrJob.Status.PROCESSING);
        var image=row.getImage();
        return new Claim(jobId,row.getId(),row.getAttemptCount(),image.getStoredPath(),image.getContentType(),image.getOriginalFilename(),image.getFileSize());
    }
    private void complete(Claim claim,OcrImageResult result,long duration) {
        OcrJob job=lockJob(claim.jobId());if(job==null)return;
        var rows=images.findAllByJobIdOrderBySortOrder(job.getId());
        var row=current(rows,claim);if(row==null)return;
        if(!members.existsByRoomIdAndUserId(job.getReceipt().getRoom().getId(),job.getRequestedByUserId())) {
            row.fail("ACCESS_REVOKED",false);summarize(job,rows);return;
        }
        OcrDraft draft=drafts.findByJobId(job.getId()).orElseThrow();
        for(int i=0;i<result.lines().size();i++) {
            var line=result.lines().get(i);
            items.save(OcrDraftItem.create(draft,row.getImage(),i+1,line,json(line)));
        }
        // Selected fields only; the full CLOVA response (card numbers etc.) never reaches the database.
        var detail=new CandidateDetail(result.storeName(),result.purchasedAt(),result.totalAmount());
        row.succeed(value(result.storeName()),value(result.purchasedAt()),value(result.totalAmount()),json(detail),duration);
        draft.touch();summarize(job,rows);
    }
    private void fail(Claim claim,String code,boolean retryable) {
        tx.executeWithoutResult(status->{
            var job=lockJob(claim.jobId());if(job==null)return;
            var rows=images.findAllByJobIdOrderBySortOrder(job.getId());var row=current(rows,claim);
            if(row!=null)row.fail(code,retryable);summarize(job,rows);
        });
    }
    private OcrJobImage current(List<OcrJobImage> rows,Claim claim) {
        return rows.stream().filter(i->i.getId().equals(claim.rowId()) && i.getAttemptCount()==claim.attempt()
            && i.getStatus()==OcrJobImage.Status.PROCESSING).findFirst().orElse(null);
    }
    private OcrJob lockJob(Long jobId) {
        var roomId=jobs.findRoomId(jobId);
        if(roomId.isEmpty() || rooms.findByIdForUpdate(roomId.get()).isEmpty())return null;
        return jobs.findById(jobId).orElse(null);
    }
    private void summarize(OcrJob job,List<OcrJobImage> rows) {
        if(rows.stream().anyMatch(this::active))job.status(OcrJob.Status.PROCESSING);
        else {
            long success=rows.stream().filter(i->i.getStatus()==OcrJobImage.Status.SUCCEEDED).count();
            job.status(success==rows.size()?OcrJob.Status.REVIEWABLE:success==0?OcrJob.Status.FAILED:OcrJob.Status.PARTIALLY_FAILED);
        }
    }
    private boolean active(OcrJobImage row) {return row.getStatus()==OcrJobImage.Status.PENDING || row.getStatus()==OcrJobImage.Status.PROCESSING;}
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {recover(true);}
    @Scheduled(fixedDelayString="${app.ocr.recovery-interval-ms:60000}",initialDelayString="${app.ocr.recovery-interval-ms:60000}")
    public void recoverStuckJobs() {recover(false);}
    private void recover(boolean all) {
        var ids=tx.execute(status->jobs.findAllByStatusIn(List.of(OcrJob.Status.PENDING,OcrJob.Status.PROCESSING)).stream().map(OcrJob::getId).toList());
        for(Long id:Objects.requireNonNull(ids))interrupt(id,all);
    }
    private void interrupt(Long id,boolean all) {
        tx.executeWithoutResult(status->{
            var job=lockJob(id);if(job==null)return;
            var rows=images.findAllByJobIdOrderBySortOrder(id);
            LocalDateTime cutoff=LocalDateTime.now().minus(stuckAfter);
            for(var row:rows) {
                LocalDateTime since=row.getStatus()==OcrJobImage.Status.PENDING?row.getQueuedAt():row.getStartedAt();
                if(active(row) && (all || since.isBefore(cutoff)))row.fail("INTERRUPTED",true);
            }
            summarize(job,rows);
        });
    }
    private String json(Object value) {try{return mapper.writeValueAsString(value);}catch(IOException e){throw new IllegalStateException("Cannot serialize selected OCR fields",e);}}
    private static <T>T value(OcrField<T> field){return field==null?null:field.value();}
    private record Claim(Long jobId,Long rowId,int attempt,String key,String contentType,String filename,long fileSize){}
    private record CandidateDetail(OcrField<String> storeName,OcrField<LocalDate> purchasedAt,OcrField<Long> totalAmount){}
}
