package com.clinicos.service.service;

import com.clinicos.service.config.AppMetrics;
import com.clinicos.service.dto.response.ExtractionResponse;
import com.clinicos.service.exception.BusinessException;
import com.clinicos.service.service.ai.AiProvider;
import com.clinicos.service.service.ai.AiProviderException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * AI extraction orchestrator — provider-agnostic.
 * Handles: retry with exponential backoff, optional prompt caching, response validation.
 * The actual AI call is delegated to an AiProvider implementation (Gemini, OpenAI, etc.).
 */
@Service
@Slf4j
public class AiExtractionService {

    private final AiProvider aiProvider;
    private final ObjectMapper objectMapper;
    private final AppMetrics appMetrics;

    @Value("${clinicos.ai.enabled:true}")
    private boolean aiEnabled;

    @Value("${clinicos.ai.max-retries:3}")
    private int maxRetries;

    @Value("${clinicos.ai.cache.enabled:false}")
    private boolean cacheEnabled;

    @Value("${clinicos.ai.cache.ttl:3600}")
    private int cacheTtlSeconds;

    // Cache state (volatile for thread-safety)
    private volatile String cacheId = null;
    private volatile Instant cacheExpiry = null;

    private static final int INITIAL_DELAY_MS = 1000;
    private static final int MAX_DELAY_MS = 8000;
    private static final int JITTER_BOUND_MS = 500;

    // ==================== SYSTEM PROMPT (loaded from resource file) ====================
    // Edit src/main/resources/prompts/extraction-prompt.txt to update the prompt.
    // No code changes needed — just update the file and redeploy.
    private static final String SYSTEM_PROMPT = loadPrompt();

    // ==================== EXTRACTION SCHEMA ====================
    private static final Map<String, Object> EXTRACTION_SCHEMA = buildExtractionSchema();

    public AiExtractionService(AiProvider aiProvider, ObjectMapper objectMapper, AppMetrics appMetrics) {
        this.aiProvider = aiProvider;
        this.objectMapper = objectMapper;
        this.appMetrics = appMetrics;
        log.info("AI extraction service initialized with provider: {}", aiProvider.getClass().getSimpleName());
    }

