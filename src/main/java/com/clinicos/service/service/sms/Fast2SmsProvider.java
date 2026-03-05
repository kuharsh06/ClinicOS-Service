package com.clinicos.service.service.sms;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Fast2SMS DLT provider implementation.
 * Activated when clinicos.sms.provider=fast2sms (default).
 */
@Component
@ConditionalOnProperty(name = "clinicos.sms.provider", havingValue = "fast2sms", matchIfMissing = true)
@Slf4j
public class Fast2SmsProvider implements SmsProvider {

    private static final String API_URL = "https://www.fast2sms.com/dev/bulkV2";

    @Value("${clinicos.sms.api-key:}")
    private String apiKey;

    @Value("${clinicos.sms.sender-id:CODRIP}")
    private String senderId;

    @Value("${clinicos.sms.otp-template-id:210499}")
    private int otpTemplateId;

    private RestClient restClient;

    @PostConstruct
    public void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(15));

        this.restClient = RestClient.builder()
                .baseUrl(API_URL)
                .requestFactory(factory)
                .build();

        log.info("Fast2SMS provider initialized: senderId={}, templateId={}", senderId, otpTemplateId);
    }

    @Override
    @SuppressWarnings("unchecked")
    public SmsResult sendOtp(String phoneNumber, String otp) {
        Map<String, Object> body = Map.of(
                "route", "dlt",
                "sender_id", senderId,
                "message", otpTemplateId,
                "variables_values", otp + "|",
                "flash", 0,
                "numbers", phoneNumber
        );

        try {
            Map<String, Object> response = restClient.post()
                    .uri("")
                    .header("authorization", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                log.error("Fast2SMS returned null response for {}", phoneNumber);
                return SmsResult.failure(0, "Null response from Fast2SMS");
            }

            if (Boolean.TRUE.equals(response.get("return"))) {
                String requestId = (String) response.get("request_id");
                log.info("OTP SMS sent to {} via Fast2SMS, request_id: {}", phoneNumber, requestId);
                return SmsResult.success(requestId);
            }

            // Parse API-level error code from response
            int apiStatusCode = parseStatusCode(response);
            String errorMsg = parseErrorMessage(response);
            log.error("Fast2SMS error for {} — code: {}, message: {}", phoneNumber, apiStatusCode, errorMsg);
            return SmsResult.failure(apiStatusCode, errorMsg);

        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            boolean retryable = (status == 429 || status >= 500);
            log.error("Fast2SMS HTTP error for {}: {} {}", phoneNumber, status, e.getStatusText());
            throw new SmsProviderException(
                    "Fast2SMS HTTP error: " + status + " " + e.getStatusText(), status, retryable);
        } catch (SmsProviderException e) {
            throw e;
        } catch (Exception e) {
            log.error("Fast2SMS unexpected error for {}: {}", phoneNumber, e.getMessage());
            throw new SmsProviderException("Fast2SMS error: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private int parseStatusCode(Map<String, Object> response) {
        Object statusCode = response.get("status_code");
        if (statusCode instanceof Number n) {
            return n.intValue();
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private String parseErrorMessage(Map<String, Object> response) {
        // Fast2SMS returns message as array: ["error description"]
        Object msg = response.get("message");
        if (msg instanceof List<?> list && !list.isEmpty()) {
            return list.get(0).toString();
        }
        if (msg instanceof String s) {
            return s;
        }
        return response.toString();
    }
}
