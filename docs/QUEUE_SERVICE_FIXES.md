# QueueService — Fixes Applied

> Documents all fixes applied to `QueueService.java` and related repository files.

---

## 1. Source queue must be ENDED before importing stash

**Severity:** Medium (data integrity)
**File:** `QueueService.java:184-186`

**Problem:** `importStash()` accepted any source queue — including ACTIVE or PAUSED ones. A user could import entries from a queue that's still running, pulling patients out of a live queue.

**Fix:** Added strict validation:
```java
if (sourceQueue.getStatus() != QueueStatus.ENDED) {
    throw new BusinessException("INVALID_SOURCE_QUEUE", "Can only import stash from an ended queue", false);
}
```

---

## 2. N+1 query problem — 91 queries reduced to 1

**Severity:** Medium (performance)
**Files:** `QueueEntryRepository.java`, `QueueService.java:276, 333`

**Problem:** `buildQueueSnapshot()` loaded queue entries with one query, then each call to `buildQueueEntryFull()` triggered 3 separate LAZY-loaded queries per entry (patient, bill, stashedFromQueue). For a queue with 30 patients: 1 + (30 × 3) = 91 SQL queries per API call.

**Example — Before:**
```sql
-- 1 query to get entries
SELECT * FROM queue_entries WHERE queue_id = 5;

-- Then for EACH of the 30 entries:
SELECT * FROM patients WHERE id = 101;
SELECT * FROM bills WHERE id = 201;
SELECT * FROM queues WHERE id = 301;
-- × 30 entries = 90 more queries
```

**Example — After:**
```sql
-- 1 query gets everything
SELECT qe.*, p.*, b.*, sq.*
FROM queue_entries qe
  JOIN patients p ON qe.patient_id = p.id
  LEFT JOIN bills b ON qe.bill_id = b.id
  LEFT JOIN queues sq ON qe.stashed_from_queue_id = sq.id
WHERE qe.queue_id = 5
ORDER BY qe.position ASC;
```

**Fix:** Added two JOIN FETCH queries to `QueueEntryRepository.java`:
- `findByQueueIdWithDetailsOrderByPositionAsc` — for loading active queue entries
- `findByQueueIdAndStateWithDetailsOrderByPositionAsc` — for loading stashed entries by state

Uses `JOIN FETCH` for patient (always exists), `LEFT JOIN FETCH` for bill and stashedFromQueue (nullable).

Applied in:
- `buildQueueSnapshot()` at line 276 — active queue entries
- `findPreviousQueueStash()` at line 333 — stashed entries from last ended queue

---

## 3. Doctor lookup — O(n) scan reduced to O(1) query

**Severity:** Low (performance)
**Files:** `OrgMemberRepository.java`, `QueueService.java:53`

**Problem:** `getActiveQueue()` loaded ALL org members with a JOIN FETCH, then filtered in Java to find one doctor:
```java
orgMemberRepository.findByOrganizationIdWithUser(org.getId())
    .stream()
    .filter(m -> m.getUser().getUuid().equals(doctorUuid))
    .findFirst()
```

For a clinic with 50 members, this fetched 50 rows to find 1.

**Fix:** Added direct query to `OrgMemberRepository.java`:
```java
@Query("SELECT om FROM OrgMember om JOIN FETCH om.user " +
       "WHERE om.organization.id = :orgId AND om.user.uuid = :userUuid")
Optional<OrgMember> findByOrgIdAndUserUuid(Integer orgId, String userUuid);
```

QueueService now uses: `orgMemberRepository.findByOrgIdAndUserUuid(org.getId(), doctorUuid)`

---

## 4. Active/Paused queue lookup — 2 queries merged to 1

**Severity:** Low (performance)
**File:** `QueueService.java:56-58`

**Problem:** Two sequential DB queries to find the active queue:
```java
Optional<Queue> activeQueue = queueRepository.findByDoctorIdAndStatus(doctor.getId(), QueueStatus.ACTIVE);
if (activeQueue.isEmpty()) {
    activeQueue = queueRepository.findByDoctorIdAndStatus(doctor.getId(), QueueStatus.PAUSED);
}
```

**Fix:** Single query using existing `findByDoctorIdAndStatusIn`:
```java
List<Queue> activeQueues = queueRepository.findByDoctorIdAndStatusIn(
    doctor.getId(), List.of(QueueStatus.ACTIVE, QueueStatus.PAUSED));
```

---

## 5. Previous queue stash lookup — load-all-ended replaced with targeted query

**Severity:** Medium (performance, degrades over time)
**Files:** `QueueRepository.java`, `QueueService.java:325-339`

**Problem:** `findPreviousQueueStash()` loaded ALL ended queues for a doctor, sorted them in Java, then iterated each one querying for stashed entries. For a doctor working 6 months (180+ ended queues), this was:
```java
List<Queue> endedQueues = queueRepository.findByDoctorIdAndStatusIn(doctorId, List.of(QueueStatus.ENDED));
endedQueues.sort(...);
for (Queue endedQueue : endedQueues) {
    // query stashed entries for each ended queue until one has results
}
```

**Fix:** Added targeted query to `QueueRepository.java`:
```java
@Query("SELECT q FROM Queue q WHERE q.doctor.id = :doctorId " +
       "AND q.status = 'ENDED' ORDER BY q.endedAt DESC LIMIT 1")
Optional<Queue> findMostRecentEndedQueue(Integer doctorId);
```

`findPreviousQueueStash()` now: 1 query for latest ended queue + 1 query for its stashed entries = 2 queries total (previously unbounded).

---

## 6. Gender returns lowercase instead of uppercase

**Severity:** Low (API consistency)
**File:** `QueueService.java:105, 319`

**Problem:** `patient.getGender().name()` returns Java enum constant names (`MALE`, `FEMALE`, `OTHER`). The rest of the API returns lowercase (`male`, `female`, `other`). The Gender enum has a `getValue()` method that returns the lowercase string.

**Fix:** Replaced `.name()` with `.getValue()` in two places:
- `lookupPatient()` at line 105 — patient lookup response
- `buildQueueEntryFull()` at line 319 — queue entry response

---

## Performance Impact Summary

| Operation | Queries Before | Queries After |
|-----------|---------------|---------------|
| `getActiveQueue` (30 entries) | ~95 (2 queue + 1 entries + 90 lazy + N ended queues) | ~4 (1 queue + 1 entries + 1 ended + 1 stash) |
| Doctor lookup | Loads all members | 1 targeted query |
| Stash lookup | Loads all ended queues + iterates | 2 queries max |
