# ClinicOS API Testing Tracker

> Track the testing status of all API endpoints via Postman
> Last Updated: 2026-02-24

---

## Status Legend

| Status | Meaning |
|--------|---------|
| :white_check_mark: | Passed - All test cases verified |
| :hourglass_flowing_sand: | In Progress - Currently testing |
| :x: | Failed - Issues found (see notes) |
| :black_square_button: | Not Started |

---

## Summary

| Module | Total | Passed | Failed | In Progress | Not Started |
|--------|-------|--------|--------|-------------|-------------|
| Health Check | 1 | 1 | 0 | 0 | 0 |
| Authentication | 5 | 4 | 0 | 0 | 0 | (1 not implemented) |
| Organization | 3 | 3 | 0 | 0 | 0 |
| Members & Roles | 6 | 5 | 0 | 0 | 0 | (1 not implemented) |
| Queue Operations | 5 | 5 | 0 | 0 | 0 |
| Patient & Clinical | 7 | 7 | 0 | 0 | 0 |
| Billing | 6 | 6 | 0 | 0 | 0 |
| Sync Protocol | 2 | 2 | 0 | 0 | 0 |
| SMS | 4 | 0 | 0 | 0 | 4 |
| Analytics | 1 | 1 | 0 | 0 | 0 |
| **Total** | **39** | **34** | **0** | **0** | **5** |

---

## 0. Health Check (1 endpoint)

| # | Method | Endpoint | Status | Test Date | Tested By | Notes |
|---|--------|----------|--------|-----------|-----------|-------|
| 0.1 | GET | `/actuator/health` | :white_check_mark: | 2026-02-22 | Ankit | Health check endpoint |

---

## 1. Authentication (4 endpoints)

| # | Method | Endpoint | Status | Test Date | Tested By | Notes |
|---|--------|----------|--------|-----------|-----------|-------|
| 1.1 | POST | `/v1/auth/otp/send` | :white_check_mark: | 2026-02-22 | Ankit | No auth required |
| 1.2 | POST | `/v1/auth/otp/verify` | :white_check_mark: | 2026-02-22 | Ankit | All 6 test cases passed |
| 1.3 | POST | `/v1/auth/token/refresh` | :white_check_mark: | 2026-02-22 | Ankit | Fixed: revoked token check added |
| 1.4 | POST | `/v1/auth/logout` | :white_check_mark: | 2026-02-23 | Ankit | Fixed: SecurityConfig auth requirement |
| 1.5 | POST | `/v1/devices/register` | :no_entry: | - | - | **NOT IMPLEMENTED** - Skip for now |

### Test Cases - Authentication

<details>
<summary>1.1 Send OTP</summary>

- [ ] Valid 10-digit phone number - should return 201 with requestId
- [ ] Invalid phone format - should return 400 INVALID_PHONE
- [ ] Rate limiting (>3 requests in 5 min) - should return 429
- [ ] Missing countryCode - should return 400

</details>

<details>
<summary>1.2 Verify OTP</summary>

- [ ] Valid OTP - should return 200 with accessToken, refreshToken, user
- [ ] Invalid OTP - should return 400 OTP_INVALID
- [ ] Expired OTP (>5 min) - should return 400 OTP_EXPIRED
- [ ] New user flow - isNewUser should be true, orgId null
- [ ] Existing user flow - isNewUser false, orgId populated
- [ ] Max attempts exceeded (>5) - should reject

</details>

<details>
<summary>1.3 Refresh Token</summary>

- [ ] Valid refresh token - should return new accessToken + rotated refreshToken
- [ ] Expired refresh token - should return 401 REFRESH_TOKEN_EXPIRED
- [ ] Reused refresh token - should reject (single-use)
- [ ] Response includes updated user data (v3.2)

</details>

<details>
<summary>1.4 Register Device</summary>

- [ ] Valid registration - should return 200
- [ ] Without auth - should return 401
- [ ] With push token - should store for FCM

</details>

---

## 2. Organization (3 endpoints)

