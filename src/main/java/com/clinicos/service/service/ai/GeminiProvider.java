package com.clinicos.service.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gemini AI provider implementation.
 * Activated when clinicos.ai.provider=gemini (default).
 * Manages its own HTTP client — no shared beans.
 */
@Component
@ConditionalOnProperty(name = "clinicos.ai.provider", havingValue = "gemini", matchIfMissing = true)
@Slf4j
public class GeminiProvider implements AiProvider {

    private final ObjectMapper objectMapper;
    private RestClient restClient;

    @Value("${clinicos.ai.api-key}")
    private String apiKey;

    @Value("${clinicos.ai.model:gemini-2.0-flash}")
    private String model;

    public GeminiProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(30));

        this.restClient = RestClient.builder()
                .baseUrl("https://generativelanguage.googleapis.com/v1beta")
                .requestFactory(factory)
                .build();

        log.info("Gemini provider initialized: model={}", model);
    }

    @Override
    public String generate(String systemPrompt, String userPrompt, Object responseSchema) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))));
        body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", userPrompt)))));
        body.put("generationConfig", Map.of(
                "responseMimeType", "application/json",
                "responseSchema", responseSchema,
                "temperature", 0.1
        ));

        String raw = callApi("/models/" + model + ":generateContent", body);
        return extractJsonFromEnvelope(raw);
    }

    @Override
    public String createCache(String systemPrompt, int ttlSeconds) {
        Map<String, Object> cacheRequest = Map.of(
                "model", "models/" + model,
                "displayName", "clinicos-extraction-prompt",
                "systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))),
                "ttl", ttlSeconds + "s"
        );

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri("/cachedContents")
                    .header("x-goog-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(cacheRequest)
                    .retrieve()
                    .body(Map.class);

            if (response != null && response.containsKey("name")) {
                String cacheId = (String) response.get("name");
                log.info("Gemini cache created: {}", cacheId);
                return cacheId;
            }
        } catch (Exception e) {
            log.error("Failed to create Gemini cache: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public String generateWithCache(String cacheId, String userPrompt, Object responseSchema) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cachedContent", cacheId);
        body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", userPrompt)))));
        body.put("generationConfig", Map.of(
                "responseMimeType", "application/json",
                "responseSchema", responseSchema,
                "temperature", 0.1
        ));

        String raw = callApi("/models/" + model + ":generateContent", body);
        return extractJsonFromEnvelope(raw);
    }

    // ==================== Private ====================

    private String callApi(String uri, Map<String, Object> body) {
        try {
            return restClient.post()
                    .uri(uri)
                    .header("x-goog-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            boolean retryable = (status == 429 || status == 500 || status == 503 || status == 504);
            int delay = parseRetryAfter(e);

            throw new AiProviderException(
                    "Gemini API error: " + status + " " + e.getStatusText(),
                    status, retryable, delay);
        }
    }

    /**
     * Parse Gemini response envelope → extract clean JSON text.
     * Gemini returns: { candidates: [{ content: { parts: [{ text: "..." }] } }] }
     */
    @SuppressWarnings("unchecked")
    private String extractJsonFromEnvelope(String responseBody) {
        if (responseBody == null) return null;
        try {
            Map<String, Object> envelope = objectMapper.readValue(responseBody, Map.class);
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) envelope.get("candidates");
            if (candidates == null || candidates.isEmpty()) return null;

            Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
            List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
            if (parts == null || parts.isEmpty()) return null;

            String text = (String) parts.get(0).get("text");
            return (text != null && !text.isBlank()) ? text : null;
        } catch (Exception e) {
            log.warn("Failed to parse Gemini response envelope: {}", e.getMessage());
            return null;
        }
    }

    private int parseRetryAfter(RestClientResponseException e) {
        String retryAfter = e.getResponseHeaders() != null
                ? e.getResponseHeaders().getFirst("Retry-After")
                : null;
        if (retryAfter != null) {
            try {
                return Integer.parseInt(retryAfter) * 1000;
            } catch (NumberFormatException ignored) {}
        }
        return 0;
    }
}
