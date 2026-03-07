# PatientService — Fixes Applied

> Documents all fixes applied to `PatientService.java` and related repository files.

---

## 1. Patient list and search loaded ALL patients into memory (High — Performance)

**Files:** `PatientService.java:45-70`, `PatientRepository.java`

**Problem:** Both `listPatients()` and `searchPatients()` loaded the entire patient table for the org into Java memory, then sorted/filtered/paginated in Java. For a clinic with 5,000+ patients, every page request loaded all 5,000 rows.

Two specific sub-issues:
- **No-search path:** `findByOrganizationId(org.getId())` returned every patient
- **Phone search path:** loaded all patients, then `filter(p -> p.getPhone().contains(search))` in Java

**Fix:** Added DB-level queries with `Pageable` to `PatientRepository.java`:

```java
// Combined name + phone search in single query (replaces two queries + Java merge)
searchByNameOrPhone(orgId, query, Pageable)

// Sorted paginated queries (replaces load-all + Java sort)
findByOrgIdOrderByLastVisitDesc(orgId, Pageable)
findByOrgIdOrderByNameAsc(orgId, Pageable)
findByOrgIdOrderByCreatedDesc(orgId, Pageable)
findByOrgIdOrderByVisitsDesc(orgId, Pageable)
```

`listPatients()` now uses a switch on sort key to pick the right DB query. `searchPatients()` uses `searchByNameOrPhone` with LIMIT.

**Impact:** From `O(n)` memory + `O(n log n)` sort per request → constant `O(limit)` memory, DB handles sorting and pagination.

Removed dead code: `sortPatients()` method (sorting now done by DB).

---

## 1b. Keyset cursor pagination (True cursor — no offset)

**Files:** `PatientService.java:58-80, 334-423`, `PatientRepository.java:44-78`, `VisitRepository.java:26-33`

**Problem:** Initial DB pagination fix used `PageRequest.of(0, limit+1)` for every request. The cursor was returned in the response but never consumed — page 2+ always returned the same data as page 1.

**Fix:** Implemented proper keyset cursor pagination:

**How it works:**
1. **Cursor encodes:** `sortValue|internalId` as Base64. Example: `"2026-02-20|42"` → `"MjAyNi0wMi0yMHw0Mg=="`
2. **Cursor decodes:** Parse Base64 → split by `|` → look up entity by ID from DB
3. **WHERE clause:** Uses the cursor entity's sort value + ID as keyset: `WHERE (sortCol < :cursorValue OR (sortCol = :cursorValue AND id < :cursorId))`

**Per sort option — 4 first-page queries + 5 cursor queries added to `PatientRepository`:**

| Sort | First page ORDER BY | Cursor WHERE clause |
|------|-------------------|-------------------|
| `last_visit_desc` | `last_visit_date DESC NULLS LAST, id DESC` | `date < cursor OR (date = cursor AND id < cursorId) OR date IS NULL` |
| `last_visit_desc` (null cursor) | same | `date IS NULL AND id < cursorId` |
| `name_asc` | `name ASC, id ASC` | `name > cursor OR (name = cursor AND id > cursorId)` |
| `created_desc` | `created_at DESC, id DESC` | `created_at < cursor OR (= AND id < cursorId)` |
| `visits_desc` | `total_visits DESC, id DESC` | `total_visits < cursor OR (= AND id < cursorId)` |

**Visit thread** also uses keyset cursor: `visitDate|id` with cursor query in `VisitRepository`.

**Helper methods added to `PatientService`:**
- `fetchPatientsFirstPage(orgId, sortKey, page)` — picks first-page query by sort
- `fetchPatientsAfterCursor(orgId, sortKey, cursor, page)` — picks cursor query by sort
- `encodePatientCursor(patient, sortKey)` / `decodeCursorToPatient(cursor)` — cursor encode/decode
- `encodeVisitCursor(visit)` / `decodeCursorToVisit(cursor)` — visit cursor encode/decode

**Rollback:** If keyset pagination causes issues, revert `PatientService.java`, `PatientRepository.java`, and `VisitRepository.java` to commit `f044bd1`. The offset-based first-page-only behavior is safe as a fallback.

---

## 1c. Doctor allowed all assistant sync events (SyncService)

**File:** `SyncService.java:57-71`

**Problem:** `EVENT_ALLOWED_ROLES` only allowed `assistant` for queue operations (`patient_added`, `call_now`, etc.). In single-doctor clinics, the doctor acts as their own assistant. Sync push from a doctor-only user was rejected with `UNAUTHORIZED_ROLE`.

**Fix:** Added `"doctor"` to all event types that previously only allowed `"assistant"`:
```
patient_added, patient_removed, call_now, step_out, queue_paused,
queue_resumed, queue_ended, stash_imported → now allow both assistant AND doctor
```