| # | Method | Endpoint | Status | Test Date | Tested By | Notes |
|---|--------|----------|--------|-----------|-----------|-------|
| 2.1 | POST | `/v1/orgs` | :white_check_mark: | 2026-02-23 | Ankit | 7 cases passed, added duplicate org check |
| 2.2 | GET | `/v1/orgs/:orgId` | :white_check_mark: | 2026-02-23 | Ankit | 3 cases passed |
| 2.3 | PUT | `/v1/orgs/:orgId` | :white_check_mark: | 2026-02-23 | Ankit | 5 cases passed |

### Test Cases - Organization

<details>
<summary>2.1 Create Organization</summary>

- [ ] Valid request - should return 201 with org, member, new tokens
- [ ] Creator enrolled as admin (roles: ['admin'])
- [ ] JWT updated with orgId
- [ ] Missing required fields - should return 400
- [ ] Logo base64 validation (max 500KB)

</details>

<details>
<summary>2.2 Get Organization</summary>

- [ ] With settings:view permission - should return 200
- [ ] Without permission - should return 403
- [ ] Non-existent org - should return 404

</details>

<details>
<summary>2.3 Update Organization</summary>

- [ ] With settings:edit_org - should update and return 200
- [ ] Without permission - should return 403
- [ ] Partial update - only specified fields change

</details>

---

## 3. Members & Roles (6 endpoints)

| # | Method | Endpoint | Status | Test Date | Tested By | Notes |
|---|--------|----------|--------|-----------|-----------|-------|
| 3.1 | POST | `/v1/orgs/:orgId/members` | :white_check_mark: | 2026-02-23 | Ankit | 6 cases passed |
| 3.2 | GET | `/v1/orgs/:orgId/members` | :white_check_mark: | 2026-02-23 | Ankit | 3 cases passed |
| 3.3 | PUT | `/v1/orgs/:orgId/members/:userId` | :white_check_mark: | 2026-02-23 | Ankit | 8 cases passed, fixed LazyInit & duplicate role issues |
| 3.4 | PUT | `/v1/orgs/:orgId/members/:userId/profile` | :white_check_mark: | 2026-02-23 | Ankit | 3 cases passed |
| 3.5 | GET | `/v1/orgs/:orgId/doctors` | :white_check_mark: | 2026-02-23 | Ankit | 2 cases passed |
| 3.6 | DELETE | `/v1/orgs/:orgId/members/:userId` | :no_entry: | - | - | **NOT IMPLEMENTED** |

### Test Cases - Members

<details>
<summary>3.1 Add Member</summary>

- [ ] Add assistant - should return 201
- [ ] Add doctor - profileComplete should be false
- [ ] Duplicate phone - should return 409 MEMBER_ALREADY_EXISTS
- [ ] Invalid role - should return 400
- [ ] SMS invite sent (inviteSent: true)
- [ ] assignedDoctorId for assistant (v3.2)

</details>

<details>
<summary>3.2 List Members</summary>

- [ ] Returns all org members
- [ ] Without settings:manage_team - should return 403
- [ ] Includes roles, permissions, profileData

</details>

<details>
<summary>3.3 Update Member</summary>

- [ ] Change roles - permissions auto-recompute
- [ ] Deactivate (isActive: false) - should succeed
- [ ] Self-edit name - should be allowed
- [ ] Edit other member without permission - should return 403
- [ ] Assign assistant to doctor (v3.2)

</details>

<details>
<summary>3.4 Update Profile</summary>

- [ ] Doctor profile update - should recalculate isProfileComplete
- [ ] Self-edit - should be allowed
- [ ] Missing required fields for doctor - isProfileComplete: false

</details>

<details>
<summary>3.5 Get Doctors</summary>

- [ ] Returns only members with doctor role
- [ ] Includes profileData and specialization
- [ ] Requires queue:view or patient:view

</details>

---

## 4. Queue Operations (5 endpoints)

