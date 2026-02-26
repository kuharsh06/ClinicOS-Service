package com.clinicos.service.repository;

import com.clinicos.service.entity.EventStore;
import com.clinicos.service.enums.EventStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EventStoreRepository extends JpaRepository<EventStore, Integer> {

    Optional<EventStore> findByUuid(String uuid);

    List<EventStore> findByOrganizationIdAndServerReceivedAtGreaterThanOrderByServerReceivedAtAsc(
            Integer orgId, Long afterTimestamp);

    List<EventStore> findByOrganizationIdAndStatus(Integer orgId, EventStatus status);

    List<EventStore> findByUserIdOrderByDeviceTimestampDesc(Integer userId);

    @Query("SELECT MAX(e.serverReceivedAt) FROM EventStore e WHERE e.organization.id = :orgId AND e.status = 'APPLIED'")
    Long findLatestEventTimestamp(@Param("orgId") Integer orgId);

    @Query("SELECT e FROM EventStore e WHERE e.organization.id = :orgId " +
            "AND e.serverReceivedAt > :since " +
            "AND e.deviceId != :excludeDeviceId " +
            "AND e.status = 'APPLIED' " +
            "ORDER BY e.serverReceivedAt ASC")
    List<EventStore> findEventsForPull(
            @Param("orgId") Integer orgId,
            @Param("since") Long since,
            @Param("excludeDeviceId") String excludeDeviceId,
            Pageable pageable);
}
