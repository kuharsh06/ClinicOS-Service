# ClinicOS Sync Events — Frontend Payloads (Backend-Aligned)

> Exact payloads the frontend must send. Verified against backend `SyncService.java`.
> Any deviation from these structures will cause event rejection or server errors.

---

## Push Request

```
POST /v1/sync/push
Authorization: Bearer <accessToken>
X-Device-Id: <deviceId>
Content-Type: application/json
```

```json
{
  "deviceId": "58086860-ba99-4704-b283-1daf208e31ac",
  "events": [ ... ]
}
```

---

## Event Envelope

Every event in the `events[]` array must have this structure:

```json
{
  "eventId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "deviceId": "58086860-ba99-4704-b283-1daf208e31ac",
  "userId": "4b06dee4-e7a3-4ee8-8ca5-147a492eaca3",
  "userRoles": ["admin", "doctor"],
  "eventType": "patient_added",
  "targetEntity": "uuid-of-primary-entity",
  "targetTable": "queue_entry",
  "payload": { ... },
  "deviceTimestamp": 1772108000000,
  "schemaVersion": 1
}
```

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `eventId` | string (UUID) | Yes | Client-generated, dedup key |
| `deviceId` | string | Yes | Must match request-level deviceId |
| `userId` | string (UUID) | Yes | User UUID (not member UUID) |
| `userRoles` | string[] | Yes | Informational only — server checks DB roles |
| `eventType` | string | Yes | One of the 12 synced event types |
| `targetEntity` | string (UUID) | Yes | Primary entity UUID (varies by event type) |
| `targetTable` | string | Yes | `"queue_entry"` / `"queue"` / `"visit"` / `"patient_thread"` / `"billing"` |
| `payload` | object | Yes | Event-specific data |
| `deviceTimestamp` | number | Yes | Epoch milliseconds when action happened |
| `schemaVersion` | integer | Yes | Must be >= 1 |

**Fields NOT sent** (server derives these):
- `orgId` — from JWT token
- `serverReceivedAt` — set by server
- `synced` — client-side only
- `doctorId` at envelope level — send inside payload where needed

**CRITICAL — field names must match exactly:**
- `eventType` — NOT `type`
- `targetEntity` — NOT `target`
- `targetTable` — NOT `table`
- `deviceTimestamp` — NOT `timestamp`
- `schemaVersion` at envelope level — NOT inside payload

If any `@NotBlank` / `@NotNull` field is missing or empty, the server returns **HTTP 400** (Bad Request) — NOT a rejection in the response body. A 400 means the event never reached processing. Fix the envelope first.

---

## Push Response

```json
{
  "success": true,
  "data": {
    "accepted": [
      { "eventId": "uuid", "serverReceivedAt": 1772108001234 }
    ],
    "rejected": [
      { "eventId": "uuid", "code": "PROCESSING_ERROR", "reason": "Invalid state transition" }
    ],
    "serverTimestamp": 1772108001234
  }
}
```

| Rejection Code | Meaning |
|----------------|---------|
| `DUPLICATE_IGNORED` | Event already processed (safe to ignore) |
| `INVALID_EVENT_TYPE` | Unknown eventType |
| `UNAUTHORIZED_ROLE` | User's role can't perform this event |
| `SCHEMA_MISMATCH` | Invalid schemaVersion |
| `PROCESSING_ERROR` | Business logic failure (state guard, not found, etc.) |

---

## Pull Request

```
GET /v1/sync/pull?since=<timestamp>&deviceId=<deviceId>&limit=100
Authorization: Bearer <accessToken>
X-Device-Id: <deviceId>
```

| Param | Type | Required | Default | Notes |
|-------|------|----------|---------|-------|
| `since` | number | No | 0 | Epoch ms — from `lastEventTimestamp` or previous pull's `serverTimestamp` |
| `deviceId` | string | Yes | — | Events from THIS device are excluded |
| `limit` | integer | No | 100 | Max 500 |

## Pull Response

```json
{
  "success": true,
  "data": {
    "events": [ ... ],
    "serverTimestamp": 1772108005678,
    "hasMore": false
  }
}
```

Use `serverTimestamp` as `since` for the next pull. If `hasMore: true`, pull again immediately.

---

# Event Payloads (12 Synced Events)

---

## 1. patient_added

