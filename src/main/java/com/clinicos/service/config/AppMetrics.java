package com.clinicos.service.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Central metrics registry — all custom counters and timers in one place.
 * All metrics are in-memory (Micrometer lock-free atomics), zero DB load.
 * Exported via /actuator/prometheus for scraping.
 */
@Component
public class AppMetrics {

    private final MeterRegistry registry;

    public AppMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    // ==================== Sync Events ====================

    public void recordSyncEvent(String eventType, String status, String rejectionCode) {
        Counter.builder("sync.events")
                .description("Sync events processed")
                .tag("type", eventType)
                .tag("status", status)
                .tag("code", rejectionCode != null ? rejectionCode : "none")
                .register(registry)
                .increment();
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void recordSyncDuration(Timer.Sample sample, String eventType, String status) {
        sample.stop(Timer.builder("sync.events.duration")
                .description("Sync event processing duration")
                .tag("type", eventType)
                .tag("status", status)
                .register(registry));
    }

    // ==================== AI Extraction ====================

    public void recordAiExtraction(String status, int attempt) {
        Counter.builder("ai.extraction")
                .description("AI extraction requests")
                .tag("status", status)
                .tag("attempt", String.valueOf(attempt))
                .register(registry)
                .increment();
    }

    public void recordAiDuration(Timer.Sample sample, String status) {
        sample.stop(Timer.builder("ai.extraction.duration")
                .description("AI extraction duration")
                .tag("status", status)
                .register(registry));
    }

    // ==================== Auth ====================

    /**
     * Record auth events with action, status, and reason.
     * Actions: send, verify, refresh, logout, jwt_rejected
     * Status: success, failure
     * Reason: specific error code or "none"
     */
    public void recordAuth(String action, String status, String reason) {
        Counter.builder("auth.events")
                .description("Auth operations")
                .tag("action", action)
                .tag("status", status)
                .tag("reason", reason != null ? reason : "none")
                .register(registry)
                .increment();
    }

    public void recordAuth(String action, String status) {
        recordAuth(action, status, "none");
    }

    // ==================== Queue Operations ====================

    public void recordQueueStarted() {
        Counter.builder("queue.started")
                .description("Queues started")
                .register(registry)
                .increment();
    }

    public void recordQueueEnded() {
        Counter.builder("queue.ended")
                .description("Queues ended")
                .register(registry)
                .increment();
    }

    public void recordQueueAutoCompleted(int count) {
        Counter.builder("queue.auto_completed")
                .description("Patients auto-completed on queue end")
                .register(registry)
                .increment(count);
    }

    public void recordQueueWaitTime(long waitTimeMs) {
        Timer.builder("queue.wait_time")
                .description("Patient wait time (registeredAt to calledAt)")
                .register(registry)
                .record(Duration.ofMillis(waitTimeMs));
    }

    public void recordQueueConsultationTime(long consultTimeMs) {
        Timer.builder("queue.consultation_time")
                .description("Consultation time (calledAt to completedAt)")
                .register(registry)
                .record(Duration.ofMillis(consultTimeMs));
    }

    public void recordQueueDuration(long durationMs) {
        Timer.builder("queue.duration")
                .description("Total queue session duration")
                .register(registry)
                .record(Duration.ofMillis(durationMs));
    }

    public void recordQueuePatientsPerSession(int count) {
        DistributionSummary.builder("queue.patients_per_session")
                .description("Patients seen per queue session")
                .register(registry)
                .record(count);
    }

    // ==================== SMS ====================

    public void recordSms(String status) {
        Counter.builder("sms.send")
                .description("SMS delivery")
                .tag("status", status)
                .register(registry)
                .increment();
    }
}
