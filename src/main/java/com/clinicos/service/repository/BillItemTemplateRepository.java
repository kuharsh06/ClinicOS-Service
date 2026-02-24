package com.clinicos.service.repository;

import com.clinicos.service.entity.BillItemTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BillItemTemplateRepository extends JpaRepository<BillItemTemplate, Integer> {

    Optional<BillItemTemplate> findByUuid(String uuid);

    List<BillItemTemplate> findByOrganizationIdAndIsActiveTrueOrderBySortOrderAsc(Integer orgId);

    List<BillItemTemplate> findByOrganizationIdAndIsDefaultTrueAndIsActiveTrue(Integer orgId);

    List<BillItemTemplate> findByOrganizationId(Integer orgId);
}
