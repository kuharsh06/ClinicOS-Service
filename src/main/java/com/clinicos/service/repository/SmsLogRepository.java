package com.clinicos.service.repository;

import com.clinicos.service.entity.SmsLog;
import com.clinicos.service.enums.SmsStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface SmsLogRepository extends JpaRepository<SmsLog, Integer> {

    Optional<SmsLog> findByUuid(String uuid);

    Optional<SmsLog> findByProviderMsgId(String providerMsgId);

    List<SmsLog> findByOrganizationIdOrderByCreatedAtDesc(Integer orgId);

    List<SmsLog> findByStatus(SmsStatus status);

    List<SmsLog> findByOrganizationIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            Integer orgId, Instant start, Instant end);

    List<SmsLog> findByRecipientPhoneOrderByCreatedAtDesc(String phone);

    long countByOrganizationIdAndStatus(Integer orgId, SmsStatus status);
}
