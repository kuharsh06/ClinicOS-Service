# Student Flow — Frontend Integration Guide

**Date:** 02 March 2026
**Backend Version:** Current (no new endpoints created)

---

## Summary

The student flow reuses **100% of existing endpoints**. No new API endpoints were created.

**Backend changes made:**
1. New `student` role with 10 permissions (SQL seed)
2. `POST /orgs` accepts optional `creatorRole: "student"` field
3. State machine allows direct `waiting → completed` (mark_complete on waiting entry)
4. Sync event role checks allow `student` for: `patient_added`, `patient_removed`, `mark_complete`, `visit_saved`

---

## 1. Student Onboarding

### Step 1: OTP Login (unchanged)

```
POST /auth/otp/request
{
  "phone": "+919876543210",
  "countryCode": "+91"
}

POST /auth/otp/verify
{
  "phone": "+919876543210",
  "countryCode": "+91",
  "otp": "123456",
  "deviceId": "device-uuid-here"
}
```

Response (new user, no org yet):
```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbG...",
    "refreshToken": "eyJhbG...",
    "expiresAt": 1709380000000,
    "isNewUser": true,
    "user": {
      "userId": "user-uuid",
      "phone": "+919876543210",
      "name": null,
      "orgId": null,
      "roles": [],
      "permissions": [],
      "isProfileComplete": false
    }
  }
}
```

**Frontend logic:** `orgId === null` → show onboarding. Ask "Are you a Doctor or Student?"

### Step 2: Create Org with Student Role

```
POST /v1/orgs
Authorization: Bearer <accessToken>
X-Device-Id: <deviceId>
Content-Type: application/json

{
  "name": "AIIMS Delhi",
  "address": "Ansari Nagar, New Delhi",
  "city": "New Delhi",
  "state": "Delhi",
  "pin": "110029",
  "creatorRole": "student",
  "workingHours": {
    "monday": { "shifts": [{ "open": "09:00", "close": "17:00" }] },
    "tuesday": { "shifts": [{ "open": "09:00", "close": "17:00" }] },
    "wednesday": { "shifts": [{ "open": "09:00", "close": "17:00" }] },
    "thursday": { "shifts": [{ "open": "09:00", "close": "17:00" }] },
    "friday": { "shifts": [{ "open": "09:00", "close": "17:00" }] },
    "saturday": { "shifts": [{ "open": "09:00", "close": "13:00" }] },
    "sunday": { "shifts": [] }
  },
  "creator": {
    "name": "Rahul Sharma"
  }
}
```

**Key field: `"creatorRole": "student"`** — this is the ONLY difference from doctor onboarding.

Response:
```json
{
  "success": true,
  "data": {
    "org": {
      "orgId": "org-uuid",
      "name": "AIIMS Delhi",
      "address": "Ansari Nagar, New Delhi",
      "city": "New Delhi",
      "state": "Delhi",
      "pin": "110029",
      "brandColor": "#059669",
      "smsLanguage": "hi",
      "workingHours": { ... },
      "isOpenToday": true,
      "currentShift": { "open": "09:00", "close": "17:00" },
      "createdAt": "2026-03-02T10:00:00Z"
    },
    "member": {
      "userId": "user-uuid",
      "phone": "+919876543210",
      "name": "Rahul Sharma",
      "roles": ["student"],
      "permissions": [
        "org:view",
        "queue:view",
        "queue:manage",
        "patient:view",
        "patient:view_clinical",
        "patient:create",
        "patient:update",
        "visit:create",
        "visit:update",
        "visit:view"
      ],
      "isActive": true,
      "isProfileComplete": true,
      "profileData": null,
      "joinedAt": "2026-03-02T10:00:00Z"
    },
    "accessToken": "eyJhbG...",
    "refreshToken": "eyJhbG..."
  }
}
```

**Frontend detection:** `roles.includes("student")` → enable student UI mode.

**Note:** `isProfileComplete: true` — students skip the doctor profile completion screen.

### Step 3: Subsequent Logins

After OTP verify on a returning student, the response includes:
```json
{
  "user": {
    "orgId": "org-uuid",
    "roles": ["student"],
    "permissions": ["org:view", "queue:view", "queue:manage", ...],
    "isProfileComplete": true
  }
}
```

**Frontend logic:** `roles.includes("student")` → student mode. No extra API call needed.

---

## 2. Queue — Auto-Creation (Frontend-Driven)

Students have a permanent queue. The frontend creates it once and never ends it.

### On First Launch (No Queue Exists)

```
GET /v1/orgs/{orgId}/queues/active?doctorId={studentUserUuid}
```