**Who:** assistant, doctor
**targetEntity:** new entry UUID (client-generated)
**targetTable:** `"queue_entry"`

```json
{
  "eventType": "patient_added",
  "targetEntity": "entry-a1b2c3d4",
  "targetTable": "queue_entry",
  "payload": {
    "queueId": "queue-i9j0k1l2",
    "patientId": "pat-e5f6g7h8",
    "tokenNumber": 24,
    "doctorId": "4b06dee4-e7a3-4ee8-8ca5-147a492eaca3",
    "patient": {
      "phone": "9876543210",
      "name": "Meena Devi",
      "age": 32,
      "gender": "female",
      "smsConsent": true
    },
    "complaintTags": ["fever", "body_pain"],
    "complaintText": "Fever, cough since 3 days"
  }
}
```

| Payload Field | Type | Required | Notes |
|---------------|------|----------|-------|
| `queueId` | string | Yes | Existing queue UUID, or new UUID (auto-creates queue) |
| `patientId` | string | Yes | Existing patient UUID, or new UUID (auto-creates patient) |
| `tokenNumber` | integer | Yes | Next token number (client-computed) |
| `doctorId` | string | Yes | Doctor's **user UUID** (needed to create queue) |
| `patient.phone` | string | Yes | 10-digit phone |
| `patient.name` | string | Yes | **Cannot be null** — causes DB error |
| `patient.age` | integer | No | Can be null |
| `patient.gender` | string | No | `"male"` / `"female"` / `"other"` / null — **NOT "M"/"F"** |
| `patient.smsConsent` | boolean | No | Default: `true`. Patient's SMS notification preference |
| `complaintTags` | string[] | No | Structured tags for analytics |
| `complaintText` | string | No | Free text |

**IMPORTANT:**
- Patient fields MUST be nested under `"patient"` key — not flat in payload
- `gender` must be full lowercase word: `"male"`, `"female"`, `"other"` — not `"M"`, `"F"`
- `patient.name` is required (DB NOT NULL constraint)
- If `queueId` doesn't exist, a new queue is auto-created for the doctor
- **Re-visit update:** When an existing patient is re-added, `age`, `gender`, and `smsConsent` are updated on the patient record (only if non-null in the payload). `name` and `phone` are NOT updated.

---

## 2. patient_removed

**Who:** assistant, doctor
**targetEntity:** entry UUID to remove
**targetTable:** `"queue_entry"`
**State guard:** entry must be in `waiting`

```json
{
  "eventType": "patient_removed",
  "targetEntity": "entry-a1b2c3d4",
  "targetTable": "queue_entry",
  "payload": {
    "reason": "no_show"
  }
}
```

| Payload Field | Type | Required | Values |
|---------------|------|----------|--------|
| `reason` | string | No | `"no_show"` / `"cancelled"` / `"left"` |

**Note:** Can only remove `waiting` patients. Removing `now_serving` patients is rejected.

---

## 3. call_now

**Who:** assistant, doctor
**targetEntity:** entry UUID to call
**targetTable:** `"queue_entry"`
**State guard:** entry must be `waiting` AND no other patient is `now_serving`

```json
{
  "eventType": "call_now",
  "targetEntity": "entry-a1b2c3d4",
  "targetTable": "queue_entry",
  "payload": {
    "previousNowServing": "entry-x9y8z7w6"
  }
}
```

| Payload Field | Type | Required | Notes |
|---------------|------|----------|-------|
| `previousNowServing` | string / null | No | Informational — server doesn't use this |

**IMPORTANT:** Server enforces **single now_serving** per queue. If another patient is already being served, the event is rejected with "another patient is already being served". Complete or step_out the current patient first.

---

## 4. step_out

**Who:** assistant, doctor
**targetEntity:** entry UUID currently being served
**targetTable:** `"queue_entry"`
**State guard:** entry must be `now_serving`

```json
{
  "eventType": "step_out",
  "targetEntity": "entry-a1b2c3d4",
  "targetTable": "queue_entry",
  "payload": {
    "reason": "blood_test"
  }
}
```

| Payload Field | Type | Required | Values |
|---------------|------|----------|--------|
| `reason` | string | No | `"blood_test"` / `"xray"` / `"other"` |

**Behavior:** Patient moves from `now_serving` back to `waiting` at the **end of the queue** (not their original position). They can be called again later.

