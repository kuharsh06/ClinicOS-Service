package com.clinicos.service.repository;

import com.clinicos.service.entity.EventRoleSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EventRoleSnapshotRepository extends JpaRepository<EventRoleSnapshot, Integer> {

    Optional<EventRoleSnapshot> findByUuid(String uuid);

    List<EventRoleSnapshot> findByEventId(Integer eventId);
}
