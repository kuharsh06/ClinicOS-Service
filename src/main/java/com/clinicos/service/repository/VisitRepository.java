package com.clinicos.service.repository;

import com.clinicos.service.entity.Visit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface VisitRepository extends JpaRepository<Visit, Integer> {

    Optional<Visit> findByUuid(String uuid);

    List<Visit> findByPatientIdOrderByVisitDateDesc(Integer patientId);

    List<Visit> findByOrganizationIdAndVisitDate(Integer orgId, LocalDate visitDate);

    List<Visit> findByOrganizationIdAndVisitDateBetween(Integer orgId, LocalDate startDate, LocalDate endDate);

    Optional<Visit> findByQueueEntryId(Integer queueEntryId);

    long countByPatientId(Integer patientId);
}
