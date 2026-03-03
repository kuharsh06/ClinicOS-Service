package com.clinicos.service.service.ai;

/**
 * Abstraction for AI model providers (Gemini, OpenAI, Claude, etc.).
 * Swap providers by changing clinicos.ai.provider property.
 */
public interface AiProvider {

    /**
     * Send a structured extraction request to the AI model.
     * Provider MUST parse its own response envelope and return ONLY the clean extracted JSON.
     *
     * @param systemPrompt  the system instructions (medical reference, rules)
     * @param userPrompt    the user message (transcript + optional current state)
     * @param responseSchema the JSON schema for structured output (provider translates format if needed)
     * @return clean JSON string of the extracted data (NOT the raw API envelope)
     * @throws AiProviderException on errors (retryable or not)
     */
    String generate(String systemPrompt, String userPrompt, Object responseSchema);

    /**
     * Create or refresh a prompt cache (if supported by the provider).
     * Returns cache ID, or null if caching is not supported/disabled.
     */
    default String createCache(String systemPrompt, int ttlSeconds) {
        return null; // Default: no caching support
    }

    /**
     * Send a cached extraction request (uses cached system prompt).
     * Falls back to generate() if caching not supported.
     */
    default String generateWithCache(String cacheId, String userPrompt, Object responseSchema) {
        throw new UnsupportedOperationException("Caching not supported by this provider");
    }
}