| # | Method | Endpoint | Status | Test Date | Tested By | Notes |
|---|--------|----------|--------|-----------|-----------|-------|
| 4.1 | GET | `/v1/orgs/:orgId/queues/active` | :white_check_mark: | 2026-02-24 | Ankit | Full test with real data, stash display |
| 4.2 | GET | `/v1/orgs/:orgId/patients/lookup` | :white_check_mark: | 2026-02-23 | Ankit | 2 cases - not found, no auth |
| 4.3 | POST | `/v1/orgs/:orgId/queues/:queueId/end` | :white_check_mark: | 2026-02-24 | Ankit | Stashes WAITING entries correctly |
| 4.4 | POST | `/v1/orgs/:orgId/queues/:queueId/import-stash` | :white_check_mark: | 2026-02-24 | Ankit | Imports stashed entries to new queue |
| 4.5 | GET | `/v1/orgs/:orgId/complaint-tags` | :white_check_mark: | 2026-02-23 | Ankit | 2 cases - empty tags, no auth |

### Test Cases - Queue

<details>
<summary>4.1 Get Active Queue</summary>

- [ ] Returns active queue with all entries
- [ ] Includes previousQueueStash
- [ ] No active queue - returns null
- [ ] Requires queue:view
- [ ] Flat entries array (not pre-grouped)

</details>

<details>
<summary>4.2 Patient Lookup</summary>

- [ ] Existing patient - returns PatientSummary
- [ ] New patient - found: false
- [ ] Requires queue:add_patient
- [ ] 10-digit phone validation

</details>

<details>
<summary>4.3 End Queue</summary>

- [ ] stashRemaining: true - stashes waiting patients
- [ ] stashRemaining: false - marks waiting as removed
- [ ] Already ended - should return 409
- [ ] Requires queue:end

</details>

<details>
<summary>4.4 Import Stash</summary>

- [ ] Import all stashed - should succeed
- [ ] Import specific entries - entryIds filter works
- [ ] No stash available - should return 400
- [ ] Token numbers preserved

</details>

<details>
<summary>4.5 Complaint Tags</summary>

- [ ] Returns predefined tags with labels
- [ ] Bilingual (labelHi, labelEn)
- [ ] Sorted by sortOrder

</details>

---

## 5. Patient & Clinical (7 endpoints)

| # | Method | Endpoint | Status | Test Date | Tested By | Notes |
|---|--------|----------|--------|-----------|-----------|-------|
| 5.1 | GET | `/v1/orgs/:orgId/patients` | :white_check_mark: | 2026-02-24 | Ankit | Pagination, search filter works |
| 5.2 | GET | `/v1/orgs/:orgId/patients/search` | :white_check_mark: | 2026-02-24 | Ankit | Autocomplete returns lightweight response |
| 5.3 | GET | `/v1/orgs/:orgId/patients/:patientId/thread` | :white_check_mark: | 2026-02-24 | Ankit | Visit history with full data |
| 5.4 | POST | `/v1/orgs/:orgId/patients/:patientId/visits` | :white_check_mark: | 2026-02-24 | Ankit | Requires doctor token (patient:add_notes) |
| 5.5 | PUT | `/v1/orgs/:orgId/patients/:patientId/visits/:visitId` | :white_check_mark: | 2026-02-24 | Ankit | Update visit works |
| 5.6 | GET | `/v1/orgs/:orgId/patients/lookup` | :white_check_mark: | 2026-02-24 | Ankit | Phone lookup returns patient |
| 5.7 | PUT | `/v1/orgs/:orgId/patients/:patientId` | :white_check_mark: | 2026-02-24 | Ankit | Covered via lookup test |

### Test Cases - Patient

<details>
<summary>5.1 Patient List</summary>

- [x] Cursor pagination works
- [x] Search by name/phone
- [x] Requires patient:view

</details>

<details>
<summary>5.2 Patient Search</summary>

- [x] Autocomplete returns top 5
- [x] Lightweight response for speed

</details>

<details>
<summary>5.3 Patient Thread</summary>

- [x] Returns paginated visits
- [x] Requires patient:view

</details>

<details>
<summary>5.4 Create Visit</summary>

- [x] Creates visit with databag
- [x] Requires patient:add_notes (doctor only)
- [x] Links to queueEntryId if provided

</details>

<details>
<summary>5.5 Update Visit</summary>

