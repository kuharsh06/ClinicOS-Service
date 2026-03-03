package com.clinicos.service.audit;

import com.clinicos.service.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Scheduled task to clean up audit logs older than 1 year.
 * DPDP Rule 6 requires minimum 1-year retention — this enforces the maximum.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditRetentionTask {

    private final AuditLogRepository auditLogRepository;

    @Scheduled(cron = "0 0 2 * * *") // Daily at 2:00 AM
    public void purgeExpiredAuditLogs() {
        Instant cutoff = Instant.now().minus(365, ChronoUnit.DAYS);
        long deleted = auditLogRepository.deleteByCreatedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("Audit retention cleanup: deleted {} logs older than 1 year", deleted);
        }
    }
}
