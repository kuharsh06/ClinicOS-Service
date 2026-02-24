package com.clinicos.service.repository;

import com.clinicos.service.entity.SmsTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SmsTemplateRepository extends JpaRepository<SmsTemplate, Integer> {

    Optional<SmsTemplate> findByUuid(String uuid);

    Optional<SmsTemplate> findByOrganizationIdAndTemplateKeyAndDeletedAtIsNull(Integer orgId, String templateKey);

    List<SmsTemplate> findByOrganizationIdAndIsActiveTrueAndDeletedAtIsNull(Integer orgId);

    List<SmsTemplate> findByOrganizationIdAndDeletedAtIsNull(Integer orgId);
}
