package com.clinicos.service.controller;

import com.clinicos.service.dto.response.ApiResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Admin stats endpoint — reads from in-memory Micrometer counters.
 * Zero DB load. Counters reset on restart.
 * For persistent history, scrape /actuator/prometheus with Prometheus + Grafana.
 */
@RestController
@RequestMapping("/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final MeterRegistry meterRegistry;

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();

        stats.put("syncEvents", buildSyncStats());
        stats.put("queue", buildQueueStats());
        stats.put("aiExtraction", buildAiStats());
        stats.put("auth", buildAuthStats());
        stats.put("sms", buildSmsStats());
        stats.put("http", buildHttpStats());

        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    private Map<String, Object> buildSyncStats() {
        Map<String, Object> sync = new LinkedHashMap<>();
        Map<String, Map<String, Object>> byType = new LinkedHashMap<>();

        long totalApplied = 0;
        long totalRejected = 0;
        long totalIdempotent = 0;
        Map<String, Long> rejectionCodes = new LinkedHashMap<>();

        Collection<Meter> meters = meterRegistry.find("sync.events").meters();
        for (Meter meter : meters) {
            if (meter instanceof Counter counter) {
                String type = counter.getId().getTag("type");
                String status = counter.getId().getTag("status");
                String code = counter.getId().getTag("code");
                long count = (long) counter.count();

                if (count == 0) continue;

                byType.computeIfAbsent(type, k -> new LinkedHashMap<>());
                Map<String, Object> typeStats = byType.get(type);

                if ("applied".equals(status)) {
                    typeStats.merge("applied", count, (a, b) -> (long) a + (long) b);
                    totalApplied += count;
                    if ("IDEMPOTENT".equals(code)) {
                        totalIdempotent += count;
                    }
                } else if ("rejected".equals(status)) {
                    typeStats.merge("rejected", count, (a, b) -> (long) a + (long) b);
                    totalRejected += count;
                    if (code != null && !"none".equals(code)) {
                        rejectionCodes.merge(code, count, Long::sum);
                    }
                }
            }
        }

        // Add duration stats per type
        Collection<Meter> timerMeters = meterRegistry.find("sync.events.duration").meters();
        for (Meter meter : timerMeters) {
            if (meter instanceof Timer timer) {
                String type = timer.getId().getTag("type");
                if (type != null && byType.containsKey(type)) {
                    Map<String, Object> typeStats = byType.get(type);
                    double avgMs = timer.mean(TimeUnit.MILLISECONDS);
                    double maxMs = timer.max(TimeUnit.MILLISECONDS);
                    if (avgMs > 0) {
                        typeStats.put("avgMs", Math.round(avgMs * 100.0) / 100.0);
                        typeStats.put("maxMs", Math.round(maxMs * 100.0) / 100.0);
                    }
                }
            }
        }

        sync.put("total", totalApplied + totalRejected);
        sync.put("applied", totalApplied);
        sync.put("rejected", totalRejected);
        sync.put("successRate", totalApplied + totalRejected > 0
                ? Math.round(totalApplied * 10000.0 / (totalApplied + totalRejected)) / 100.0 + "%"
                : "N/A");
        sync.put("idempotent", totalIdempotent);
        sync.put("rejectionCodes", rejectionCodes);
        sync.put("byType", byType);

        return sync;
    }

    private Map<String, Object> buildQueueStats() {
        Map<String, Object> queue = new LinkedHashMap<>();

        Counter started = meterRegistry.find("queue.started").counter();
        Counter ended = meterRegistry.find("queue.ended").counter();
        Counter autoCompleted = meterRegistry.find("queue.auto_completed").counter();

        queue.put("started", started != null ? (long) started.count() : 0L);
        queue.put("ended", ended != null ? (long) ended.count() : 0L);
        queue.put("autoCompleted", autoCompleted != null ? (long) autoCompleted.count() : 0L);

        Timer waitTime = meterRegistry.find("queue.wait_time").timer();
        if (waitTime != null && waitTime.count() > 0) {
            queue.put("avgWaitTimeMins", Math.round(waitTime.mean(TimeUnit.MINUTES) * 100.0) / 100.0);
            queue.put("maxWaitTimeMins", Math.round(waitTime.max(TimeUnit.MINUTES) * 100.0) / 100.0);
        }

        Timer consultTime = meterRegistry.find("queue.consultation_time").timer();
        if (consultTime != null && consultTime.count() > 0) {
            queue.put("avgConsultationMins", Math.round(consultTime.mean(TimeUnit.MINUTES) * 100.0) / 100.0);
            queue.put("maxConsultationMins", Math.round(consultTime.max(TimeUnit.MINUTES) * 100.0) / 100.0);
        }

        Timer duration = meterRegistry.find("queue.duration").timer();
        if (duration != null && duration.count() > 0) {
            queue.put("avgSessionMins", Math.round(duration.mean(TimeUnit.MINUTES) * 100.0) / 100.0);
            queue.put("maxSessionMins", Math.round(duration.max(TimeUnit.MINUTES) * 100.0) / 100.0);
        }

        DistributionSummary patients = meterRegistry.find("queue.patients_per_session").summary();
        if (patients != null && patients.count() > 0) {
            queue.put("avgPatientsPerSession", Math.round(patients.mean() * 100.0) / 100.0);
            queue.put("maxPatientsPerSession", (int) patients.max());
        }

        return queue;
    }

    private Map<String, Object> buildAiStats() {
        Map<String, Object> ai = new LinkedHashMap<>();

        long success = 0, errors = 0, retries = 0, unexpectedErrors = 0, malformed = 0, exhausted = 0, disabled = 0;

        Collection<Meter> meters = meterRegistry.find("ai.extraction").meters();
        for (Meter meter : meters) {
            if (meter instanceof Counter counter) {
                String status = counter.getId().getTag("status");
                long count = (long) counter.count();
                if (status == null) continue;
                switch (status) {
                    case "success" -> success += count;
                    case "error" -> errors += count;
                    case "retry" -> retries += count;
                    case "unexpected_error" -> unexpectedErrors += count;
                    case "malformed" -> malformed += count;
                    case "exhausted" -> exhausted += count;
                    case "disabled" -> disabled += count;
                }
            }
        }

        long totalRequests = success + errors + exhausted;
        ai.put("totalRequests", totalRequests);
        ai.put("success", success);
        ai.put("errors", errors);
        ai.put("exhausted", exhausted);
        ai.put("retries", retries);
        ai.put("unexpectedErrors", unexpectedErrors);
        ai.put("malformed", malformed);
        if (disabled > 0) {
            ai.put("disabled", disabled);
        }
        ai.put("successRate", totalRequests > 0
                ? Math.round(success * 10000.0 / totalRequests) / 100.0 + "%"
                : "N/A");

        // Duration
        Timer successTimer = meterRegistry.find("ai.extraction.duration").tag("status", "success").timer();
        if (successTimer != null && successTimer.count() > 0) {
            ai.put("avgDurationMs", Math.round(successTimer.mean(TimeUnit.MILLISECONDS)));
            ai.put("maxDurationMs", Math.round(successTimer.max(TimeUnit.MILLISECONDS)));
        }

        return ai;
    }

    private Map<String, Object> buildAuthStats() {
        Map<String, Object> auth = new LinkedHashMap<>();

        // Group by action → reason → count
        Map<String, Map<String, Long>> actionDetails = new LinkedHashMap<>();
        Map<String, Long> actionTotals = new LinkedHashMap<>();

        Collection<Meter> meters = meterRegistry.find("auth.events").meters();
        for (Meter meter : meters) {
            if (meter instanceof Counter counter) {
                String action = counter.getId().getTag("action");
                String status = counter.getId().getTag("status");
                String reason = counter.getId().getTag("reason");
                long count = (long) counter.count();
                if (count == 0) continue;

                String key = status + ("none".equals(reason) ? "" : ":" + reason);
                actionDetails.computeIfAbsent(action, k -> new LinkedHashMap<>());
                actionDetails.get(action).merge(key, count, Long::sum);
                actionTotals.merge(action, count, Long::sum);
            }
        }

        auth.put("otpSend", actionDetails.getOrDefault("send", Map.of()));
        auth.put("otpVerify", actionDetails.getOrDefault("verify", Map.of()));
        auth.put("tokenRefresh", actionDetails.getOrDefault("refresh", Map.of()));
        auth.put("logout", actionTotals.getOrDefault("logout", 0L));
        auth.put("jwtRejected", actionDetails.getOrDefault("jwt_rejected", Map.of()));
        auth.put("accessDenied", actionDetails.getOrDefault("access_denied", Map.of()));

        return auth;
    }

    private Map<String, Object> buildSmsStats() {
        Map<String, Object> sms = new LinkedHashMap<>();

        Collection<Meter> meters = meterRegistry.find("sms.send").meters();
        for (Meter meter : meters) {
            if (meter instanceof Counter counter) {
                String status = counter.getId().getTag("status");
                long count = (long) counter.count();
                if (count > 0) {
                    sms.put(status, count);
                }
            }
        }

        return sms;
    }

    private Map<String, Object> buildHttpStats() {
        Map<String, Object> http = new LinkedHashMap<>();
        Map<String, Map<String, Object>> endpoints = new LinkedHashMap<>();

        // Track weighted totals for correct avgMs calculation
        Map<String, Double> totalTimeMs = new LinkedHashMap<>();
        Map<String, Double> maxTimeMs = new LinkedHashMap<>();

        Collection<Meter> meters = meterRegistry.find("http.server.requests").meters();
        for (Meter meter : meters) {
            if (meter instanceof Timer timer && timer.count() > 0) {
                String uri = timer.getId().getTag("uri");
                String status = timer.getId().getTag("status");
                String method = timer.getId().getTag("method");
                if (uri == null || uri.contains("actuator")) continue;

                String key = method + " " + uri;
                Map<String, Object> ep = endpoints.computeIfAbsent(key, k -> new LinkedHashMap<>());

                long count = timer.count();
                double avgMs = timer.mean(TimeUnit.MILLISECONDS);
                double maxMs = timer.max(TimeUnit.MILLISECONDS);

                ep.merge("totalRequests", count, (a, b) -> (long) a + (long) b);
                totalTimeMs.merge(key, avgMs * count, Double::sum);
                maxTimeMs.merge(key, maxMs, Math::max);

                if (status != null && status.startsWith("5")) {
                    ep.merge("5xx", count, (a, b) -> (long) a + (long) b);
                } else if (status != null && status.startsWith("4")) {
                    ep.merge("4xx", count, (a, b) -> (long) a + (long) b);
                }

                // Per-status-code timing breakdown
                if (status != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> byStatus = (Map<String, Object>) ep.computeIfAbsent(
                            "byStatus", k -> new LinkedHashMap<>());
                    Map<String, Object> statusStats = new LinkedHashMap<>();
                    statusStats.put("count", count);
                    statusStats.put("avgMs", Math.round(avgMs * 100.0) / 100.0);
                    statusStats.put("maxMs", Math.round(maxMs * 100.0) / 100.0);
                    byStatus.put(status, statusStats);
                }
            }
        }

        // Compute correct weighted avgMs and true maxMs per endpoint
        for (Map.Entry<String, Map<String, Object>> entry : endpoints.entrySet()) {
            String key = entry.getKey();
            Map<String, Object> ep = entry.getValue();
            long total = (long) ep.get("totalRequests");
            if (total > 0 && totalTimeMs.containsKey(key)) {
                ep.put("avgMs", Math.round(totalTimeMs.get(key) / total * 100.0) / 100.0);
            }
            if (maxTimeMs.containsKey(key)) {
                ep.put("maxMs", Math.round(maxTimeMs.get(key) * 100.0) / 100.0);
            }
        }

        http.put("endpoints", endpoints);
        return http;
    }
}
