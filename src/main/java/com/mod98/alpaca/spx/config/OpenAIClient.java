package com.mod98.alpaca.spx.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;
import java.util.List;
import java.util.Map;

@Component
public class OpenAIClient {

    private final String apiKey;
    private final String model;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;

    public OpenAIClient(@Value("${openai.apiKey}") String apiKey,
                        @Value("${openai.model:gpt-4o-mini}") String model,
                        ObjectMapper mapper) {
        this.apiKey = apiKey;
        this.model = model;
        this.restTemplate = new RestTemplate();
        this.mapper = mapper.copy().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    // Text only
    public String chat(String systemPrompt, String userPrompt) {
        try {
            String url = "https://api.openai.com/v1/chat/completions";

            Map<String, Object> body = Map.of(
                    "model", model,
                    "temperature", 0,
                    "max_tokens",200,
                    "messages", List.of(
                            Map.of("role", "system",
                                    "content", List.of(
                                            Map.of("type", "text", "text", systemPrompt)
                                    )
                            ),
                            Map.of("role", "user",
                                    "content", List.of(
                                            Map.of("type", "text", "text", userPrompt)
                                    )
                            )
                    )
            );

            return doRequest(url, body);
        } catch (Exception e) {
            throw new RuntimeException("Failed calling OpenAI", e);
        }
    }

    // Text + Image (Vision)
    public String chatWithImage(String systemPrompt,
                                String userPrompt,
                                byte[] imageBytes,
                                String mimeType) {
        try {
            String url = "https://api.openai.com/v1/chat/completions";

            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            String dataUrl = "data:" + mimeType + ";base64," + base64;

            Map<String, Object> body = Map.of(
                    "model", model,
                    "temperature", 0,
                    "messages", List.of(
                            Map.of("role", "system",
                                    "content", List.of(
                                            Map.of("type", "text", "text", systemPrompt)
                                    )
                            ),
                            Map.of("role", "user",
                                    "content", List.of(
                                            Map.of("type", "text", "text", userPrompt),
                                            Map.of("type", "image_url",
                                                    "image_url", Map.of("url", dataUrl))
                                    )
                            )
                    )
            );

            return doRequest(url, body);
        } catch (Exception e) {
            throw new RuntimeException("Failed calling OpenAI with image", e);
        }
    }

    private String doRequest(String url, Map<String, Object> body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        HttpEntity<String> entity =
                new HttpEntity<>(mapper.writeValueAsString(body), headers);

        ResponseEntity<String> resp =
                restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

        if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
            throw new RuntimeException("OpenAI error: " + resp.getStatusCode());
        }

        Map<?, ?> root = mapper.readValue(resp.getBody(), Map.class);
        List<?> choices = (List<?>) root.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("No choices from OpenAI");
        }
        Map<?, ?> first = (Map<?, ?>) choices.get(0);
        Map<?, ?> msg = (Map<?, ?>) first.get("message");
        return (String) msg.get("content");
    }
}
