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

Removed dead code: `sortPatients()` method (sorting now done by DB), `decodeCursor()` method (cursor consumed by offset pagination).

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
