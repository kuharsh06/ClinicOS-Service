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

**When these are implemented**, they should follow the same patterns established in the other handlers: deviceTimestamp for dates, guardStateTransition where applicable, proper entity creation with UUID from targetEntity.
