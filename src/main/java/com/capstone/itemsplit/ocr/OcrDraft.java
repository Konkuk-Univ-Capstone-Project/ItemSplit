package com.capstone.itemsplit.ocr;
import com.capstone.itemsplit.receipt.Receipt;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import java.time.LocalDateTime;

@Entity @Table(name="ocr_drafts",uniqueConstraints=@UniqueConstraint(columnNames="job_id"))
@Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class OcrDraft {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="job_id",nullable=false)
    @OnDelete(action=OnDeleteAction.CASCADE) private OcrJob job;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="receipt_id",nullable=false)
    @OnDelete(action=OnDeleteAction.CASCADE) private Receipt receipt;
    @Column(nullable=false,length=20) private String status;
    @Version @Column(nullable=false) private long revision;
    @Column(nullable=false) private LocalDateTime createdAt;
    @Column(nullable=false) private LocalDateTime updatedAt;
    public static OcrDraft create(OcrJob job) {
        OcrDraft draft=new OcrDraft();draft.job=job;draft.receipt=job.getReceipt();draft.status="EDITING";
        draft.createdAt=LocalDateTime.now();draft.updatedAt=draft.createdAt;return draft;
    }
    void touch() {updatedAt=LocalDateTime.now();}
}
