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
