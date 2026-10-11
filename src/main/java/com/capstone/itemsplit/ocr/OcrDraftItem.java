package com.capstone.itemsplit.ocr;
import com.capstone.itemsplit.receipt.ReceiptImage;
import com.capstone.itemsplit.ocr.engine.ReceiptOcrEngine.OcrLine;
import com.capstone.itemsplit.ocr.engine.ReceiptOcrEngine.OcrField;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import java.time.LocalDateTime;

@Entity @Table(name="ocr_draft_items",uniqueConstraints=@UniqueConstraint(columnNames={"draft_id","image_id","row_index"}))
@Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class OcrDraftItem {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="draft_id",nullable=false)
    @OnDelete(action=OnDeleteAction.CASCADE) private OcrDraft draft;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="image_id",nullable=false)
    @OnDelete(action=OnDeleteAction.CASCADE) private ReceiptImage image;
    @Column(nullable=false) private int rowIndex;
    @Column(nullable=false) private int sortOrder;
    private String ocrName;
    private Integer ocrQuantity;
    private Long ocrUnitPrice;
    private Long ocrLineAmount;
    @Column(length=255) private String ocrFlags;
    @Column(columnDefinition="text") private String ocrDetail;
    private String name;
    private Long price;
    private Integer quantity;
    @Column(nullable=false) private boolean excludedFromSettlement;
    @Column(nullable=false) private boolean removed;
    public static OcrDraftItem create(OcrDraft draft,ReceiptImage image,int row,OcrLine line,String detail) {
        OcrDraftItem item=new OcrDraftItem();item.draft=draft;item.image=image;item.rowIndex=row;
        item.sortOrder=image.getSortOrder()*1000+row;item.ocrName=value(line.name());
        item.ocrQuantity=value(line.quantity());item.ocrUnitPrice=value(line.unitPrice());item.ocrLineAmount=value(line.lineAmount());
        item.ocrFlags=String.join(",",new java.util.TreeSet<>(line.flags()));item.ocrDetail=detail;
        item.name=item.ocrName;item.price=item.ocrUnitPrice;item.quantity=item.ocrQuantity;return item;
    }
    private static <T>T value(OcrField<T> field) {return field==null?null:field.value();}
}
