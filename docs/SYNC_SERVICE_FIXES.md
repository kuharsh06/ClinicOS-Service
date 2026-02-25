# SyncService — Fixes & Remaining Issues

> Tracks critical fixes applied and medium issues pending for `SyncService.java`

---

## Critical Issues Fixed

### 1. Event saved as APPLIED before processing succeeds

**Commit:** `7c4232a`

**Problem:** Events were saved with `status=APPLIED` before `processEvent()` ran. If processing failed, the `event_store` row stayed APPLIED while domain tables were never updated — data inconsistency.

**Fix:** Save as `PENDING` first, then update to `APPLIED` on success or `REJECTED` (with code + reason) on failure. Inner try/catch around `processEvent()` handles the lifecycle.

**Lines changed:** `SyncService.java:132-181`

---

### 2. No state guards on queue entry transitions

**Commit:** `5dd186c`

**Problem:** Handlers like `call_now`, `mark_complete`, `patient_removed`, `step_out` blindly set the new state without checking the current state. This allowed invalid transitions like calling a COMPLETED patient or removing an already STASHED entry.

**Fix:** Added `VALID_TRANSITIONS` map encoding the state machine and `guardStateTransition()` check before every `setState()` call. Invalid transitions throw RuntimeException, caught by the inner try/catch which marks the event as REJECTED.

**State machine:**
```
WAITING     → CALLED, REMOVED, STASHED, STEPPED_OUT
CALLED      → COMPLETED, REMOVED, STEPPED_OUT
STEPPED_OUT → WAITING, REMOVED
STASHED     → WAITING (via stash import)
COMPLETED   → (terminal)
REMOVED     → (terminal)
```

**Lines changed:** `SyncService.java:46-54` (map), `SyncService.java:371-384` (guard method), lines 396, 413, 434, 451 (guard calls)

---

### 3. Server time used instead of device time in event handlers

**Commit:** `0d388bc`

**Problem:** In an offline-first system, events may sync hours after they happen. Three handlers used server time (`System.currentTimeMillis()`, `Instant.now()`, `LocalDate.now()`) instead of `event.getDeviceTimestamp()`, causing incorrect data when events synced late.

**Fix:** All three now derive timestamps from `event.getDeviceTimestamp()` with server time as fallback.

| Handler | Field | Before (wrong) | After (correct) |
|---------|-------|-----------------|------------------|
| `processQueueResumed` | pause duration | `System.currentTimeMillis() - pauseStart` | `deviceTimestamp - pauseStart` |
| `processMarkComplete` | `patient.lastVisitDate` | `LocalDate.now()` | `Instant.ofEpochMilli(deviceTimestamp)` converted to `Asia/Kolkata` zone |
| `processQueueEnded` | `queue.endedAt` | `Instant.now()` | `Instant.ofEpochMilli(deviceTimestamp)` |

**Lines changed:** `SyncService.java:439-441`, `SyncService.java:470-477`, `SyncService.java:497-499`

---

### 4. Pull query returned REJECTED events to other devices

**Commit:** `d7a95aa`

**Problem:** The `findEventsForPull` query had no status filter. After fix #1 introduced PENDING→APPLIED/REJECTED lifecycle, REJECTED events (failed processing, invalid transitions) were being returned to other devices. Since the frontend replays events blindly, rejected events would be applied locally even though the server rejected them.

**Fix:** Added `AND e.status = 'APPLIED'` filter to the pull query in `EventStoreRepository.java`.

**Lines changed:** `EventStoreRepository.java:29`

---

### 5. State machine, guard consistency, and null org validation

**Commit:** (this commit)

Three low-severity hardening fixes:

**5a. `STASHED → REMOVED` missing from state machine**

When stashed entries are imported, the original is marked REMOVED. But VALID_TRANSITIONS only allowed `STASHED → WAITING`. Added `REMOVED` to STASHED's allowed set.

**Lines changed:** `SyncService.java:51`

**5b. `processQueueEnded` and `processStashImported` now use VALID_TRANSITIONS for skip logic**

Previously used manual `entry.getState() == WAITING` / `== STASHED` checks. Now uses `VALID_TRANSITIONS` map to determine if the transition is allowed, keeping batch skip behavior (continue instead of throw) while staying consistent with the state machine. The `STASHED → REMOVED` transition in stash import now also uses `guardStateTransition()`.

**Lines changed:** `SyncService.java:560-567`, `SyncService.java:615-647`

**5c. Early fail if user has no organization**

