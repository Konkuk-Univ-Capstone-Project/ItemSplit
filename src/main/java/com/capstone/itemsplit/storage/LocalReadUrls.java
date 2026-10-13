package com.capstone.itemsplit.storage;

import com.capstone.itemsplit.common.exception.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="app.storage.type",havingValue="local",matchIfMissing=true)
public class LocalReadUrls implements StorageReadUrls {
    private static final long TTL_SECONDS=300;
    private final byte[] secret;
    private final Clock clock;

    @Autowired
    public LocalReadUrls(@Value("${app.storage.local.signing-secret:}") String secret) {
        this(secret,Clock.systemUTC());
    }
    LocalReadUrls(String secret,Clock clock) {
        this.clock=clock;
        if(secret.isBlank()) {this.secret=new byte[32];new SecureRandom().nextBytes(this.secret);}
        else {
            if(secret.getBytes(StandardCharsets.UTF_8).length<32) throw new IllegalStateException("LOCAL_STORAGE_SIGNING_SECRET must contain at least 32 bytes");
            this.secret=secret.getBytes(StandardCharsets.UTF_8);
        }
    }
    @Override public SignedUrl create(String key) {
        validateKey(key);
        long expires=clock.instant().getEpochSecond()+TTL_SECONDS;
        return new SignedUrl("/api/storage/local/"+key+"?expires="+expires+"&sig="+HexFormat.of().formatHex(sign(key,expires)),Instant.ofEpochSecond(expires));
    }
    public void verify(String key,long expires,String signature) {
        validateKey(key);
        long now=clock.instant().getEpochSecond();
        if(expires<=now || expires>now+TTL_SECONDS || signature==null || !signature.matches("[a-f0-9]{64}")) throw denied();
        if(!MessageDigest.isEqual(sign(key,expires),HexFormat.of().parseHex(signature))) throw denied();
    }
    private void validateKey(String key) {
        // Only server-generated receipt image keys are public through this signed route.
        if(key==null || !key.matches("receipts/[0-9]+/[a-zA-Z0-9_-]+\\.(png|jpg|jpeg)")) throw denied();
    }
    private byte[] sign(String key,long expires) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));
            return mac.doFinal((key+"\n"+expires).getBytes(StandardCharsets.UTF_8));
        } catch(GeneralSecurityException e) {throw new IllegalStateException("Cannot sign image URL",e);}
    }
    private ApiException denied() {return new ApiException(ErrorCode.FORBIDDEN,"사진 조회 주소가 만료되었거나 유효하지 않습니다.");}
}
