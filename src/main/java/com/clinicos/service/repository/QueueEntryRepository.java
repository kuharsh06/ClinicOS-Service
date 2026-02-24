package com.clinicos.service.repository;

import com.clinicos.service.entity.QueueEntry;
import com.clinicos.service.enums.QueueEntryState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface QueueEntryRepository extends JpaRepository<QueueEntry, Integer> {

    Optional<QueueEntry> findByUuid(String uuid);

    List<QueueEntry> findByQueueIdOrderByPositionAsc(Integer queueId);

    List<QueueEntry> findByQueueIdAndStateOrderByPositionAsc(Integer queueId, QueueEntryState state);

    List<QueueEntry> findByQueueIdAndStateInOrderByPositionAsc(Integer queueId, List<QueueEntryState> states);

    Optional<QueueEntry> findByQueueIdAndPatientId(Integer queueId, Integer patientId);

    @Query("SELECT qe FROM QueueEntry qe WHERE qe.queue.id = :queueId AND qe.state = 'WAITING' ORDER BY qe.position ASC")
    List<QueueEntry> findWaitingEntries(@Param("queueId") Integer queueId);

    @Query("SELECT COUNT(qe) FROM QueueEntry qe WHERE qe.queue.id = :queueId AND qe.state = 'WAITING'")
    long countWaitingEntries(@Param("queueId") Integer queueId);

    List<QueueEntry> findByPatientId(Integer patientId);
}
