package com.clinicos.service.task;

import com.clinicos.service.entity.OrgMember;
import com.clinicos.service.entity.User;
import com.clinicos.service.repository.BillRepository;
import com.clinicos.service.repository.OrgMemberRepository;
import com.clinicos.service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * Scheduled task to permanently anonymize PII for users past their 30-day grace period.
 * Runs daily at 2:30 AM (after AuditRetentionTask at 2:00 AM).
 * Each user is processed in its own transaction — one failure won't roll back others.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AccountDeletionTask {

    /** Prefix for anonymized phone numbers. Keep in sync with UserRepository.findUsersAwaitingPermanentDeletion JPQL. */
    static final String DELETED_PHONE_PREFIX = "del_";

    private final UserRepository userRepository;
    private final OrgMemberRepository orgMemberRepository;
    private final BillRepository billRepository;
    private final PlatformTransactionManager transactionManager;

    @Scheduled(cron = "0 30 2 * * *")
    public void permanentlyDeleteExpiredAccounts() {
        List<User> users = userRepository.findUsersAwaitingPermanentDeletion(Instant.now());

        if (users.isEmpty()) {
            return;
        }

        log.info("Processing permanent deletion for {} user(s)", users.size());

        int success = 0;
        int failed = 0;

        // Collect IDs only — entities are detached and must NOT be used inside transactions
        List<Integer> userIds = users.stream().map(User::getId).toList();

        for (Integer userId : userIds) {
            try {
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                tx.executeWithoutResult(status -> anonymizeUser(userId));
                success++;
            } catch (Exception e) {
                failed++;
                log.error("Failed to anonymize user id {}: {}", userId, e.getMessage(), e);
            }
        }

        log.info("Permanent deletion complete: {} succeeded, {} failed", success, failed);
    }

    private void anonymizeUser(Integer userId) {
        // Re-fetch inside transaction to get fresh state (guards against support cancellation race)
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getDeletedAt() == null || user.getPhone().startsWith(DELETED_PHONE_PREFIX)) {
            return; // Already anonymized, cancelled, or deleted
        }

        String originalUuid = user.getUuid();

        // 1. Anonymize denormalized bill.doctorName
        List<OrgMember> memberships = orgMemberRepository.findByUserId(userId);
        List<Integer> memberIds = memberships.stream()
                .map(OrgMember::getId)
                .toList();

        if (!memberIds.isEmpty()) {
            int billsAnonymized = billRepository.anonymizeDoctorNameByMemberIds(memberIds);
            if (billsAnonymized > 0) {
                log.info("Anonymized doctorName in {} bill(s) for user {}", billsAnonymized, originalUuid);
            }
        }

        // 2. Anonymize user PII
        // Phone format: "del_<userId>" — max 14 chars, fits VARCHAR(15), unique per user
        user.setPhone(DELETED_PHONE_PREFIX + userId);
        user.setCountryCode("XX");
        user.setName("Deleted User");
        userRepository.save(user);

        log.info("Permanently anonymized user {}", originalUuid);
    }
}