Previously, `org` was set to null and passed into handlers that would NullPointerException on `org.getId()`. Now throws a clear error upfront: "Cannot sync events without an organization".

**Lines changed:** `SyncService.java:82-86`

---

### 6. visit_saved handler — security & exception fixes

**Commit:** (pending)

Four issues found in the `processVisitSaved` handler:

**6a. Cross-org patient validation missing (SECURITY)**

Patient is looked up by UUID but never validated against the current org. A doctor from Org A could create a visit for a patient belonging to Org B.

```java
// Before: no org check
Patient patient = patientRepository.findByUuid(patientId).orElseThrow(...);

// After: validate org ownership
Patient patient = patientRepository.findByUuid(patientId).orElseThrow(...);
if (!patient.getOrganization().getId().equals(org.getId())) {
    throw new ResourceNotFoundException("Patient", patientId);
}
```

**6b. Cross-org queue entry validation missing (SECURITY)**

Queue entry looked up by UUID without org ownership check. Could link a visit to a queue entry from another org.

```java
// Before: no org check
queueEntry = queueEntryRepository.findByUuid(queueEntryId).orElse(null);

// After: validate org ownership
queueEntry = queueEntryRepository.findByUuid(queueEntryId).orElse(null);
if (queueEntry != null && !queueEntry.getQueue().getOrganization().getId().equals(org.getId())) {
    queueEntry = null; // silently ignore cross-org entry
}
```

**6c. Cross-org visit update validation missing (SECURITY)**

On update path, existing visit found by UUID without checking it belongs to the current org.

```java
// Before: no org check
Visit existingVisit = visitRepository.findByUuid(visitId).orElse(null);
if (existingVisit != null) { existingVisit.setData(...); }

// After: validate org ownership
if (existingVisit != null && !existingVisit.getOrganization().getId().equals(org.getId())) {
    throw new ResourceNotFoundException("Visit", visitId);
}
```

**6d. Cross-patient visit update (SECURITY)**

On update path, the visit's patient was not validated against `targetEntity`. A doctor could target patient B but update a visit belonging to patient A by sending patient A's visitId.

```java
// After: validate visit belongs to target patient
if (!existingVisit.getPatient().getUuid().equals(patientId)) {
    throw new IllegalArgumentException("Visit does not belong to patient");
}
```

**6e. Missing visitId validation**

When `visitId` is null or blank, the code would create a ghost visit with a random UUID that the client can never reference. Added early validation.

**6f. RuntimeException instead of typed exceptions**

All `orElseThrow` calls use `RuntimeException` with generic messages. Changed to `ResourceNotFoundException` for consistency and better debugging.

---

### Known Accepted Behaviors (visit_saved)

| Item | Decision | Reason |
|------|----------|--------|
| Any doctor can update another doctor's visit | **Allowed** | Multi-doctor clinics may need this |
| visitDate not updated on edit | **By design** | Visit date = when it happened, not last edit |
| Patient lastComplaintTags not refreshed on update | **Skipped** | Low severity, minor UX stale data |

---

## Medium Issues (Pending)

### M1. `userRoles` always empty in sync pull response

**Severity:** Medium
**Files:** `SyncService.java`

**Problem:** Both push and pull sides of the role snapshot are broken:

- **Push side (line 150):** When an event is saved to `event_store`, nobody writes to the `event_role_snapshot` table. The entity, repository, and DB table all exist but are never used.
- **Pull side (line 665-666):** `convertToDto()` always returns `Collections.emptyList()` for `userRoles`. The comment says "would need to fetch from member record" but never does.

```java
// SyncService.java:665-666
// Get user roles (would need to fetch from member record)
List<String> userRoles = Collections.emptyList();  // ← always empty
```

**Impact:** Frontend cannot attribute actions to roles (e.g., "Dr. Sharma marked complete" vs "Ravi called next"). Audit trail has no role context.

**Fix required — Push side:** After saving `EventStore` at line 150, snapshot the user's current roles:

```java
// After eventStoreRepository.save(eventStore) at line 150:
// Save role snapshot for audit trail
for (String roleName : actualRoles) {
    roleRepository.findByName(roleName).ifPresent(role -> {
        EventRoleSnapshot snapshot = EventRoleSnapshot.builder()
                .event(eventStore)
                .role(role)
                .build();
        eventRoleSnapshotRepository.save(snapshot);
    });
}
```

**Fix required — Pull side:** In `convertToDto()` at line 665, replace the empty list:

```java
// Replace lines 665-666 with:
List<String> userRoles = eventRoleSnapshotRepository.findByEventId(event.getId())
        .stream()
        .map(snapshot -> snapshot.getRole().getName())
        .collect(Collectors.toList());
```

