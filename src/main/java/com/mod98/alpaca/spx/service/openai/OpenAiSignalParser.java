package com.mod98.alpaca.spx.service.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mod98.alpaca.spx.service.signal.ParsedSignal;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OpenAiSignalParser {

    private final OkHttpClient client = new OkHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;
    private final String model;

    public OpenAiSignalParser(
            @Value("${openai.apiKey}") String apiKey,
            @Value("${openai.model:gpt-4o-mini}") String model) {
        this.apiKey = apiKey;
        this.model = model;
    }

    public ParsedSignal parse(String rawText, long msgId, Long replyTo) {
        try {
            String prompt = buildPrompt(rawText);

            String requestBody = """
            {
              "model": "%s",
              "temperature": 0,
              "messages": [
                {"role": "system", "content": "You are a strict SPXW signal parser. Return ONLY JSON."},
                {"role": "user", "content": "%s"}
              ]
            }
            """.formatted(model, prompt);

            Request request = new Request.Builder()
                    .url("https://api.openai.com/v1/chat/completions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                    .build();

            Response response = client.newCall(request).execute();
            String body = response.body().string();

            String json = extractJson(body);

            ParsedSignal parsed = mapper.readValue(json, ParsedSignal.class);
            parsed.setTelegramMessageId(msgId);
            parsed.setReplyToMessageId(replyTo);

            return parsed;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private String buildPrompt(String text) {
        return """
        Extract structured data from the following SPXW option signal text:

        %s

        Return JSON ONLY:
        {
          "symbol": "SPXW",
          "optionType": "CALL",
          "strike": 6810,
          "expiry": "2025-11-28",
          "entryPrice": 3.30,
          "signalType": "PREPARE"
        }
        """.formatted(text.replace("\"", ""));
    }

    private String extractJson(String body) throws Exception {
        int start = body.indexOf("{");
        int end = body.lastIndexOf("}");
        return body.substring(start, end + 1);
    }
}
