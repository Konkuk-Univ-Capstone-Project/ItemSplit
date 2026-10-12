package com.capstone.itemsplit.storage;

import com.capstone.itemsplit.common.exception.*;
import com.capstone.itemsplit.receipt.ReceiptImageRepository;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@ConditionalOnProperty(name="app.storage.type",havingValue="local",matchIfMissing=true)
public class LocalImageController {
    private final LocalReadUrls urls;
    private final StorageService storage;
    private final ReceiptImageRepository images;

    @GetMapping("/api/storage/local/{*key}")
    public ResponseEntity<byte[]> image(@PathVariable String key,@RequestParam long expires,@RequestParam String sig) {
        String storedKey=key.startsWith("/")?key.substring(1):key;
        urls.verify(storedKey,expires,sig);
        var image=images.findByStoredPath(storedKey).orElseThrow(()->new ApiException(ErrorCode.NOT_FOUND));
        try {
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.getContentType()))
                .header(HttpHeaders.CACHE_CONTROL,"private, no-store").header("Referrer-Policy","no-referrer")
                .header(HttpHeaders.CONTENT_DISPOSITION,"inline").body(storage.read(storedKey));
        } catch(IOException e) {throw new ApiException(ErrorCode.NOT_FOUND,"저장된 영수증 사진을 찾을 수 없습니다.");}
    }
}
