package com.mod98.alpaca.spx.parsing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Service
public class ImageAnalysisServiceImpl implements ImageAnalysisService {

    private static final String OPENAI_API_KEY =
            System.getProperty("OPENAI_API_KEY", System.getenv("OPENAI_API_KEY"));

    static {
        if (OPENAI_API_KEY == null || OPENAI_API_KEY.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY is missing! Check your .env or environment variables.");
        }
    }

    private static final String PROMPT = """
    You are a trading signal image parser for a Telegram channel.

    There are two useful image roles for us:

    1) PREPARE:
       - The image shows multiple potential contracts or setups.
       - This is just "prepare", we do NOT open a trade yet.
       - The bot should only switch to a waiting state.

    2) ENTRY:
       - The image shows a single clear contract with ONE main entry price.
       - After a PREPARE image, the FIRST ENTRY image is the actual entry signal.
       - Any later images with the same contract and slightly changed prices
         should be considered ENTRY as well, but the bot will ignore them
         because it already entered the trade from the first ENTRY image.

    If the image is not related to trading signals, or cannot be understood, use IGNORE.

    Return ONLY JSON with this structure:
    {
      "imageRole": "PREPARE | ENTRY | IGNORE",
      "contractCount": number,
      "entryPrice": number or null,
      "direction": "LONG | SHORT | UNKNOWN",
      "confidence": number,
      "notes": "any extra notes"
    }

    Rules:
    - imageRole = "PREPARE" if the image shows multiple contracts / setups.
    - imageRole = "ENTRY" if the image shows a single clear contract and price.
    - imageRole = "IGNORE" otherwise.
    - contractCount = how many contracts are shown.
    - entryPrice = main entry price (for ENTRY images) or null.
    - direction = "LONG" for buy/UP, "SHORT" for sell/DOWN, "UNKNOWN" if unclear.
    - confidence = float between 0 and 1.
    - notes = short english notes.

    Output:
    - JSON ONLY, no explanations, no markdown, no extra text.
    """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient();

    @Override
    public ImageAnalysisResult analyze(Path imagePath) {
        try {
            // 1 We read the image and convert it to base64
            byte[] imgBytes = Files.readAllBytes(imagePath);
            String base64 = Base64.getEncoder().encodeToString(imgBytes);
            String dataUrl = "data:image/jpeg;base64," + base64;

            // 2 We build a body for /v1/chat/completions
            JsonNode requestJson = buildRequestJson(dataUrl);
            String requestBodyStr = mapper.writeValueAsString(requestJson);

            RequestBody body = RequestBody.create(
                    requestBodyStr,
                    MediaType.parse("application/json")
            );

            Request request = new Request.Builder()
                    .url("https://api.openai.com/v1/chat/completions")
                    .header("Authorization", "Bearer " + OPENAI_API_KEY)
                    .header("Content-Type", "application/json")
                    .post(body)
                    .build();

            try (Response response = http.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new RuntimeException("ChatCompletions error: " + response);
                }

                String respStr = response.body().string();
                return extractResultFromResponse(respStr);
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to analyze image: " + e.getMessage(), e);
        }
    }

    private JsonNode buildRequestJson(String dataUrl) {
        var root = mapper.createObjectNode();
        root.put("model", "gpt-4.1-mini");

        var messages = root.putArray("messages");
        var msg = messages.addObject();
        msg.put("role", "user");

        var content = msg.putArray("content");

        // Part 1: The text (prompt)
        var textPart = content.addObject();
        textPart.put("type", "text");
        textPart.put("text", PROMPT);

        // Part 2: The Image
        var imgPart = content.addObject();
        imgPart.put("type", "image_url");
        var imgUrl = imgPart.putObject("image_url");
        imgUrl.put("url", dataUrl);

        return root;
    }

    private ImageAnalysisResult extractResultFromResponse(String respStr) throws Exception {
        JsonNode root = mapper.readTree(respStr);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IllegalStateException("No choices in OpenAI response");
        }

        JsonNode message = choices.get(0).path("message");
        JsonNode contentNode = message.path("content");

        String contentText;


        if (contentNode.isArray() && contentNode.size() > 0) {
            contentText = contentNode.get(0).path("text").asText();
        } else {
            contentText = contentNode.asText();
        }

        // Here, contentText should only be JSON (as requested in the prompt)
        return mapper.readValue(contentText, ImageAnalysisResult.class);
    }
}