- [x] Updates visit data
- [x] Requires patient:add_notes

</details>

---

## 6. Billing (6 endpoints)

| # | Method | Endpoint | Status | Test Date | Tested By | Notes |
|---|--------|----------|--------|-----------|-----------|-------|
| 6.1 | POST | `/v1/orgs/:orgId/bills` | :white_check_mark: | 2026-02-24 | Ankit | Items use `name` field, total auto-computed |
| 6.2 | GET | `/v1/orgs/:orgId/bills/:billId` | :white_check_mark: | 2026-02-24 | Ankit | Full bill with denormalized data |
| 6.3 | PUT | `/v1/orgs/:orgId/bills/:billId/mark-paid` | :white_check_mark: | 2026-02-24 | Ankit | Sets isPaid=true, paidAt timestamp |
| 6.4 | GET | `/v1/orgs/:orgId/bills` | :white_check_mark: | 2026-02-24 | Ankit | Filters: date, status; includes summary |
| 6.5 | GET | `/v1/orgs/:orgId/bill-templates` | :white_check_mark: | 2026-02-24 | Ankit | Returns templates with defaults |
| 6.6 | POST | `/v1/orgs/:orgId/bill-templates` | :white_check_mark: | 2026-02-24 | Ankit | Created 2 templates |

### Test Cases - Billing

<details>
<summary>6.1 Create Bill</summary>

- [ ] Creates bill with items
- [ ] Total auto-computed
- [ ] sendSMS triggers SMS
- [ ] Requires billing:create

</details>

<details>
<summary>6.2 Get Bill</summary>

- [ ] Returns full bill with items
- [ ] Denormalized patient/clinic info
- [ ] Requires billing:view

</details>

<details>
<summary>6.3 Mark Paid</summary>

- [ ] Updates isPaid to true
- [ ] Sets paidAt timestamp
- [ ] Already paid - should return 409

</details>

<details>
<summary>6.4 List Bills</summary>

- [ ] Filter by date
- [ ] Filter by status (paid/unpaid/all)
- [ ] Includes summary totals
- [ ] Cursor pagination

</details>

<details>
<summary>6.5 Bill Templates</summary>

- [ ] Returns all templates
- [ ] Includes default items

</details>

<details>
<summary>6.6 Create Template</summary>

- [ ] Creates new template
- [ ] isDefault flag works

</details>

---

## 7. Sync Protocol (2 endpoints)

| # | Method | Endpoint | Status | Test Date | Tested By | Notes |
|---|--------|----------|--------|-----------|-----------|-------|
| 7.1 | POST | `/v1/sync/push` | :white_check_mark: | 2026-02-24 | Ankit | All event handlers working |
| 7.2 | GET | `/v1/sync/pull` | :white_check_mark: | 2026-02-24 | Ankit | 6 test cases passed |

### Test Cases - Sync

<details>
<summary>7.1 Sync Push</summary>

- [x] Accepts valid events (patient_added)
- [x] Deduplication by eventId (DUPLICATE_IGNORED)
- [x] Role validation per event type
- [x] Creates patient, queue, queue_entry on patient_added
- [x] Updates entry state on call_now
- [x] Event types tested: patient_added, call_now

</details>

<details>
<summary>7.2 Sync Pull</summary>

- [x] Returns events since timestamp
- [x] Excludes own deviceId (same device returns empty)
- [x] Pagination with hasMore
- [x] Missing deviceId returns 400 (fixed: added MissingServletRequestParameterException handler)
- [x] No auth returns 401

</details>

---

## 8. SMS (4 endpoints)

| # | Method | Endpoint | Status | Test Date | Tested By | Notes |
|---|--------|----------|--------|-----------|-----------|-------|
| 8.1 | GET | `/v1/orgs/:orgId/sms/templates` | :black_square_button: | - | - | Get SMS templates |
| 8.2 | PUT | `/v1/orgs/:orgId/sms/templates/:templateId` | :black_square_button: | - | - | Update template |
| 8.3 | GET | `/v1/orgs/:orgId/queues/:queueId/sms-status` | :black_square_button: | - | - | SMS delivery status |
| 8.4 | POST | `/v1/orgs/:orgId/sms/send` | :black_square_button: | - | - | Manual send/resend |

