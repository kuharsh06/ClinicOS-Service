# SmsService — Fixes Applied

> Documents all fixes applied to `SmsService.java` and related repository files.

---

## 1. getQueueSmsStatus loaded ALL org SMS logs inside a loop (High — Performance)

**Files:** `SmsService.java:75-108`, `SmsLogRepository.java`

**Problem:** For each queue entry, the method loaded ALL SMS logs for the entire org, then filtered in Java:

```java
// OLD: Inside a loop over 30 entries — loads ALL org logs 30 times
List<SmsLog> entryLogs = smsLogRepository.findByOrganizationIdOrderByCreatedAtDesc(org.getId())
        .stream()
        .filter(log -> log.getQueueEntry() != null && log.getQueueEntry().getId().equals(entry.getId()))
        .collect(Collectors.toList());
```

For a queue with 30 entries and an org with 10,000 SMS logs: 30 × 10,000 = 300,000 rows loaded and scanned.

**Fix:** Batch-load all SMS logs for the queue's entries in one query, then group by entry ID:

```java
// NEW: 1 query for all entries
List<SmsLog> allLogs = smsLogRepository.findByQueueEntryIdIn(entryIds);
Map<Integer, List<SmsLog>> logsByEntryId = allLogs.stream()
        .collect(Collectors.groupingBy(log -> log.getQueueEntry().getId()));
```

Added `findByQueueEntryIdIn(List<Integer> queueEntryIds)` to `SmsLogRepository`.

**Impact:** From O(entries × totalLogs) → O(1 query for relevant logs only).

---

## 2. processTemplate null safety (Low — Edge case)

**File:** `SmsService.java:211-216`

**Problem:** `entry.getPatient().getName()` could throw NullPointerException if name is null. Similarly for org name.

**Fix:** Added `Objects.toString()` with fallback defaults:

```java
.replace("{{patient_name}}", Objects.toString(entry.getPatient().getName(), "Patient"))
.replace("{{clinic}}", Objects.toString(entry.getQueue().getOrganization().getName(), "Clinic"))
```

---

## Remaining Items (Documented, Not Fixed)

### R1. Hindi/English templates return same content

**Severity:** Low (incomplete feature)
**Line:** `SmsService.java:47`

Both language keys get the same `content` field. The `SmsTemplate` entity has a single `content` column — multi-language support needs `contentHi`/`contentEn` columns or a JSON blob.

### R2. SMS marked as SENT without actually sending

**Severity:** Low (acknowledged TODO)
**Line:** `SmsService.java:166-170`

`sendManualSms` sets status to `SENT` immediately. When SMS gateway is integrated, status should stay `PENDING` until gateway confirms. The response says `"status": "sent"` which is currently misleading.
