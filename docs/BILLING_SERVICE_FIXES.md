# BillingService — Fixes Applied

> Documents all fixes applied to `BillingService.java` and related repository files.

---

## 1. listBills loaded ALL bills + N+1 on items → DB aggregation + pagination (High — Performance)

**Files:** `BillingService.java:174-253`, `BillRepository.java`

**Problem:** `listBills()` loaded every bill for the org (`findByOrganizationId`), filtered by status in Java, sorted in Java, computed summary aggregates in Java, then paginated in Java. For a clinic with 7,000+ bills, every page request loaded all 7,000 rows. Additionally, N+1 on bill items — each bill in the page triggered a separate query for its items.

**Fix — two independent operations:**

**Summary via DB aggregation (zero rows loaded):**
```java
// Returns [count, totalBilled, totalPaid] in one query
Object[] summary = billRepository.getBillSummaryByOrg(orgId);
// or with date filter:
Object[] summary = billRepository.getBillSummaryByOrgAndDateRange(orgId, start, end);
```

**List via paginated DB query (only current page loaded):**
```java
// 4 query variants: with/without date filter × with/without paid status
billRepository.findByOrgIdOrderByCreatedDesc(orgId, page)
billRepository.findByOrgIdAndPaidStatusOrderByCreatedDesc(orgId, isPaid, page)
billRepository.findByOrgAndDateRangePaginated(orgId, start, end, page)
billRepository.findByOrgAndDateRangeAndPaidStatusPaginated(orgId, start, end, isPaid, page)
```

**Impact:** From loading all bills + Java filter/sort/aggregate → 1 aggregate query + 1 paginated list query. N+1 on items still exists per page (up to 20 extra queries) — acceptable since each bill typically has 1-3 items.

**Rollback:** Revert `BillingService.java` and `BillRepository.java`.

---

## 2. queueEntryId returned Integer ID instead of UUID (Medium — API bug)

**File:** `BillingService.java:331-336`

**Problem:** `bill.getQueueEntryId()` returned the internal Integer FK (e.g., `42`), which was sent to the frontend as `"42"`. Every other entity in the API uses UUIDs. The frontend would receive a meaningless Integer string.

**Before:** `queueEntryId: "42"` (internal DB id)
**After:** `queueEntryId: "abc-123-def-456"` (UUID)

**Fix:** Look up the QueueEntry by its Integer ID and return the UUID:
```java
String queueEntryUuid = null;
if (bill.getQueueEntryId() != null) {
    queueEntryUuid = queueEntryRepository.findById(bill.getQueueEntryId())
            .map(QueueEntry::getUuid)
            .orElse(null);
}
```

**Note:** Adds 1 query per bill response. Acceptable for single bill views (`getBill`, `markBillPaid`). For `listBills` this is N extra queries — but bills per page is small (20 max) and the queue entry lookup is by PK (fast).

---

## 3. ZoneId.systemDefault() → Asia/Kolkata for date filtering (Medium — Timezone)

**File:** `BillingService.java:186-187`

**Problem:** Date filter used `ZoneId.systemDefault()`. If server runs in UTC, filtering for "2026-02-25" would use UTC midnight boundaries (18:30 IST to 18:30 IST next day) instead of IST midnight boundaries.

**Fix:** Replaced with explicit `ZoneId.of("Asia/Kolkata")`:
```java
ZoneId IST = ZoneId.of("Asia/Kolkata");
Instant startOfDay = filterDate.atStartOfDay(IST).toInstant();
Instant endOfDay = filterDate.plusDays(1).atStartOfDay(IST).toInstant();
```

---

## Remaining Items (Documented, Not Fixed)

### R1. N+1 on bill items in listBills

**Severity:** Low (bounded — max 20 bills per page × 1 query each)
**Line:** `BillingService.java` in `listBills` stream

Each bill in the page triggers `findByBillIdOrderBySortOrderAsc`. Could be solved with a batch query (`WHERE bill_id IN (:ids)`) but the impact is small.

### R2. Total ignores quantity in bill items

**Severity:** Low (frontend handles)
**Line:** `BillingService.java:71-73`

`totalAmount` sums item amounts without multiplying by quantity. The `bill_items` table has a `quantity` column but the total calculation ignores it.

### R3. createBillTemplate loads all templates for max sort order

**Severity:** Low (typically < 20 templates)
**Line:** `BillingService.java:294-298`

Should be `SELECT MAX(sort_order)` query instead of loading all templates.
