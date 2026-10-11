package com.capstone.itemsplit.ocr;

import com.capstone.itemsplit.ocr.engine.FakeClovaTransport;
import com.capstone.itemsplit.ocr.engine.FakeOcrScenario;
import com.capstone.itemsplit.receipt.ReceiptImageRepository;
import com.capstone.itemsplit.receipt.ReceiptImageService;
import com.capstone.itemsplit.room.RoomAuthorizationService;
import com.capstone.itemsplit.storage.StorageCleanupService;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Profile("ocr-demo & !prod")
@ConditionalOnProperty(name={"app.ocr.enabled","app.ocr.demo-enabled"},havingValue="true")
public class OcrDemoService {
    private final RoomAuthorizationService authorization;
    private final ReceiptImageService uploads;
    private final ReceiptImageRepository images;
    private final StorageCleanupService cleanup;

    @Transactional
    public DemoReceipt create(Long roomId,Long userId,String name,List<FakeOcrScenario> scenarios) {
        authorization.checkMemberForUpdate(roomId,userId);
        if(scenarios==null || scenarios.isEmpty() || scenarios.size()>3 || scenarios.stream().anyMatch(Objects::isNull)) {
            throw OcrService.invalid("시나리오는 1~3개 필요합니다.");
        }
        Long receiptId=null;
        List<Long> imageIds=new ArrayList<>();
        for(var scenario:scenarios) {
            var file=new DemoFile(scenario.name()+".png",FakeClovaTransport.demoImage(scenario));
            Long imageId;
            if(receiptId==null) {
                var created=uploads.createReceiptWithImage(roomId,userId,file,name);
                receiptId=created.receiptId();
                imageId=created.image().imageId();
            } else {
                imageId=uploads.addImage(roomId,receiptId,userId,file).imageId();
            }
            imageIds.add(imageId);
            if(scenario==FakeOcrScenario.IMAGE_UNAVAILABLE) {
                // A real missing-object read, using the same cleanup path as receipt deletion.
                cleanup.deleteAfterCommit(images.findById(imageId).orElseThrow().getStoredPath());
            }
        }
        return new DemoReceipt(receiptId,List.copyOf(imageIds),true);
    }

    public record DemoReceipt(Long receiptId,List<Long> imageIds,boolean synthetic){}

    private record DemoFile(String filename,byte[] content) implements MultipartFile {
        @Override public String getName(){return "file";}
        @Override public String getOriginalFilename(){return filename;}
        @Override public String getContentType(){return "image/png";}
        @Override public boolean isEmpty(){return content.length==0;}
        @Override public long getSize(){return content.length;}
        @Override public byte[] getBytes(){return content.clone();}
        @Override public InputStream getInputStream(){return new ByteArrayInputStream(content);}
        @Override public void transferTo(File dest) throws IOException {Files.write(dest.toPath(),content);}
    }
}
