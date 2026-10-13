package com.capstone.itemsplit.ocr;

import com.capstone.itemsplit.ocr.engine.ReceiptOcrEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

/** Explicitly invoked only; makes exactly one billable request with a generated, non-personal receipt. */
@Tag("clova-live")
@EnabledIfEnvironmentVariable(named="CLOVA_LIVE_TEST", matches="true")
class ClovaLiveSmokeTest {
    @Test void recognizesSyntheticReceiptThroughConfiguredRealEngine() throws Exception {
        byte[] receipt = receiptImage();
        new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(OcrConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(com.capstone.itemsplit.storage.StorageService.class,
                () -> org.mockito.Mockito.mock(com.capstone.itemsplit.storage.StorageService.class))
            .withPropertyValues("spring.profiles.active=ocr")
            .run(context -> {
                assertThat(context).hasNotFailed();
                var result = context.getBean(ReceiptOcrEngine.class).recognize(
                    new ReceiptOcrEngine.OcrImageInput(receipt, "image/png", "synthetic-receipt.png"));
                assertThat(result.lines()).isNotEmpty();
                // Check structured extraction, not just HTTP authentication.
                assertThat(result.totalAmount().value()).isEqualTo(18000L);
                assertThat(result.lines()).anySatisfy(line -> {
                    assertThat(line.quantity().value()).isEqualTo(2);
                    assertThat(line.unitPrice().value()).isEqualTo(8000L);
                    assertThat(line.lineAmount().value()).isEqualTo(16000L);
                });
            });
    }

    private byte[] receiptImage() throws Exception {
        var image = new BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 1000, 1000);
            graphics.setColor(Color.BLACK);
            graphics.setFont(new Font("SansSerif", Font.BOLD, 44));
            graphics.drawString("ITEMSPLIT TEST RESTAURANT", 65, 90);
            graphics.setFont(new Font("SansSerif", Font.PLAIN, 32));
            graphics.drawString("RECEIPT", 410, 160);
            graphics.drawString("2026-10-12 12:30", 65, 225);
            graphics.drawLine(65, 265, 935, 265);
            graphics.drawString("ITEM", 65, 320);
            graphics.drawString("PRICE", 450, 320);
            graphics.drawString("QTY", 650, 320);
            graphics.drawString("AMOUNT", 790, 320);
            graphics.drawString("Beef Soup", 65, 395);
            graphics.drawString("8,000", 450, 395);
            graphics.drawString("2", 675, 395);
            graphics.drawString("16,000", 790, 395);
            graphics.drawString("Cola", 65, 465);
            graphics.drawString("1,000", 450, 465);
            graphics.drawString("2", 675, 465);
            graphics.drawString("2,000", 790, 465);
            graphics.drawLine(65, 525, 935, 525);
            graphics.setFont(new Font("SansSerif", Font.BOLD, 40));
            graphics.drawString("TOTAL KRW", 65, 605);
            graphics.drawString("18,000", 770, 605);
            graphics.setFont(new Font("SansSerif", Font.PLAIN, 30));
            graphics.drawString("CASH", 65, 680);
            graphics.drawString("18,000", 790, 680);
            graphics.drawString("Thank you", 400, 800);
        } finally {
            graphics.dispose();
        }
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }
}
