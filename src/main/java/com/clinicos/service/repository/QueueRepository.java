package com.clinicos.service.repository;

import com.clinicos.service.entity.Queue;
import com.clinicos.service.enums.QueueStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface QueueRepository extends JpaRepository<Queue, Integer> {

    Optional<Queue> findByUuid(String uuid);

    List<Queue> findByOrganizationId(Integer orgId);

    List<Queue> findByOrganizationIdAndStatus(Integer orgId, QueueStatus status);

    Optional<Queue> findByDoctorIdAndStatus(Integer doctorId, QueueStatus status);

    List<Queue> findByDoctorIdAndStatusIn(Integer doctorId, List<QueueStatus> statuses);

    @Query("SELECT q FROM Queue q WHERE q.doctor.id = :doctorId AND q.status = 'ENDED' ORDER BY q.endedAt DESC LIMIT 1")
    Optional<Queue> findMostRecentEndedQueue(@Param("doctorId") Integer doctorId);
}
