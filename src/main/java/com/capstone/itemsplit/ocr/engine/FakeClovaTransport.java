package com.capstone.itemsplit.ocr.engine;

import com.capstone.itemsplit.ocr.engine.ReceiptOcrEngine.OcrImageInput;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Arrays;
import java.util.Base64;
import com.capstone.itemsplit.receipt.ReceiptImageFormat;

/** Offline provider transport. Uploaded JPEG/PNG images return the NORMAL fixture, not recognition. */
public class FakeClovaTransport implements ClovaTransport {
    private static final byte[] DEMO_PNG = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aP1kAAAAASUVORK5CYII=");

    /** A tiny PNG with a bounded fixture marker; goes through the real upload and storage path. */
    public static byte[] demoImage(FakeOcrScenario scenario) {
        byte[] marker=("fake-clova:"+scenario.name()).getBytes(StandardCharsets.UTF_8);
        byte[] bytes=Arrays.copyOf(DEMO_PNG,DEMO_PNG.length+marker.length);
        System.arraycopy(marker,0,bytes,DEMO_PNG.length,marker.length);
        return bytes;
    }

    @Override
    public String recognize(OcrImageInput input) {
        if (input == null || input.content() == null || input.content().length > 10_485_760) throw unsupported();
        byte[] bytes=input.content();
        String marker="";
        if(bytes.length>DEMO_PNG.length && bytes.length<=DEMO_PNG.length+100
            && Arrays.equals(bytes,0,DEMO_PNG.length,DEMO_PNG,0,DEMO_PNG.length)) {
            marker=new String(bytes,DEMO_PNG.length,bytes.length-DEMO_PNG.length,StandardCharsets.UTF_8);
        } else if(bytes.length<=100) {
            marker=new String(bytes,StandardCharsets.UTF_8);
        }
        FakeOcrScenario scenario=FakeOcrScenario.NORMAL;
        if(marker.startsWith("fake-clova:")) {
            try { scenario=FakeOcrScenario.valueOf(marker.substring("fake-clova:".length())); }
            catch(IllegalArgumentException e) {throw unsupported();}
        } else if(ReceiptImageFormat.detect(bytes).isEmpty()) {
            throw unsupported();
        }
        if (scenario == FakeOcrScenario.TIMEOUT) throw new OcrEngineException("TIMEOUT", true);
        // The image reader owns this failure. It must not turn into a normal recognition here.
        if (scenario == FakeOcrScenario.IMAGE_UNAVAILABLE) throw unsupported();
        String path = "/ocr/fixtures/" + scenario.name().toLowerCase(Locale.ROOT) + ".json";
        try (InputStream resource = FakeClovaTransport.class.getResourceAsStream(path)) {
            if (resource == null) throw new OcrEngineException("CONFIG_ERROR", false);
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new OcrEngineException("CONFIG_ERROR", false);
        }
    }

    private OcrEngineException unsupported() {
        return new OcrEngineException("UNSUPPORTED_INPUT", false);
    }
}
