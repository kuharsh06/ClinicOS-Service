package com.clinicos.service.repository;

import com.clinicos.service.entity.ComplaintTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ComplaintTagRepository extends JpaRepository<ComplaintTag, Integer> {

    Optional<ComplaintTag> findByUuid(String uuid);

    Optional<ComplaintTag> findByOrganizationIdAndTagKey(Integer orgId, String tagKey);

    List<ComplaintTag> findByOrganizationIdAndIsActiveTrueOrderBySortOrderAsc(Integer orgId);

    List<ComplaintTag> findByOrganizationIdAndIsCommonTrueAndIsActiveTrueOrderBySortOrderAsc(Integer orgId);
}
