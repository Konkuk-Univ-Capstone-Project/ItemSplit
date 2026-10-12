package com.capstone.itemsplit.receipt;

import com.capstone.itemsplit.common.exception.*;
import com.capstone.itemsplit.room.RoomAuthorizationService;
import com.capstone.itemsplit.storage.StorageReadUrls;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class ReceiptImageQueryService {
    private final RoomAuthorizationService authorization;
    private final ReceiptRepository receipts;
    private final ReceiptImageRepository images;
    private final StorageReadUrls urls;
    public List<ImageView> list(Long roomId,Long receiptId,Long userId) {
        authorization.checkMember(roomId,userId);
        if(receipts.findByIdAndRoomId(receiptId,roomId).isEmpty()) throw new ApiException(ErrorCode.NOT_FOUND);
        var result=new ArrayList<ImageView>();
        for(var image:images.findAllByReceiptId(receiptId)) {
            var signed=urls.create(image.getStoredPath());
            result.add(new ImageView(image.getId(),result.size()+1,signed.url(),signed.expiresAt(),image.getContentType()));
        }
        return List.copyOf(result);
    }
    public record ImageView(Long imageId,int order,String url,Instant expiresAt,String contentType) { }
}
