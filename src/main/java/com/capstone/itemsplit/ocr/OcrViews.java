package com.capstone.itemsplit.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.util.*;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component @RequiredArgsConstructor
public class OcrViews {
    private final OcrJobImageRepository images;
    private final OcrDraftRepository drafts;
    private final OcrDraftItemRepository items;
    private final ObjectMapper mapper;

    public JobView view(OcrJob job) {
        var rows=images.findAllByJobIdOrderBySortOrder(job.getId());
        var draft=drafts.findByJobId(job.getId()).orElseThrow();
        var lines=items.findAllByDraftIdOrderBySortOrder(draft.getId()).stream().map(this::line).toList();
        List<String> blockers=new ArrayList<>();
        if(job.getStatus()==OcrJob.Status.PENDING || job.getStatus()==OcrJob.Status.PROCESSING) blockers.add("JOB_IN_PROGRESS");
        if(rows.stream().anyMatch(i->i.getStatus()==OcrJobImage.Status.FAILED && !i.isManuallyCovered())) blockers.add("IMAGE_FAILED_UNCOVERED");
        if(lines.stream().allMatch(LineView::removed)) blockers.add("NO_ITEMS");
        if(lines.stream().anyMatch(i->!i.removed() && !i.issues().isEmpty())) blockers.add("ITEM_ISSUES");
        BigInteger total=BigInteger.ZERO;
        for(var line:lines) if(!line.removed() && line.price()!=null && line.quantity()!=null)
            total=total.add(BigInteger.valueOf(line.price()).multiply(BigInteger.valueOf(line.quantity())));
        if(total.compareTo(BigInteger.valueOf(10_000_000))>0) blockers.add("RECEIPT_TOTAL_LIMIT");
        // Null signals an unsafe JS integer; never wrap or silently truncate an OCR sum.
        Long safeTotal=total.abs().compareTo(BigInteger.valueOf(9_007_199_254_740_991L))<=0?total.longValue():null;
        List<String> warnings=new ArrayList<>();
        BigInteger finalTotal=total;
        var totals=rows.stream().map(OcrJobImage::getTotalAmount).filter(Objects::nonNull).toList();
        if(!totals.isEmpty() && totals.stream().noneMatch(t->BigInteger.valueOf(t).equals(finalTotal))) warnings.add("OCR_TOTAL_MISMATCH");
        var imageViews=rows.stream().map(row->new ImageView(row.getImage().getId(),row.getSortOrder(),row.getStatus().name(),
            row.isManuallyCovered(),row.getErrorCode(),row.isRetryable(),row.getAttemptCount(),row.getDurationMs(),row.getStatus()==OcrJobImage.Status.SUCCEEDED
                ?new Candidates(row.getStoreName(),row.getPurchasedAt(),row.getTotalAmount(),json(row.getCandidateDetail())):null)).toList();
        return new JobView(job.getId(),job.getReceipt().getId(),job.getStatus().name(),job.getProvider(),job.getRequestedByUserId(),imageViews,
            new DraftView(draft.getId(),draft.getStatus(),draft.getRevision(),lines,safeTotal,List.copyOf(blockers),warnings));
    }
    private LineView line(OcrDraftItem item) {
        List<String> issues=new ArrayList<>(),warnings=new ArrayList<>();
        if(item.getName()==null || item.getName().isBlank()) issues.add("MISSING_NAME");
        else if(item.getName().length()>100) issues.add("NAME_TOO_LONG");
        Long price=item.getPrice(); Integer qty=item.getQuantity();
        if(price==null) issues.add("MISSING_PRICE");
        else if(price<1 || price>1_000_000) issues.add("PRICE_OUT_OF_RANGE");
        if(qty==null) issues.add("MISSING_QUANTITY");
        else if(qty<1 || qty>999) issues.add("QUANTITY_OUT_OF_RANGE");
        if(price!=null && qty!=null) {
            var total=BigInteger.valueOf(price).multiply(BigInteger.valueOf(qty));
            if(total.signum()<=0 || total.compareTo(BigInteger.valueOf(10_000_000))>0) issues.add("LINE_AMOUNT_OUT_OF_RANGE");
            if(item.getOcrLineAmount()!=null && !total.equals(BigInteger.valueOf(item.getOcrLineAmount()))) warnings.add("LINE_AMOUNT_MISMATCH");
        }
        List<String> flags=item.getOcrFlags()==null || item.getOcrFlags().isBlank()?List.of():List.of(item.getOcrFlags().split(","));
        if(flags.contains("DISCOUNT_OR_REFUND") || flags.contains("NON_INTEGER_QUANTITY")) warnings.add("SPECIAL_LINE");
        Long derived=null;
        if(price==null && item.getOcrLineAmount()!=null && item.getOcrLineAmount()>0 && qty!=null && qty>0
          && item.getOcrLineAmount()%qty==0) derived=item.getOcrLineAmount()/qty;
        return new LineView(item.getId(),item.getImage()==null?null:item.getImage().getId(),item.getRowIndex(),
            new Original(item.getOcrName(),item.getOcrQuantity(),item.getOcrUnitPrice(),item.getOcrLineAmount(),derived,flags,json(item.getOcrDetail())),
            item.getName(),price,qty,item.getMemberIds().stream().sorted().toList(),item.isExcludedFromSettlement(),item.isRemoved(),issues,warnings);
    }
    private JsonNode json(String value) {
        if(value==null) return null;
        try{return mapper.readTree(value);}catch(Exception e){throw new IllegalStateException("Invalid stored OCR detail",e);}
    }
    public record JobView(Long jobId,Long receiptId,String status,String provider,Long requestedByUserId,List<ImageView> images,DraftView draft){}
    public record ImageView(Long imageId,int order,String status,boolean manuallyCovered,String errorCode,boolean retryable,int attemptCount,Long durationMs,Candidates candidates){}
    public record Candidates(String storeName,java.time.LocalDate purchasedAt,Long totalAmount,JsonNode detail){}
    public record DraftView(Long draftId,String status,long revision,List<LineView> items,Long itemsTotal,List<String> blockers,List<String> warnings){}
    public record LineView(Long draftItemId,Long imageId,int rowIndex,Original ocr,String name,Long price,Integer quantity,
        List<Long> memberIds,boolean excludedFromSettlement,boolean removed,List<String> issues,List<String> warnings){}
    public record Original(String name,Integer quantity,Long unitPrice,Long lineAmount,Long derivedUnitPrice,List<String> flags,JsonNode detail){}
}