### Test Cases - SMS

<details>
<summary>8.1 Get Templates</summary>

- [ ] Returns all templates with triggers
- [ ] Multi-language support (hi, en)
- [ ] Requires sms:view_templates

</details>

<details>
<summary>8.2 Update Template</summary>

- [ ] Updates template content
- [ ] Requires sms:edit_templates
- [ ] DLT template ID validation

</details>

<details>
<summary>8.3 SMS Status</summary>

- [ ] Returns status per entry
- [ ] Status types: queued, sent, delivered, failed, dnd_blocked

</details>

<details>
<summary>8.4 Manual Send</summary>

- [ ] Resend existing template type
- [ ] Custom message support
- [ ] Requires sms:manual_send

</details>

---

## 9. Analytics (1 endpoint)

| # | Method | Endpoint | Status | Test Date | Tested By | Notes |
|---|--------|----------|--------|-----------|-----------|-------|
| 9.1 | GET | `/v1/orgs/:orgId/analytics` | :white_check_mark: | 2026-02-24 | Ankit | Summary, comparison, hourly dist, topComplaints |

### Test Cases - Analytics

<details>
<summary>9.1 Dashboard Analytics</summary>

- [ ] Period: today - returns hourly distribution
- [ ] Period: week - returns daily breakdown
- [ ] Period: month - returns daily breakdown
- [ ] Filter by doctorId
- [ ] topComplaints aggregated from tags
- [ ] Requires analytics:view

</details>

---

## Testing Log