---

## 5. mark_complete

**Who:** assistant, doctor
**targetEntity:** entry UUID currently being served
**targetTable:** `"queue_entry"`
**State guard:** entry must be `now_serving`

```json
{
  "eventType": "mark_complete",
  "targetEntity": "entry-a1b2c3d4",
  "targetTable": "queue_entry",
  "payload": {
    "completedBy": "4b06dee4-e7a3-4ee8-8ca5-147a492eaca3",
    "completedByRole": "doctor"
  }
}
```

| Payload Field | Type | Required | Notes |
|---------------|------|----------|-------|
| `completedBy` | string | No | User UUID who completed. Informational. |
| `completedByRole` | string | No | `"assistant"` / `"doctor"`. Informational. |

**Side effects:** Patient's `totalVisits` incremented, `lastVisitDate` updated.

---

## 6. queue_paused

**Who:** assistant, doctor
**targetEntity:** queue UUID
**targetTable:** `"queue"`
**State guard:** queue must be `active` (rejected if already paused or ended)

```json
{
  "eventType": "queue_paused",
  "targetEntity": "queue-i9j0k1l2",
  "targetTable": "queue",
  "payload": {}
}
```

Empty payload. Server uses `deviceTimestamp` as pause start time.

---

## 7. queue_resumed

**Who:** assistant, doctor
**targetEntity:** queue UUID
**targetTable:** `"queue"`
**State guard:** queue must be `paused` (rejected if active or ended)

```json
{
  "eventType": "queue_resumed",
  "targetEntity": "queue-i9j0k1l2",
  "targetTable": "queue",
  "payload": {}
}
```

Empty payload. Server computes pause duration from `deviceTimestamp`.

---

## 8. queue_ended

**Who:** assistant, doctor
**targetEntity:** queue UUID
**targetTable:** `"queue"`
**State guard:** queue must not be ended (idempotent if already ended)

```json
{
  "eventType": "queue_ended",
  "targetEntity": "queue-i9j0k1l2",
  "targetTable": "queue",
  "payload": {
    "stashedEntryIds": ["entry-001", "entry-002", "entry-003"],
    "stashedCount": 3
  }
}
```

| Payload Field | Type | Required | Notes |
|---------------|------|----------|-------|
| `stashedEntryIds` | string[] | No | Entry UUIDs to stash (waiting patients) |
| `stashedCount` | integer | No | Informational — server uses array length |