**Note:** The pull side fix queries DB per event. For batches, consider a bulk query to avoid N+1.

**Dependencies needed:** `RoleRepository` (not currently injected), `EventRoleSnapshotRepository` (not currently injected)

---

### M2. Pull limit logic resets to 100 instead of capping at 500

**Severity:** Low
**File:** `SyncService.java:197`

**Problem:**
```java
if (limit == null || limit > 500) {
    limit = 100;  // ← passing limit=501 gives 100, not 500
}
```

Default (null) and cap (>500) are merged into one condition. Passing `limit=501` resets to 100 instead of capping at 500.

**Fix:**
```java
// SyncService.java:197 — replace with:
if (limit == null) limit = 100;
if (limit > 500) limit = 500;
```

---

### M3. No optimistic locking — concurrent writes silently overwrite

**Severity:** Medium (low risk due to single-writer design, but no safety net)
**File:** `BaseEntity.java`

**Problem:** No `@Version` field on any entity. If two sync requests modify the same entity concurrently, last write wins silently — no conflict detection.

**Example race condition:**
```
Device A reads entry #42 (state=WAITING)
              Device B reads entry #42 (state=WAITING)
Device A: call_now → state=CALLED, saves ✓
              Device B: patient_removed → state=REMOVED, saves ✓
              → Device A's call_now is silently lost
```

Both pass the state guard (WAITING→CALLED and WAITING→REMOVED are both valid), but the second write overwrites the first without knowing it happened.

**Why it's medium, not critical:** The v3.2 `assignedDoctorId` pattern means one assistant manages one doctor's queue — multi-device conflicts on the same entry are rare by design. The state guards from Issue #2 already prevent the most dangerous invalid transitions. This would catch the narrow race condition where two saves happen within milliseconds.

**Fix:** Add `@Version` to `BaseEntity.java`:

```java
// BaseEntity.java — add this field:
@Version
private Long version;
```

This single field protects every entity. When two transactions read version 1, the first save bumps to version 2. The second save sees a version mismatch and throws `OptimisticLockException`, which would be caught by the inner try/catch and mark the event as REJECTED.

**Note:** Requires adding `version BIGINT` column to all 23 tables, or setting `spring.jpa.hibernate.ddl-auto=update` to auto-add it. Also needs a handler for `OptimisticLockException` in the `GlobalExceptionHandler` if it can surface outside of sync context.

---

## Code Quality Concerns for Sync Engine

> These are minor items that won't cause data corruption or incorrect behavior, but are worth addressing for robustness and maintainability.

### Q1. `Gender.valueOf()` is fragile

**Severity:** Low
**Line:** `SyncService.java:344`

```java
.gender(gender != null ? Gender.valueOf(gender.toUpperCase()) : null)
```

Uses `Gender.valueOf()` (matches Java enum constant name) instead of `Gender.fromValue()` (matches the string value). If someone sends an unexpected gender string like `"non-binary"`, it throws `IllegalArgumentException` with a confusing message instead of a clean validation error. The inner try/catch handles it gracefully (event is REJECTED), but the error reason is unhelpful.

**Fix:**
```java
.gender(gender != null ? Gender.fromValue(gender) : null)
```

---

### Q2. Doctor lookup iterates all org members in Java

**Severity:** Low (performance)
**Lines:** `SyncService.java:357-361`, `SyncService.java:584-588`

```java
OrgMember doctor = orgMemberRepository.findByOrganizationIdWithUser(org.getId())
        .stream()
        .filter(m -> m.getUser().getUuid().equals(doctorId))
        .findFirst()
        .orElseThrow(...);
```

Loads **all** org members with a JOIN FETCH, then filters in Java. For a clinic with 50 members, this fetches 50 rows to find 1. Not a problem for small clinics, but inefficient.

**Fix:** Add a direct repository query:
```java
// OrgMemberRepository.java:
Optional<OrgMember> findByOrganization_IdAndUser_Uuid(Integer orgId, String userUuid);
```

---

### Q3. Queue `startedAt` uses `Instant.now()` instead of deviceTimestamp

**Severity:** Low
**Lines:** `SyncService.java:370`, `SyncService.java:599`

```java
.startedAt(Instant.now())
```

When a queue is auto-created (first `patient_added` or `stash_imported`), `startedAt` uses server time. In theory, if the device was offline when the first patient was added, the queue start time would be the sync time, not the actual start time.