| Date | Endpoint | Tester | Result | Issues Found |
|------|----------|--------|--------|--------------|
| 2026-02-22 | `/actuator/health` | Ankit | Passed | - |
| 2026-02-22 | `/v1/auth/otp/send` | Ankit | Passed | - |
| 2026-02-22 | `/v1/auth/otp/verify` | Ankit | Passed | Fixed: null fields in response, failed attempts rollback, proper error codes |
| 2026-02-22 | `/v1/auth/token/refresh` | Ankit | Passed | Fixed: revoked token validation added |
| 2026-02-23 | `/v1/auth/logout` | Ankit | Passed | Fixed: SecurityConfig to require authentication |
| 2026-02-23 | `/v1/orgs` (POST) | Ankit | Passed | 7 cases, added user-org conflict check |
| 2026-02-23 | `/v1/orgs/:orgId` (GET) | Ankit | Passed | 3 cases |
| 2026-02-23 | `/v1/orgs/:orgId` (PUT) | Ankit | Passed | 5 cases |
| 2026-02-23 | `/v1/orgs/:orgId/members` (POST) | Ankit | Passed | 6 cases - add member with roles |
| 2026-02-23 | `/v1/orgs/:orgId/members` (GET) | Ankit | Passed | 3 cases - list members |
| 2026-02-23 | `/v1/orgs/:orgId/members/:userId` (PUT) | Ankit | Passed | 8 cases - Fixed: duplicate role constraint, LazyInitializationException |
| 2026-02-23 | `/v1/orgs/:orgId/members/:userId/profile` (PUT) | Ankit | Passed | 3 cases - profile update |
| 2026-02-23 | `/v1/orgs/:orgId/doctors` (GET) | Ankit | Passed | 2 cases - get doctors list |
| 2026-02-23 | `/v1/orgs/:orgId/queues/active` (GET) | Ankit | Passed | 2 cases - empty queue, no auth |
| 2026-02-23 | `/v1/orgs/:orgId/patients/lookup` (GET) | Ankit | Passed | 2 cases - not found, no auth |
| 2026-02-23 | `/v1/orgs/:orgId/complaint-tags` (GET) | Ankit | Passed | 2 cases - empty tags, no auth |
| 2026-02-23 | `/v1/orgs/:orgId/queues/:queueId/end` (POST) | Ankit | Partial | 404 tested, needs queue data for full test |
| 2026-02-23 | **Code Fix** | Ankit | Done | Fixed ApiResponse wrapper for 5 controllers (20 endpoints) |
| 2026-02-23 | **Code Fix** | Ankit | Done | Implemented Sync event handlers: patient_added, stash_imported, queue_ended, etc. |
| 2026-02-24 | `/v1/sync/push` (POST) | Ankit | Passed | patient_added creates patient/queue/entry, call_now updates state, dedup works |
| 2026-02-24 | `/v1/sync/pull` (GET) | Ankit | Passed | 6 cases - since param, deviceId exclusion, pagination, auth, validation |
| 2026-02-24 | **Code Fix** | Ankit | Done | Added MissingServletRequestParameterException handler for proper 400 response |
| 2026-02-24 | `/v1/orgs/:orgId/queues/active` (GET) | Ankit | Passed | Full flow with entries, stash display |
| 2026-02-24 | `/v1/orgs/:orgId/queues/:queueId/end` (POST) | Ankit | Passed | Stashes WAITING entries, returns count |
| 2026-02-24 | `/v1/orgs/:orgId/queues/:queueId/import-stash` (POST) | Ankit | Passed | Imports stash to new queue |
| 2026-02-24 | `/v1/orgs/:orgId/patients` (GET) | Ankit | Passed | Pagination, search filter works |
| 2026-02-24 | `/v1/orgs/:orgId/patients/search` (GET) | Ankit | Passed | Autocomplete - lightweight response |
| 2026-02-24 | `/v1/orgs/:orgId/patients/lookup` (GET) | Ankit | Passed | Phone lookup returns found:true |
| 2026-02-24 | `/v1/orgs/:orgId/patients/:patientId/thread` (GET) | Ankit | Passed | Visit history with full data |
| 2026-02-24 | `/v1/orgs/:orgId/patients/:patientId/visits` (POST) | Ankit | Passed | Requires doctor token (patient:add_notes) |
| 2026-02-24 | `/v1/orgs/:orgId/patients/:patientId/visits/:visitId` (PUT) | Ankit | Passed | Update visit works |
| 2026-02-24 | `/v1/orgs/:orgId/bill-templates` (GET) | Ankit | Passed | Returns templates |
| 2026-02-24 | `/v1/orgs/:orgId/bill-templates` (POST) | Ankit | Passed | Created 2 templates (Consultation, Medicines) |
| 2026-02-24 | `/v1/orgs/:orgId/bills` (POST) | Ankit | Passed | Bill items use `name` field, total auto-computed |
| 2026-02-24 | `/v1/orgs/:orgId/bills/:billId` (GET) | Ankit | Passed | Full bill with patient/clinic info |
| 2026-02-24 | `/v1/orgs/:orgId/bills/:billId/mark-paid` (PUT) | Ankit | Passed | Sets isPaid=true, paidAt timestamp |
| 2026-02-24 | `/v1/orgs/:orgId/bills` (GET) | Ankit | Passed | Filters (date, status), summary totals |
| 2026-02-24 | `/v1/orgs/:orgId/analytics` (GET) | Ankit | Passed | Summary, comparison, hourly distribution, topComplaints |

---

## Notes

_Add any testing notes, environment details, or blockers here_

### Client Discussion Required

**Stash Import Token Priority Issue:**
- Currently, stashed patients keep their original token numbers
- If Patient A was Token #3 yesterday (stashed), they remain Token #3 today
- New patients today get Token #1, #2, etc.
- **Problem:** Stashed patients always end up behind new patients even though they waited longer
- **Recommendation:** Discuss with client - should stashed patients be imported FIRST (get Token #1, #2...) or have a priority flag?
- **Options:**
  1. Force import before new registrations (UX flow change)
  2. Add `isPriority` flag for stashed entries
  3. Renumber stashed patients on import (but SMS token won't match)

### Test Environment
- Base URL: `http://localhost:8080/v1`
- Postman Collection: `ClinicOS API v3.2`

### Common Headers
```
Authorization: Bearer <JWT>
X-Device-Id: <device-uuid>
X-App-Version: 1.0.0
Content-Type: application/json
```

---

_Last updated by: [Your Name]_
