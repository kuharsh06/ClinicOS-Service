package com.clinicos.service.repository;

import com.clinicos.service.entity.Visit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface VisitRepository extends JpaRepository<Visit, Integer> {

    Optional<Visit> findByUuid(String uuid);

    List<Visit> findByPatientIdOrderByVisitDateDesc(Integer patientId);

    @Query("SELECT v FROM Visit v JOIN FETCH v.createdBy cb JOIN FETCH cb.user " +
            "LEFT JOIN FETCH v.queueEntry " +
            "WHERE v.patient.id = :patientId ORDER BY v.visitDate DESC, v.id DESC")
    List<Visit> findByPatientIdWithDetailsOrderByDateDesc(@Param("patientId") Integer patientId, Pageable pageable);

    @Query("SELECT v FROM Visit v JOIN FETCH v.createdBy cb JOIN FETCH cb.user " +
            "LEFT JOIN FETCH v.queueEntry " +
            "WHERE v.patient.id = :patientId " +
            "AND (v.visitDate < :cursorDate OR (v.visitDate = :cursorDate AND v.id < :cursorId)) " +
            "ORDER BY v.visitDate DESC, v.id DESC")
    List<Visit> findByPatientIdWithDetailsAfterCursor(
            @Param("patientId") Integer patientId, @Param("cursorDate") LocalDate cursorDate,
            @Param("cursorId") Integer cursorId, Pageable pageable);

    List<Visit> findByOrganizationIdAndVisitDate(Integer orgId, LocalDate visitDate);

    List<Visit> findByOrganizationIdAndVisitDateBetween(Integer orgId, LocalDate startDate, LocalDate endDate);

    Optional<Visit> findByQueueEntryId(Integer queueEntryId);

    long countByPatientId(Integer patientId);
}
