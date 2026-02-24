package com.clinicos.service.repository;

import com.clinicos.service.entity.VisitImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VisitImageRepository extends JpaRepository<VisitImage, Integer> {

    Optional<VisitImage> findByUuid(String uuid);

    List<VisitImage> findByVisitIdOrderBySortOrderAsc(Integer visitId);

    long countByVisitId(Integer visitId);
}