If the response shows no active queue (empty/404), the frontend auto-creates one by sending a `patient_added` event with a new queueId. The server auto-creates the queue when it sees a queueId that doesn't exist.

**Alternatively**, the frontend can send any patient_added event — if the queueId doesn't exist, the server creates the queue automatically (existing behavior, no change needed).

### Queue Never Ends

- Frontend **NEVER** sends `queue_ended` event
- Frontend **NEVER** sends `queue_paused` / `queue_resumed`
- The same queue persists across days, weeks, months
- The `createdAt` field on the queue will be the original creation date — this is fine

### Get Queue State

```
GET /v1/orgs/{orgId}/queues/active?doctorId={studentUserUuid}
```

Response (same format as doctor — `QueueResponse`):
```json
{
  "success": true,
  "data": {
    "queue": {
      "queueId": "queue-uuid",
      "orgId": "org-uuid",
      "doctorId": "student-user-uuid",
      "status": "active",
      "lastToken": 5,
      "totalPausedMs": 0,
      "createdAt": "2026-03-01T09:00:00Z",
      "entries": [
        {
          "entryId": "entry-uuid-1",
          "queueId": "queue-uuid",
          "patientId": "patient-uuid",
          "tokenNumber": 1,
          "state": "waiting",
          "position": 1,
          "complaintTags": ["fever"],
          "isBilled": false,
          "registeredAt": 1709290000000,
          "patientName": "John Doe",
          "patientPhone": "+919999999999",
          "patientAge": 45,
          "patientGender": "male"
        },
        {
          "entryId": "entry-uuid-2",
          "queueId": "queue-uuid",
          "patientId": "patient-uuid-2",
          "tokenNumber": 2,
          "state": "completed",
          "position": 2,
          "isBilled": false,
          "registeredAt": 1709291000000,
          "completedAt": 1709292000000,
          "patientName": "Jane Smith",
          "patientPhone": "+919888888888"
        }
      ]
    },
    "previousQueueStash": [],
    "lastEventTimestamp": 1709292000000
  }
}
```

**Frontend filtering:**
- **Waiting tab:** entries where `state === "waiting"`
- **Done tab:** entries where `state === "completed"`
- No entry will have `state === "now_serving"` for students (they never send `call_now`)
- `isBilled` will always be `false` (no billing)
- `previousQueueStash` will always be `[]` (queue never ends, so no stash)

---

## 3. Adding a Patient

Same as doctor. Push a `patient_added` event via sync:

```
POST /v1/sync/push
Authorization: Bearer <accessToken>
Content-Type: application/json

{
  "events": [
    {
      "eventId": "evt-uuid-1",
      "eventType": "patient_added",
      "targetEntity": "entry-uuid-new",
      "targetTable": "queue_entries",
      "schemaVersion": 1,
      "deviceId": "device-uuid",
      "deviceTimestamp": 1709300000000,
      "payload": {
        "queueId": "queue-uuid",
        "patientId": "patient-uuid-new",
        "tokenNumber": 6,
        "doctorId": "student-user-uuid",
        "patient": {
          "name": "New Patient",
          "phone": "+919888888888",
          "age": 30,
          "gender": "male"
        },
        "complaintTags": ["cough", "cold"]
      }
    }
  ]
}
```

Response:
```json
{
  "success": true,
  "data": {
    "accepted": [
      { "eventId": "evt-uuid-1", "serverReceivedAt": 1709300001000 }
    ],
    "rejected": []
  }
}
```

---

## 4. Mark as Done (Direct — No call_now Needed)

This is the key difference from the doctor flow. Students send `mark_complete` directly on a `waiting` entry. No `call_now` required.

```
POST /v1/sync/push
Authorization: Bearer <accessToken>

{
  "events": [
    {
      "eventId": "evt-uuid-2",
      "eventType": "mark_complete",
      "targetEntity": "entry-uuid-1",
      "targetTable": "queue_entries",
      "schemaVersion": 1,
      "deviceId": "device-uuid",
      "deviceTimestamp": 1709310000000,
      "payload": {
        "doctorId": "student-user-uuid"
      }
    }
  ]
}
```

**State transition:** `waiting → completed` (server now allows this directly).

The server also:
- Sets `completedAt` timestamp
- Increments `patient.totalVisits`
- Updates `patient.lastVisitDate`

---

## 5. Save a Visit (Consultation)

Same as doctor. After examining the patient:

