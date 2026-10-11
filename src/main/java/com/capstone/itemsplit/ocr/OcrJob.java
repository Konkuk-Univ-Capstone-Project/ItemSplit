package com.capstone.itemsplit.ocr;
import com.capstone.itemsplit.receipt.Receipt;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import java.time.LocalDateTime;

@Entity @Table(name="ocr_jobs",uniqueConstraints=@UniqueConstraint(columnNames="receipt_id"))
@Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class OcrJob {
    public enum Status { PENDING, PROCESSING, REVIEWABLE, PARTIALLY_FAILED, FAILED }
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="receipt_id",nullable=false)
    @OnDelete(action=OnDeleteAction.CASCADE) private Receipt receipt;
    @Column(nullable=false,length=64) private String requestId;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private Status status;
    @Column(nullable=false,length=30) private String provider;
    @Column(nullable=false) private Long requestedByUserId;
    @Column(nullable=false) private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    public static OcrJob create(Receipt receipt,String requestId,Long userId,String provider) {
        OcrJob job=new OcrJob(); job.receipt=receipt; job.requestId=requestId; job.requestedByUserId=userId;
        job.provider=provider; job.status=Status.PENDING; job.createdAt=LocalDateTime.now();return job;
    }
    void status(Status status) {
        this.status=status;
        if(status==Status.PROCESSING && startedAt==null) startedAt=LocalDateTime.now();
        finishedAt=(status==Status.PENDING || status==Status.PROCESSING) ? null : LocalDateTime.now();
    }
}
