package com.mod98.alpaca.spx.service.ocr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.DetectTextRequest;
import software.amazon.awssdk.services.rekognition.model.DetectTextResponse;
import software.amazon.awssdk.services.rekognition.model.Image;
import software.amazon.awssdk.services.rekognition.model.TextDetection;
import software.amazon.awssdk.services.rekognition.model.TextTypes;

@Service
public class AwsOcrService {

    private static final Logger log = LoggerFactory.getLogger(AwsOcrService.class);

    private final RekognitionClient rekognitionClient;

    public AwsOcrService(RekognitionClient rekognitionClient) {
        this.rekognitionClient = rekognitionClient;
    }

    public String extractText(byte[] imageBytes) {
        try {
            SdkBytes sdkBytes = SdkBytes.fromByteArray(imageBytes);

            DetectTextRequest request = DetectTextRequest.builder()
                    .image(Image.builder()
                            .bytes(sdkBytes)
                            .build())
                    .build();

            DetectTextResponse response = rekognitionClient.detectText(request);

            StringBuilder sb = new StringBuilder();
            for (TextDetection td : response.textDetections()) {
                if (td.type() == TextTypes.LINE) {
                    sb.append(td.detectedText()).append("\n");
                }
            }

            String result = sb.toString().trim();
            log.info("AWS OCR text ({} chars)", result.length());
            return result;

        } catch (Exception e) {
            log.error("Failed to run AWS Rekognition OCR", e);
            return "";
        }
    }
}