```
POST /v1/sync/push

{
  "events": [
    {
      "eventId": "evt-uuid-3",
      "eventType": "visit_saved",
      "targetEntity": "patient-uuid",
      "targetTable": "visits",
      "schemaVersion": 1,
      "deviceId": "device-uuid",
      "deviceTimestamp": 1709311000000,
      "payload": {
        "visitId": "visit-uuid-new",
        "queueEntryId": "entry-uuid-1",
        "data": {
          "vitals": { "bp": "120/80", "pulse": "72", "temp": "98.6", "weight": "70" },
          "diagnosis": "Viral fever with mild dehydration",
          "examination": "Throat congestion, mild fever",
          "prescriptions": [
            {
              "name": "Paracetamol 500mg",
              "dosage": "1 tab",
              "frequency": "TDS",
              "duration": "3 days",
              "instructions": "After food"
            }
          ],
          "labOrders": []
        },
        "complaintTags": ["fever", "cough"],
        "imageIds": ["img-uuid-1", "img-uuid-2"],
        "schemaVersion": 1
      }
    }
  ]
}
```

**Note:** `imageIds` links previously uploaded images to this visit.

---

## 6. Upload Images

Same API as doctor:

```
POST /v1/orgs/{orgId}/images/upload
Authorization: Bearer <accessToken>
Content-Type: multipart/form-data

file: <binary>
patientId: patient-uuid (optional)
visitId: visit-uuid (optional)
caption: "X-ray chest PA view" (optional)
tags: "xray,chest" (optional, comma-separated)
metadata: '{"bodyPart":"chest"}' (optional, JSON string)
```

Response:
```json
{
  "success": true,
  "data": {
    "imageId": "img-uuid",
    "imageUrl": "https://clinicos.codingrippler.com/uploads/org-uuid/patients/patient-uuid/file-uuid.jpg",
    "thumbnailUrl": "https://clinicos.codingrippler.com/uploads/org-uuid/patients/patient-uuid/file-uuid_thumb.jpg",
    "originalFilename": "xray.jpg",
    "fileType": "image",
    "mimeType": "image/jpeg",
    "fileSizeBytes": 245000,
    "caption": "X-ray chest PA view",
    "tags": ["xray", "chest"],
    "uploadedAt": "2026-03-02T10:30:00Z"
  }
}
```

### Gallery — List My Uploads

```
GET /v1/orgs/{orgId}/images/my-uploads?limit=20&afterCursor=<cursor>
```

Response:
```json
{
  "success": true,
  "data": {
    "images": [
      {
        "imageId": "img-uuid",
        "thumbnailUrl": "https://.../_thumb.jpg",
        "fullUrl": "https://.../file.jpg",
        "originalFilename": "xray.jpg",
        "fileType": "image",
        "mimeType": "image/jpeg",
        "caption": "X-ray chest PA view",
        "tags": ["xray", "chest"],
        "visitId": "visit-uuid",
        "patientId": "patient-uuid",
        "doctorName": "Rahul Sharma",
        "uploadedAt": "2026-03-02T10:30:00Z"
      }
    ],
    "meta": {
      "pagination": {
        "nextCursor": "img-uuid-last",
        "hasMore": true
      }
    }
  }
}
```

---

## 7. View Patient History (Thread)

```
GET /v1/orgs/{orgId}/patients/{patientId}/thread?limit=20
```

Returns visits with images, prescriptions, lab orders — same as doctor view.

---

## 8. Remove a Patient from Queue

If a student adds a patient by mistake:

```
POST /v1/sync/push

{
  "events": [
    {
      "eventId": "evt-uuid-4",
      "eventType": "patient_removed",
      "targetEntity": "entry-uuid",
      "targetTable": "queue_entries",
      "schemaVersion": 1,
      "deviceId": "device-uuid",
      "deviceTimestamp": 1709320000000,
      "payload": {
        "reason": "added_by_mistake"
      }
    }
  ]
}
```

---

## 9. Complete Endpoint Reference for Students

### Endpoints Students USE:

| Endpoint | Method | Purpose | Permission |
|----------|--------|---------|------------|
| `/auth/otp/request` | POST | Request OTP | Public |
| `/auth/otp/verify` | POST | Verify OTP, get tokens | Public |
| `/auth/refresh` | POST | Refresh access token | Auth |
| `/v1/orgs` | POST | Create org (with `creatorRole: "student"`) | Auth |
| `/v1/orgs/{orgId}` | GET | Get org details | `org:view` |
| `/v1/sync/push` | POST | Push events (add patient, mark complete, save visit) | Auth only (role check per event) |
| `/v1/sync/pull` | GET | Pull events from server | Auth only |
| `/v1/orgs/{orgId}/queues/active` | GET | Get active queue snapshot | `queue:view` |
| `/v1/orgs/{orgId}/patients/{patientId}/thread` | GET | Patient visit history | `patient:view` |
| `/v1/orgs/{orgId}/images/upload` | POST | Upload image/PDF | `visit:create` |
| `/v1/orgs/{orgId}/images/my-uploads` | GET | Gallery (my uploads) | `visit:create` |
| `/v1/orgs/{orgId}/images/{imageId}` | GET | Image detail | `patient:view_clinical` |
| `/v1/orgs/{orgId}/patients/{patientId}/images` | GET | Patient images | `patient:view_clinical` |