`mark_complete`, `bill_created`, `bill_updated` already had both. `visit_saved` stays doctor-only.

**Rollback:** Revert lines 57-71 in `SyncService.java` to restore assistant-only restrictions.

---

## 2. Visit thread loaded all visits + N+1 queries (Medium — Performance)

**Files:** `PatientService.java:95-137`, `VisitRepository.java`

**Problem:** `getPatientThread()` loaded ALL visits for a patient (`findByPatientIdOrderByVisitDateDesc`), then paginated in Java. Additionally, `toVisitDto()` triggered N+1 LAZY queries: `visit.getCreatedBy()` → OrgMember, `creator.getUser()` → User, `visit.getQueueEntry()` → QueueEntry. For 10 visits: up to 30 extra queries.

**Fix:** Added JOIN FETCH paginated query to `VisitRepository.java`:

```java
@Query("SELECT v FROM Visit v JOIN FETCH v.createdBy cb JOIN FETCH cb.user " +
        "LEFT JOIN FETCH v.queueEntry " +
        "WHERE v.patient.id = :patientId ORDER BY v.visitDate DESC, v.createdAt DESC")
List<Visit> findByPatientIdWithDetailsOrderByDateDesc(patientId, Pageable)
```

Now loads only the requested page with all related entities in a single query.

**Impact:** From `O(n)` visits loaded + 3N lazy queries → `O(limit)` visits + 1 query total.

---

## 3. Cross-org/patient validation on queueEntryId in createVisit (Medium — Security)

**File:** `PatientService.java:158-171`

**Problem:** `createVisit()` accepted any `queueEntryId` UUID without validating it belonged to the same organization or the same patient. A crafted request could link a visit to a queue entry from a different org or patient.

**Fix:** Added org and patient validation after looking up the queue entry:

```java
if (queueEntry != null) {
    boolean wrongOrg = !queueEntry.getQueue().getOrganization().getId().equals(org.getId());
    boolean wrongPatient = !queueEntry.getPatient().getId().equals(patient.getId());
    if (wrongOrg || wrongPatient) {
        queueEntry = null; // silently ignore invalid reference
        log.warn("Queue entry {} does not match org {} or patient {}", ...);
    }
}
```

Silently ignores rather than throws — the visit is still created, just without the queue entry link. This is defensive since the queueEntryId is optional.

---

## 4. Patient thread response missing patient info (Low — API Completeness)

**Commit:** `307c295`

**Files:** `PatientThreadResponse.java`, `PatientService.java:155-165`

**Problem:** `GET /patients/{patientId}/thread` returned only visits and pagination. No patient details (name, phone, age, etc.) were included. The client had to rely on cached data from the patient list, making the thread screen not self-contained.

**Fix:** Added `PatientSummary` to `PatientThreadResponse` with: `patientId`, `name`, `phone`, `age`, `gender`, `totalVisits`, `isRegular`, `createdAt`. No extra DB query — the patient entity is already loaded for the org validation check.

Fields deliberately excluded: `lastVisitDate` and `lastComplaintTags` — these are visible from the first visit in the thread itself.

---

## 4. Gender returns lowercase for API consistency (Low)

**File:** `PatientService.java:258, 274`

**Problem:** `patient.getGender().name()` returned uppercase (`MALE`, `FEMALE`). Rest of API uses lowercase.

**Fix:** Replaced `.name()` with `.getValue()` in `searchPatients()` and `toPatientListItem()`.

---

## Remaining Items (Documented, Not Fixed)

### R1. `visitDate` uses `LocalDate.now()` — server timezone issue

**Severity:** Medium
**Line:** `PatientService.java:177, 188`

`LocalDate.now()` uses server timezone. If server is UTC and clinic is IST (UTC+5:30), a visit at 11 PM IST on Feb 24 would be recorded as Feb 25 UTC. Should use `ZoneId.of("Asia/Kolkata")` like the SyncService timestamp fix.

### R2. `creatorRole` hardcoded to "doctor"

**Severity:** Low (TODO)
**Line:** `PatientService.java:293`

Every visit shows creator role as "doctor" regardless of who created it. Should load actual role from `OrgMemberRole`.

### R3. `patientUuid` unused in updateVisit — no cross-validation

**Severity:** Low
**Line:** `PatientService.java:206`

The URL could have a different patient ID than the visit's actual patient — no verification that visit belongs to the patient in the URL path.

### R4. Race condition on `totalVisits` increment

**Severity:** Low
**Line:** `PatientService.java:187`

Read-modify-write without atomic DB increment. Two concurrent visit creations for the same patient could lose a count. Should use `UPDATE patients SET total_visits = total_visits + 1 WHERE id = ?`.