Less impactful than the other timestamp issues because queue creation usually happens in real-time (the first patient_added triggers it, and that event is typically pushed immediately). But for consistency with the offline-first design, it should derive from `event.getDeviceTimestamp()`.

**Fix:**
```java
long startedMs = event.getDeviceTimestamp() != null ? event.getDeviceTimestamp() : System.currentTimeMillis();
.startedAt(Instant.ofEpochMilli(startedMs))
```

---

### Q4. Three event handlers are TODO stubs

**Severity:** Acknowledged — incomplete features, not bugs
**Lines:** `SyncService.java:289-299`

```java
case "visit_saved":
    log.debug("Processing visit_saved for {}", event.getTargetEntity());
    // TODO: Implement visit processing
    break;
case "bill_created":
    log.debug("Processing bill_created for {}", event.getTargetEntity());
    // TODO: Implement bill processing
    break;
case "bill_updated":
    log.debug("Processing bill_updated for {}", event.getTargetEntity());
    // TODO: Implement bill update processing
    break;
```

These event types are accepted by the role check (`EVENT_ALLOWED_ROLES` includes them), stored in `event_store` as APPLIED, and returned to other devices on pull. But the domain tables (`visits`, `bills`, `bill_items`) are **never updated**. The events exist in the event log but have no side effects.

Currently, visits and bills are only created through their direct REST endpoints (`PatientController`, `BillingController`), not through the sync protocol. This means:
- Visits/bills created offline are not synced to the server via events
- Other devices don't receive visit/bill changes through pull

**Status: RESOLVED.** All 12 event handlers are now implemented (visit_saved, bill_created, bill_updated). No more TODO stubs.

---

## Sync Protocol Architecture & Scaling Notes

> Critical reference for understanding the sync engine's guarantees, failure modes, and scaling considerations.

### State Machine (API Contract v3.1)

```
States: waiting, now_serving, completed, removed, stashed

waiting     → now_serving (call_now)
            → removed (patient_removed)
            → stashed (queue_ended)

now_serving → completed (mark_complete)
            → waiting (step_out — goes to END of queue)

stashed     → waiting (stash_imported)
            → removed

completed   → (terminal)
removed     → (terminal)
```

Queue-level states:
```
active → paused (queue_paused)
paused → active (queue_resumed)
active/paused → ended (queue_ended)
ended → (terminal — pause/resume rejected)
```

### Sync Flow: Push → Pull → Convergence

```
ASSISTANT DEVICE                 SERVER                        DOCTOR DEVICE
────────────────                 ──────                        ─────────────

1. User action
   → create SyncEvent
   → apply to local reducer
   → write to SQLite event_log
   → UI updates instantly

2. POST /sync/push              3. For each event:
   { deviceId, events[] }          a. Dedup by eventId
                                   b. Role check (JWT → DB roles)
                                   c. Schema version check
                                   d. Store as PENDING
                                   e. processEvent() → apply to DB
                                   f. Mark APPLIED (or REJECTED)
                                   g. Return accepted/rejected

4. Mark synced=1 locally
                                                               5. GET /sync/pull?since=<ts>
                                6. Query event_store WHERE:       &deviceId=doctor-device
                                   org_id = user's org
                                   serverReceivedAt > since
                                   deviceId != requester
                                   status = 'APPLIED'
                                   ORDER BY serverReceivedAt ASC

                                                               7. For each pulled event:
                                                                  → apply to local reducer
                                                                  → UI updates
                                                                  → update lastEventTimestamp
```

### Timestamp Guarantees (No Event Loss)

```
T0: Doctor boots → GET /queues/active
    Returns: full queue snapshot + lastEventTimestamp = System.currentTimeMillis()

    WHY this is safe:
    - Snapshot is built from domain tables (already have all applied events)
    - lastEventTimestamp = T0 (current server time)
    - Any event applied BEFORE T0 is already in the snapshot
    - Any event pushed AFTER T0 has serverReceivedAt > T0
    - Pull since=T0 catches everything new

T1: Assistant pushes events → serverReceivedAt = T1 (T1 > T0)
T2: Doctor pulls since=T0 → gets events where serverReceivedAt > T0
    → Includes T1 events ✓

NO EVENTS CAN BE LOST because:
1. @Transactional on push = atomic (all-or-nothing commit)
2. serverReceivedAt is set BEFORE processing (at transaction start)
3. lastEventTimestamp is set AT response time (>= all applied events)
4. Pull uses > (strictly greater), so no off-by-one
```

### Conflict Resolution: First-Write-Wins