### Sync Events Students CAN Send:

| Event Type | Purpose | State Transition |
|------------|---------|-----------------|
| `patient_added` | Add patient to queue | Creates entry in `waiting` state |
| `patient_removed` | Remove patient from queue | `waiting → removed` |
| `mark_complete` | Mark consultation done | `waiting → completed` (direct!) |
| `visit_saved` | Save consultation data | Creates/updates visit record |

### Sync Events Students CANNOT Send:

| Event Type | Why Not |
|------------|---------|
| `call_now` | No "now serving" concept |
| `step_out` | No stepping out |
| `queue_paused` | Queue never pauses |
| `queue_resumed` | Queue never pauses |
| `queue_ended` | Queue never ends |
| `stash_imported` | No stash (queue never ends) |
| `bill_created` | No billing |
| `bill_updated` | No billing |
| `bill_paid` | No billing |

If any of these are sent, the server returns:
```json
{
  "rejected": [{
    "eventId": "...",
    "code": "UNAUTHORIZED_ROLE",
    "reason": "User roles [student] cannot perform call_now"
  }]
}
```

### Endpoints Students DO NOT USE:

| Endpoint | Why |
|----------|-----|
| `/v1/orgs/{orgId}/members/*` | No team management |
| `/v1/orgs/{orgId}/billing/*` | No billing |
| `/v1/orgs/{orgId}/analytics/*` | No analytics |
| `/v1/orgs/{orgId}/sms/*` | No SMS |
| `/v1/orgs/{orgId}/doctors/*` | Single user, no doctor list |

---

## 10. Frontend UI Conditional Logic

```typescript
// Detection
const isStudent = user.roles.includes('student');

// Tab bar
const tabs = isStudent
  ? ['Queue', 'Gallery', 'Settings']           // 3 tabs
  : ['Home', 'Queue', 'Patients', 'Settings'];  // 4 tabs (doctor)

// Queue screen
if (isStudent) {
  // Hide: NowServingCard, CallNext, StepOut, EndQueue, Pause, Resume
  // Hide: Removed tab, billing chips, stash import
  // Show: Waiting tab, Done tab, Add Patient FAB
  // Tap patient → navigate to Patient Thread (no action sheet)
  // "Mark Done" button → sends mark_complete directly
}

// Settings screen
if (isStudent) {
  // Hide: Clinic Profile, Working Hours, Manage Team,
  //        SMS Templates, Bill Templates, Complaint Tags
  // Show: My Profile, Language, Privacy, Offline & Sync
}
```

---

## 11. Typical Student Session Flow

```
1. Open app
2. OTP login → roles: ["student"] → student UI mode
3. Get queue → GET /v1/orgs/{orgId}/queues/active?doctorId=xxx
4. Queue has entries from previous days (never cleared)

5. Add patient:
   → Push: patient_added event
   → Entry appears in Waiting tab

6. Tap patient in Waiting tab:
   → Navigate to Patient Thread
   → See past visits (if any)
   → Tap "New Visit" → Consultation screen

7. Fill consultation:
   → Vitals, diagnosis, prescriptions, images
   → Upload images: POST /images/upload
   → Save visit: Push visit_saved event (with imageIds)

8. Mark as done:
   → Push: mark_complete event (waiting → completed directly)
   → Entry moves to Done tab

9. Repeat for next patient

10. Close app — queue persists. Next day, same queue.
```

---

## 12. No New Endpoints Created

Everything uses existing APIs:
- Auth: `/auth/otp/*` (unchanged)
- Org: `POST /v1/orgs` (added optional `creatorRole` field — backward compatible)
- Sync: `/v1/sync/push` and `/v1/sync/pull` (unchanged)
- Queue: `/v1/orgs/{orgId}/queues/active` (unchanged)
- Images: `/v1/orgs/{orgId}/images/*` (unchanged)
- Patients: `/v1/orgs/{orgId}/patients/*` (unchanged)
