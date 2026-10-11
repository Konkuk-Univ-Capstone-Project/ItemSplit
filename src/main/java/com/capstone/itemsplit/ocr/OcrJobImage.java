package com.capstone.itemsplit.ocr;
import com.capstone.itemsplit.receipt.ReceiptImage;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import java.time.LocalDateTime;

@Entity @Table(name="ocr_job_images",uniqueConstraints=@UniqueConstraint(columnNames={"job_id","image_id"}))
@Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class OcrJobImage {
    public enum Status { PENDING, PROCESSING, SUCCEEDED, FAILED }
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="job_id",nullable=false)
    @OnDelete(action=OnDeleteAction.CASCADE) private OcrJob job;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="image_id",nullable=false)
    @OnDelete(action=OnDeleteAction.CASCADE) private ReceiptImage image;
    @Column(nullable=false) private int sortOrder;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private Status status;
    @Column(length=30) private String errorCode;
    @Column(nullable=false) private boolean retryable;
    @Column(nullable=false) private int attemptCount;
    private Long durationMs;
    private String storeName;
    private java.time.LocalDate purchasedAt;
    private Long totalAmount;
    @Column(columnDefinition="text") private String candidateDetail;
    @Column(nullable=false) private LocalDateTime queuedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    public static OcrJobImage create(OcrJob job,ReceiptImage image) {
        OcrJobImage row=new OcrJobImage();row.job=job;row.image=image;row.sortOrder=image.getSortOrder();
        row.status=Status.PENDING;row.queuedAt=LocalDateTime.now();return row;
    }
    void start() {status=Status.PROCESSING;attemptCount++;startedAt=LocalDateTime.now();finishedAt=null;errorCode=null;retryable=false;}
    void retry() {status=Status.PENDING;queuedAt=LocalDateTime.now();errorCode=null;retryable=false;startedAt=null;finishedAt=null;durationMs=null;}
    void fail(String code,boolean retryable) {status=Status.FAILED;errorCode=code;this.retryable=retryable;finishedAt=LocalDateTime.now();}
    void succeed(String name,java.time.LocalDate date,Long total,String detail,long duration) {
        storeName=name;purchasedAt=date;totalAmount=total;candidateDetail=detail;durationMs=duration;
        status=Status.SUCCEEDED;errorCode=null;retryable=false;finishedAt=LocalDateTime.now();
    }
}