**Server behavior:**
1. Auto-completes any `now_serving` patient
2. Stashes entries listed in `stashedEntryIds` (only if they're still in `waiting`)
3. Sets queue status to `ended`

---

## 9. stash_imported

**Who:** assistant, doctor
**targetEntity:** NEW queue UUID (client-generated for the new session)
**targetTable:** `"queue_entry"`

```json
{
  "eventType": "stash_imported",
  "targetEntity": "queue-new-session-uuid",
  "targetTable": "queue_entry",
  "payload": {
    "doctorId": "4b06dee4-e7a3-4ee8-8ca5-147a492eaca3",
    "importedEntryIds": ["entry-001", "entry-002", "entry-003"],
    "importedCount": 3
  }
}
```

| Payload Field | Type | Required | Notes |
|---------------|------|----------|-------|
| `doctorId` | string | **Yes** | Doctor's user UUID — needed to create new queue |
| `importedEntryIds` | string[] | Yes | Entry UUIDs to import from stash |
| `importedCount` | integer | No | Informational |

**Server behavior:**
1. Creates new queue (if targetEntity queue doesn't exist) for the doctor
2. Creates new entries in the new queue for each stashed entry
3. Original stashed entries are marked `REMOVED`
4. Imported entries get `WAITING` state with fresh positions

---

## 10. stash_dismissed

**Who:** assistant, doctor
**targetEntity:** source queue UUID (the ENDED queue whose stash is being dismissed)
**targetTable:** `"queue_entry"`

```json
{
  "eventType": "stash_dismissed",
  "targetEntity": "queue-ended-session-uuid",
  "targetTable": "queue_entry",
  "payload": {
    "doctorId": "4b06dee4-e7a3-4ee8-8ca5-147a492eaca3",
    "importedEntryIds": ["entry-001", "entry-002", "entry-003"]
  }
}
```

| Payload Field | Type | Required | Notes |
|---------------|------|----------|-------|
| `doctorId` | string | **Yes** | Doctor's user UUID |
| `importedEntryIds` | string[] | Yes | Entry UUIDs to dismiss from stash |

**Server behavior:**
1. Validates source queue exists and is in `ended` state
2. For each entry: skips if not found or not in `stashed` state
3. Marks matching entries as `REMOVED` with reason `"dismissed"`
4. `previousQueueStash` becomes empty (or reduced) on next fetch

**Idempotent:** If entries are already removed (imported or dismissed), they are silently skipped. No error.

**Conflict with `stash_imported`:** Both consume stashed entries. Whichever syncs first wins. The second becomes a no-op.

---

## 11. visit_saved

**Who:** doctor only
**targetEntity:** patient UUID (NOT visit UUID)
**targetTable:** `"patient_thread"`

```json
{
  "eventType": "visit_saved",
  "targetEntity": "pat-e5f6g7h8",
  "targetTable": "patient_thread",
  "payload": {
    "visitId": "visit-q7r8s9t0",
    "patientId": "pat-e5f6g7h8",
    "queueEntryId": "entry-a1b2c3d4",
    "complaintTags": ["fever", "body_pain"],
    "data": {
      "vitals": {
        "bp": "120/80",
        "temp": "99.5",
        "pulse": "72",
        "weight": "68",
        "spo2": "98"
      },
      "diagnosis": "Viral fever",
      "examination": "Throat congested",
      "prescriptions": [
        {
          "medicineName": "Paracetamol 500mg",
          "dosage": "1 tab",
          "frequency": "TDS",
          "timing": "After Food",
          "duration": "3 days"
        }
      ],
      "labOrders": [
        {
          "testName": "CBC",
          "notes": "Check for infection"
        }
      ],
      "followUp": "Review in 3 days",
      "attachments": []
    },
    "schemaVersion": 1
  }
}
```

| Payload Field | Type | Required | Notes |
|---------------|------|----------|-------|
| `visitId` | string | **Yes** | Client-generated UUID. **Cannot be null/blank.** |
| `patientId` | string | No | Same as targetEntity — included for clarity |
| `queueEntryId` | string | No | Links visit to queue entry. null for standalone visits. |
| `complaintTags` | string[] | No | Tags for analytics |
| `data` | object | No | Clinical data blob — backend stores as-is |
| `schemaVersion` | integer | No | Defaults to 1 |

**IMPORTANT:**
- `targetEntity` must be the **patient UUID**, not the visit UUID
- `visitId` is in the **payload**, not targetEntity
- If `visitId` already exists → **updates** the visit (doesn't create duplicate)
- If `visitId` is new → **creates** visit + increments patient `totalVisits`
- `data` is opaque — backend stores it as JSON without interpreting

---

## 12. bill_paid (create bill + mark paid — single event)

**Who:** assistant, doctor
**targetEntity:** bill UUID (client-generated)
**targetTable:** `"billing"`

```json
{
  "eventType": "bill_paid",
  "targetEntity": "bill-u1v2w3x4",
  "targetTable": "billing",
  "payload": {
    "patientId": "pat-e5f6g7h8",
    "queueEntryId": "entry-a1b2c3d4",
    "items": [
      { "name": "Consultation Fee", "amount": 500 },
      { "name": "ECG", "amount": 300 }
    ],
    "totalAmount": 800,
    "paidAt": 1772108000000
  }
}
```

| Payload Field | Type | Required | Notes |
|---------------|------|----------|-------|
| `patientId` | string | **Yes** | Patient UUID — **cannot be null** |
| `queueEntryId` | string | No | Links bill to queue entry. Marks entry as billed. |
| `items` | array | No | Bill line items `[{name, amount}]` |
| `items[].name` | string | Yes (per item) | Item description |
| `items[].amount` | number | Yes (per item) | Amount in INR |
| `totalAmount` | number | No | If not provided, server calculates from items |
| `paidAt` | number | No | Epoch milliseconds. Falls back to `deviceTimestamp` if not provided. |

**Server behavior:**
1. Validates patient belongs to org
2. Creates `bills` row with items, total, patient info, doctor name
3. Marks bill as **paid** (`isPaid=true`, `paidAt` set)
4. Marks queue entry as `isBilled = true` (if queueEntryId provided)
5. Idempotent — if bill UUID already exists, skips silently
6. Bill appears in `GET /bills` list, pagination, and summary as **paid**

**Note:** This is a single event — creates the bill AND marks it paid in one shot. No need for separate `bill_created` + `bill_updated` events. In a real clinic, billing and payment happen at the same moment.

**Legacy events:** `bill_created` and `bill_updated` still work on the backend for backward compatibility, but `bill_paid` is the recommended single event.

---

# Events NOT Synced (Frontend Local Only)

No events are local-only anymore. All 11 primary events are synced.

---

# Events Received via Pull Only (Server-Generated)

## sms_status_updated

Server generates this when SMS gateway reports delivery status. Frontend receives it via pull.

```json
{
  "eventType": "sms_status_updated",
  "targetEntity": "entry-a1b2c3d4",
  "targetTable": "queue_entry",
  "payload": {
    "queueEntryId": "entry-a1b2c3d4",
    "smsType": "registration",
    "status": "delivered",
    "updatedAt": 1772108005000
  }
}
```

**Note:** Not yet implemented on backend (SMS gateway integration pending).

---

# State Machine Reference

```
States: waiting | now_serving | completed | removed | stashed

waiting     → now_serving  (call_now — only if no one else is now_serving)
            → removed      (patient_removed)
            → stashed      (queue_ended)

now_serving → completed    (mark_complete)
            → waiting      (step_out — goes to END of queue)

stashed     → waiting      (stash_imported — new entry in new queue)
            → removed      (stash_dismissed — dismissed from stash)

completed   → (terminal)
removed     → (terminal)
```

Queue states:
```
active  → paused  (queue_paused)
paused  → active  (queue_resumed)
active/paused → ended (queue_ended — auto-completes now_serving)
ended   → (terminal — pause/resume rejected)
```

---

# Role Permissions for Events

| Event | assistant | doctor | admin |
|-------|-----------|--------|-------|
| `patient_added` | Yes | Yes | No |
| `patient_removed` | Yes | Yes | No |
| `call_now` | Yes | Yes | No |
| `step_out` | Yes | Yes | No |
| `mark_complete` | Yes | Yes | No |
| `queue_paused` | Yes | Yes | No |
| `queue_resumed` | Yes | Yes | No |
| `queue_ended` | Yes | Yes | No |
| `stash_imported` | Yes | Yes | No |
| `stash_dismissed` | Yes | Yes | No |
| `visit_saved` | No | **Yes** | No |
| `bill_paid` | Yes | Yes | No |

---

# Common Mistakes That Cause Rejection

| Mistake | Error Code | Fix |
|---------|-----------|-----|
| `gender: "F"` | Hibernate crash | Use `"female"` |
| `patient.name` is null | DB constraint violation | Always provide name |
| Patient fields not nested under `"patient"` | name=null crash | Nest under `payload.patient` |
| `visit_saved` targetEntity is visitId | Patient not found | Set targetEntity to patientId |
| `bill_paid` missing patientId | PROCESSING_ERROR | Always include patientId |
| `stash_imported` without doctorId | Doctor not found | Add doctorId to payload |
| `call_now` while someone is serving | PROCESSING_ERROR | Complete/step_out first |
| `patient_removed` on now_serving | PROCESSING_ERROR | Only remove waiting patients |
| `step_out` on waiting patient | PROCESSING_ERROR | Only step_out now_serving |
| `visitId` missing in visit_saved | PROCESSING_ERROR | Always include visitId |
| `schemaVersion` is 0 or null | SCHEMA_MISMATCH | Must be >= 1 |

---

# Sync Flow Quick Reference

```
Boot:
  GET /queues/active?doctorId=<uuid>
  → Returns queue snapshot + lastEventTimestamp
  → Use lastEventTimestamp as "since" for first pull

Every 15 seconds:
  1. POST /sync/push  → push unsynced local events
  2. GET /sync/pull?since=<ts>&deviceId=<id>
     → Returns events from OTHER devices
     → Apply to local reducer
     → Update "since" to response.serverTimestamp

On user action:
  1. Apply to local reducer (instant UI)
  2. Save to SQLite event_log (crash-safe)
  3. Push immediately (don't wait for timer)
```