```
Scenario: Assistant removes patient, Doctor completes patient simultaneously

Assistant (T1): patient_removed → WAITING→REMOVED ✓ APPLIED
Doctor (T2):    mark_complete   → REMOVED→COMPLETED ✗ REJECTED
                                  "Invalid state transition"

OR (if Doctor arrives first):

Doctor (T1):    mark_complete   → WAITING→COMPLETED ✓ APPLIED
Assistant (T2): patient_removed → COMPLETED→REMOVED ✗ REJECTED
                                  "Invalid state transition"

RESULT:
- Queue is ALWAYS in a valid state (no corruption)
- First event to reach server wins
- Loser gets REJECTED with clear reason
- Both devices converge on next pull
- Frontend can show toast: "Patient was already removed/completed"
```

### Failure Modes

#### 1. Business Logic Failure (99% of failures)
```
Event: call_now on COMPLETED patient
Handler: guardStateTransition() throws
Inner catch: catches exception
Result: Event marked REJECTED in event_store
        Other events in batch continue processing
        Queue INTACT — no state change
```

#### 2. Hibernate Session Poison (1% — DB constraint violation)
```
Event: patient_added with name=null
Handler: Hibernate flushes → MySQL "Column 'name' cannot be null"
Result: Hibernate session CORRUPTED
        Remaining events in batch ALL FAIL (cascading)
        @Transactional rolls back EVERYTHING
        Queue INTACT — entire transaction reverted
        Client gets 500 → retries batch
```

**Mitigation:** Frontend validates all required fields before creating events. Server handlers validate critical fields (visitId, patientId) before DB operations. DB constraint violations should never happen in normal operation.

**Future fix:** Process each event in its own sub-transaction (`@Transactional(propagation = REQUIRES_NEW)`) so one bad event can't cascade. This is a P2 architectural improvement.

#### 3. Network Failure
```
Client pushes → network dies → no response
Events stay in SQLite with synced=0
Next connectivity → retry push
Server dedup by eventId → safe to retry
```

### Pull Query Performance

```sql
-- Query (EventStoreRepository.findEventsForPull)
SELECT e FROM EventStore e
WHERE e.organization.id = :orgId
  AND e.serverReceivedAt > :since
  AND e.deviceId != :excludeDeviceId
  AND e.status = 'APPLIED'
ORDER BY e.serverReceivedAt ASC

-- Index: idx_events_org_received (org_id, server_received_at)
-- Performance: O(log n) index seek + scan forward
-- Typical pull: 0-10 events per 15s interval
-- Even with 100K events in store, query reads <20 rows
```

### Scaling Considerations (P2+)

| Concern | Current State | Future Fix |
|---------|--------------|------------|
| **Doctor scoping** | Pull returns ALL org events | Add doctorId filter to pull query (multi-doctor P2) |
| **Event archival** | All events in one table forever | Move events older than 30 days to archive table |
| **Sub-transactions** | One @Transactional for entire batch | `REQUIRES_NEW` per event to isolate failures |
| **Optimistic locking** | No @Version on entities | Add @Version to BaseEntity for concurrent write safety |
| **SSE (real-time)** | 15s polling | Server-Sent Events replaces pull timer (P1) |
| **Rate limiting** | None on push/pull | Add per-device rate limits to prevent abuse |
| **Event compression** | Full payload stored per event | Delta encoding for visit_saved updates |

### Event Type Reference (All 12 Implemented)

| Event | Allowed Roles | State Guard | Creates/Modifies |
|-------|--------------|-------------|------------------|
| `patient_added` | assistant, doctor | none (creates new) | queue_entry + patient |
| `patient_removed` | assistant, doctor | WAITING only | queue_entry state |
| `call_now` | assistant, doctor | WAITING only | queue_entry state |
| `step_out` | assistant, doctor | CALLED only | queue_entry state + position |
| `mark_complete` | assistant, doctor | CALLED only | queue_entry state + patient stats |
| `queue_paused` | assistant, doctor | ACTIVE only | queue status |
| `queue_resumed` | assistant, doctor | PAUSED only | queue status |
| `queue_ended` | assistant, doctor | not ENDED | queue status + auto-complete + stash |
| `stash_imported` | assistant, doctor | STASHED entries | queue_entry (new entries in target queue) |
| `visit_saved` | doctor | none (create/update) | visit + patient stats |
| `bill_created` | assistant, doctor | none (creates new) | bill + bill_items + queue_entry.isBilled |
| `bill_updated` | assistant, doctor | bill must exist | bill.isPaid |