    /**
     * Extract structured clinical data from a transcript.
     * Retries on transient failures with exponential backoff.
     */
    public ExtractionResponse extract(String transcript, Map<String, Object> currentState) {
        if (!aiEnabled) {
            appMetrics.recordAiExtraction("disabled", 0);
            throw new BusinessException("SERVICE_UNAVAILABLE", "AI extraction is not available", false);
        }

        Timer.Sample timerSample = appMetrics.startTimer();
        String userPrompt = buildUserPrompt(transcript, currentState);

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                String responseBody = callProvider(userPrompt);
                ExtractionResponse result = parseAndValidate(responseBody);

                if (result != null) {
                    appMetrics.recordAiExtraction("success", attempt);
                    appMetrics.recordAiDuration(timerSample, "success");
                    log.info("AI extraction succeeded on attempt {}", attempt);
                    return result;
                }

                // Malformed response — retry
                appMetrics.recordAiExtraction("malformed", attempt);
                log.warn("Attempt {}/{}: Malformed response from AI provider, retrying", attempt, maxRetries);

            } catch (AiProviderException e) {
                if (!e.isRetryable()) {
                    appMetrics.recordAiExtraction("error", attempt);
                    appMetrics.recordAiDuration(timerSample, "error");
                    log.error("Non-retryable AI error: {} (status {})", e.getMessage(), e.getStatusCode());
                    throw new BusinessException("AI_ERROR", "AI extraction failed: " + e.getMessage(), false);
                }

                appMetrics.recordAiExtraction("retry", attempt);
                int delay = e.getRetryDelayMs() > 0 ? e.getRetryDelayMs() : calculateBackoff(attempt);
                log.warn("Attempt {}/{}: {} (status {}), retrying in {}ms",
                        attempt, maxRetries, e.getMessage(), e.getStatusCode(), delay);
                sleep(delay);

            } catch (Exception e) {
                appMetrics.recordAiExtraction("unexpected_error", attempt);
                int delay = calculateBackoff(attempt);
                log.warn("Attempt {}/{}: Unexpected error ({}), retrying in {}ms",
                        attempt, maxRetries, e.getMessage(), delay);
                sleep(delay);
            }
        }

        appMetrics.recordAiExtraction("exhausted", maxRetries);
        appMetrics.recordAiDuration(timerSample, "error");
        throw new BusinessException("AI_ERROR", "AI extraction failed after " + maxRetries + " attempts", true);
    }

    // ==================== Provider Call (with cache orchestration) ====================

    private String callProvider(String userPrompt) {
        if (cacheEnabled) {
            String cache = getOrCreateCacheSafe();
            if (cache != null) {
                try {
                    return aiProvider.generateWithCache(cache, userPrompt, EXTRACTION_SCHEMA);
                } catch (AiProviderException e) {
                    if (e.getStatusCode() == 404) {
                        // Cache expired between check and use — reset and fall through
                        log.warn("AI cache expired (404), falling back to inline prompt");
                        cacheId = null;
                        cacheExpiry = null;
                    } else {
                        throw e;
                    }
                } catch (UnsupportedOperationException e) {
                    log.info("AI provider does not support caching, using inline prompt");
                }
            }
        }

        return aiProvider.generate(SYSTEM_PROMPT, userPrompt, EXTRACTION_SCHEMA);
    }

    // ==================== User Prompt Building ====================

    private String buildUserPrompt(String transcript, Map<String, Object> currentState) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Extract structured medical data from this transcript:\n\n");
        prompt.append(transcript);

        if (currentState != null && !currentState.isEmpty()) {
            try {
                prompt.append("\n\nCURRENT CONSULTATION STATE (merge new dictation into this):\n");
                prompt.append(objectMapper.writeValueAsString(currentState));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize currentState, ignoring: {}", e.getMessage());
            }
        }

        return prompt.toString();
    }

    // ==================== Cache Management ====================

    private String getOrCreateCacheSafe() {
        try {
            return getOrCreateCache();
        } catch (Exception e) {
            log.warn("Cache operation failed, falling back to inline prompt: {}", e.getMessage());
            return null;
        }
    }

    private String getOrCreateCache() {
        // Check if existing cache is still valid (with safety margin = 10% of TTL, min 30s)
        long safetyMarginSeconds = Math.max(30, cacheTtlSeconds / 10);
        if (cacheId != null && cacheExpiry != null
                && Instant.now().isBefore(cacheExpiry.minus(safetyMarginSeconds, ChronoUnit.SECONDS))) {
            return cacheId;
        }

        log.info("Creating AI prompt cache (TTL: {}s)", cacheTtlSeconds);
        String newCacheId = aiProvider.createCache(SYSTEM_PROMPT, cacheTtlSeconds);

        if (newCacheId != null) {
            cacheId = newCacheId;
            cacheExpiry = Instant.now().plus(cacheTtlSeconds, ChronoUnit.SECONDS);
            log.info("AI cache created: {} (expires: {})", cacheId, cacheExpiry);
            return cacheId;
        }

        return null;
    }

    // ==================== Response Parsing & Validation ====================

    private ExtractionResponse parseAndValidate(String jsonText) {
        try {
            if (jsonText == null || jsonText.isBlank()) {
                log.warn("Empty response from AI provider");
                return null;
            }

            ExtractionResponse result = objectMapper.readValue(jsonText, ExtractionResponse.class);
            return validate(result);

        } catch (Exception e) {
            log.warn("Failed to parse AI response: {}", e.getMessage());
            return null;
        }
    }

    private ExtractionResponse validate(ExtractionResponse result) {
        if (result == null) return null;

        if (result.getPrescriptions() == null) {
            result.setPrescriptions(List.of());
        }
        if (result.getVitals() == null) {
            result.setVitals(ExtractionResponse.Vitals.builder().build());
        }
        if (result.getLabOrders() == null) {
            result.setLabOrders(List.of());
        }

        // Filter out entries with empty required fields
        result.setPrescriptions(
                result.getPrescriptions().stream()
                        .filter(p -> p.getMedicineName() != null && !p.getMedicineName().isBlank())
                        .toList()
        );
        result.setLabOrders(
                result.getLabOrders().stream()
                        .filter(l -> l.getTestName() != null && !l.getTestName().isBlank())
                        .toList()
        );

        return result;
    }

    // ==================== Retry Helpers ====================

    private int calculateBackoff(int attempt) {
        int base = INITIAL_DELAY_MS * (1 << (attempt - 1));
        int jitter = ThreadLocalRandom.current().nextInt(0, JITTER_BOUND_MS);
        return Math.min(base + jitter, MAX_DELAY_MS);
    }

    private void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================== Extraction Schema ====================

    private static String loadPrompt() {
        try {
            return new String(
                    AiExtractionService.class.getResourceAsStream("/prompts/extraction-prompt.txt").readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to load extraction prompt from /prompts/extraction-prompt.txt", e);
        }
    }

    private static Map<String, Object> buildExtractionSchema() {
        return Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "prescriptions", Map.of(
                                "type", "ARRAY",
                                "items", Map.of(
                                        "type", "OBJECT",
                                        "properties", Map.of(
                                                "medicineName", Map.of("type", "STRING"),
                                                "dosage", Map.of("type", "STRING"),
                                                "frequency", Map.of("type", "STRING"),
                                                "timing", Map.of("type", "STRING"),
                                                "duration", Map.of("type", "STRING")
                                        ),
                                        "required", List.of("medicineName", "dosage", "frequency", "timing", "duration")
                                )
                        ),
                        "vitals", Map.of(
                                "type", "OBJECT",
                                "properties", Map.of(
                                        "bp", Map.of("type", "STRING"),
                                        "pulse", Map.of("type", "STRING"),
                                        "temp", Map.of("type", "STRING"),
                                        "weight", Map.of("type", "STRING"),
                                        "spo2", Map.of("type", "STRING")
                                )
                        ),
                        "labOrders", Map.of(
                                "type", "ARRAY",
                                "items", Map.of(
                                        "type", "OBJECT",
                                        "properties", Map.of(
                                                "testName", Map.of("type", "STRING"),
                                                "type", Map.of("type", "STRING"),
                                                "notes", Map.of("type", "STRING")
                                        ),
                                        "required", List.of("testName", "type")
                                )
                        ),
                        "followUp", Map.of(
                                "type", "OBJECT",
                                "nullable", true,
                                "properties", Map.of(
                                        "days", Map.of("type", "INTEGER"),
                                        "notes", Map.of("type", "STRING")
                                )
                        ),
                        "examination", Map.of("type", "STRING")
                ),
                "required", List.of("prescriptions", "vitals", "labOrders", "followUp")
        );
    }
}
