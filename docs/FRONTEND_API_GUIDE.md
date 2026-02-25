# ClinicOS — Frontend API Integration Guide

> Complete step-by-step API flow for onboarding, org setup, member management, and profile updates.
> Base URL: `https://clinicos.codingrippler.com`

---

## Required Headers (All Requests)

| Header | Value | Required |
|--------|-------|----------|
| `Content-Type` | `application/json` | All POST/PUT |
| `Authorization` | `Bearer <accessToken>` | All except auth endpoints |
| `X-Device-Id` | Unique device identifier | Always |

---

## 1. Authentication — New User Onboarding

### 1.1 Send OTP

```
POST /v1/auth/otp/send
```

**Request:**
```json
{
  "countryCode": "+91",
  "phone": "9999999999"
}
```

**Response (200):**
```json
{
  "success": true,
  "data": {
    "requestId": "a8da0981-b13f-4936-b71c-4d8de2bde505",
    "expiresInSeconds": 300,
    "retryAfterSeconds": 30,
    "devOtp": "123456"
  }
}
```

> `devOtp` is only returned in dev mode. In production, OTP is sent via SMS.
> `retryAfterSeconds` — frontend should disable resend button for this duration.

---

### 1.2 Verify OTP

```
POST /v1/auth/otp/verify
```

**Request:**
```json
{
  "requestId": "a8da0981-b13f-4936-b71c-4d8de2bde505",
  "otp": "123456",
  "deviceId": "unique-device-id",
  "deviceInfo": {
    "platform": "android",
    "osVersion": "14",
    "appVersion": "1.0.0",
    "deviceModel": "Pixel 8"
  }
}
```

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `requestId` | string | Yes | From send OTP response |
| `otp` | string | Yes | 4-6 digits |
| `deviceId` | string | Yes | Persistent device identifier |
| `deviceInfo.platform` | string | Yes | `"android"` or `"ios"` |
| `deviceInfo.osVersion` | string | No | OS version |
| `deviceInfo.appVersion` | string | No | App version |
| `deviceInfo.deviceModel` | string | No | Device model name |

**Response — New User (200):**
```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbG...",
    "refreshToken": "eyJhbG...",
    "expiresAt": 1772093091589,
    "user": {
      "userId": "e531f37e-7534-45db-b9f5-cddc9a6147a3",
      "phone": "9999999999",
      "name": null,
      "orgId": null,
      "roles": [],
      "permissions": [],
      "isProfileComplete": false,
      "assignedDoctorId": null,
      "assignedDoctorName": null
    },
    "isNewUser": true
  }
}
```

**Response — Existing User with Org (200):**
```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbG...",
    "refreshToken": "eyJhbG...",
    "expiresAt": 1772093655958,
    "user": {
      "userId": "e531f37e-7534-45db-b9f5-cddc9a6147a3",
      "phone": "9999999999",
      "name": "Dr. Harsh Kumar",
      "orgId": "84fdcd5f-a1d5-4156-926b-881d4ab6b6f9",
      "roles": ["admin", "doctor"],
      "permissions": ["org:view", "org:update", "members:view", "..."],
      "isProfileComplete": true,
      "assignedDoctorId": null,
      "assignedDoctorName": null
    },
    "isNewUser": false
  }
}
```

### Frontend Routing Logic After Verify

```
if (isNewUser || orgId == null) {
    → Navigate to "Create Organization" screen
} else if (!isProfileComplete) {
    → Navigate to "Complete Profile" screen
} else {
    → Navigate to Dashboard
}
```

---

### 1.3 Refresh Token

```
POST /v1/auth/token/refresh
```

**Request:**
```json
{
  "refreshToken": "eyJhbG...",
  "deviceId": "unique-device-id"
}
```

**Response (200):**
```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbG...(new)...",
    "refreshToken": "eyJhbG...(new)...",
    "expiresAt": 1772179491589
  }
}
```

> Access token expires in **24 hours**. Refresh token expires in **30 days**.
> After refresh, **both tokens are rotated** — store the new pair.

---

### 1.4 Logout

```
POST /v1/auth/logout
Headers: Authorization: Bearer <accessToken>
```

**Request:**
```json
{
  "deviceId": "unique-device-id"
}
```

**Response (200):**
```json
{
  "success": true,
  "data": {
    "message": "Logged out successfully"
  }
}
```

---

## 2. Create Organization

After a new user signs up, they create their clinic/org. The creator automatically becomes **admin**.

```
POST /v1/orgs
Headers: Authorization: Bearer <accessToken>
```

**Request:**
```json
{
  "name": "Smile Dental Care",
  "address": "45, Indiranagar 100ft Road",
  "city": "Bangalore",
  "state": "Karnataka",
  "pin": "560038",
  "brandColor": "#dc2626",
  "smsLanguage": "en",
  "clinicalDataVisibility": "all_members",
  "workingHours": {
    "monday": {
      "shifts": [
        { "open": "09:00", "close": "13:00" },
        { "open": "17:00", "close": "21:00" }
      ]
    },
    "tuesday": {
      "shifts": [
        { "open": "09:00", "close": "13:00" },
        { "open": "17:00", "close": "21:00" }
      ]
    },
    "wednesday": { "shifts": [{ "open": "09:00", "close": "18:00" }] },
    "thursday": { "shifts": [{ "open": "09:00", "close": "18:00" }] },
    "friday": { "shifts": [{ "open": "09:00", "close": "18:00" }] },
    "saturday": { "shifts": [{ "open": "09:00", "close": "14:00" }] },
    "sunday": { "shifts": [] }
  },
  "creator": {
    "name": "Dr. Rahul Verma"
  }
}
```

| Field | Type | Required | Default | Validation |
|-------|------|----------|---------|------------|
| `name` | string | Yes | — | Max 200 chars |
| `address` | string | Yes | — | — |
| `city` | string | Yes | — | Max 100 chars |
| `state` | string | Yes | — | Max 100 chars |
| `pin` | string | Yes | — | Exactly 6 digits |
| `brandColor` | string | No | `#059669` | 7-char hex color |
| `smsLanguage` | string | No | `hi` | ISO 639-1 code |
| `clinicalDataVisibility` | string | No | `all_members` | `all_members` or `clinical_roles_only` |
| `workingHours` | object | Yes | — | Mon-Sun with shifts |
| `workingHours.{day}.shifts` | array | Yes | — | Each shift: `open` + `close` in 24h format |
| `creator.name` | string | Yes | — | Creator's display name |
| `logo` | string | No | null | Base64 encoded image (max 500KB) |

**Response (201):**
```json
{
  "success": true,
  "data": {
    "org": {
      "orgId": "ece62ec5-dc2d-4577-b55e-c42608df004e",
      "name": "Smile Dental Care",
      "brandColor": "#dc2626",
      "address": "45, Indiranagar 100ft Road",
      "city": "Bangalore",
      "state": "Karnataka",
      "pin": "560038",
      "smsLanguage": "en",
      "clinicalDataVisibility": "all_members",
      "workingHours": { "..." },
      "createdAt": "2026-02-25T08:22:55.407580Z",
      "nextWorkingDay": "2026-02-26",
      "isOpenToday": true
    },
    "member": {
      "userId": "817326b1-2f56-482e-9e86-b34d26101e09",
      "phone": "6666666666",
      "name": "Dr. Rahul Verma",
      "roles": ["admin"],
      "permissions": ["org:view", "org:update", "members:view", "...24 total"],
      "isActive": true,
      "isProfileComplete": false,
      "joinedAt": "2026-02-25T08:22:55.434342Z"
    },
    "accessToken": "eyJhbG...(new token with org context)",
    "refreshToken": "eyJhbG...(new refresh token)"
  }
}
```

> **IMPORTANT:** The response includes **new tokens** with org context baked in. Discard the old tokens and store these new ones. All subsequent API calls must use the new access token.

---

## 3. Update Member Roles

If the org creator (admin) also practices as a doctor, add the doctor role.

```
PUT /v1/orgs/{orgId}/members/{userId}
Headers: Authorization: Bearer <accessToken>
Permission Required: members:update
```

**Request — Make admin also a doctor:**
```json
{
  "roles": ["admin", "doctor"]
}
```

> `roles` is a **full replacement** — always include ALL desired roles, not just the new one.

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `roles` | string[] | No | Full list of roles to assign |
| `name` | string | No | Update display name |
| `isActive` | boolean | No | Activate/deactivate member |
| `assignedDoctorId` | string | No | UUID of doctor to assign (for assistants). `null` to clear. |

**Response (200):**
```json
{
  "success": true,
  "data": {
    "userId": "817326b1-2f56-482e-9e86-b34d26101e09",
    "phone": "6666666666",
    "name": "Dr. Rahul Verma",
    "roles": ["admin", "doctor"],
    "permissions": ["org:view", "org:update", "...24 total"],
    "isActive": true,
    "isProfileComplete": false,
    "joinedAt": "2026-02-25T08:22:55Z"
  }
}
```

### Available Roles

| Role | Description | Key Permissions |
|------|-------------|-----------------|
| `owner` | Full access | Everything |
| `admin` | Administrative access | Everything (24 permissions) |
| `doctor` | Clinical access | View/create patients, visits, queue ops, analytics (14 permissions) |
| `assistant` | Front desk | Queue management, patient create, billing (12 permissions) |
| `nurse` | Clinical support | Patient view, clinical data access |
| `billing` | Billing only | Billing create/view/mark paid |

### Common Role Combinations

| Use Case | Roles |
|----------|-------|
| Clinic owner who also practices | `["admin", "doctor"]` |
| Clinic manager (non-clinical) | `["admin"]` |
| Doctor only | `["doctor"]` |
| Assistant | `["assistant"]` |
| Doctor who also handles billing | `["doctor", "billing"]` |

---

## 4. Update Member Profile (Doctor Details)

After assigning the doctor role, update their clinical profile. This is a **separate call** from role assignment.

```
PUT /v1/orgs/{orgId}/members/{userId}/profile
Headers: Authorization: Bearer <accessToken>
Permission: Self (own profile) or members:update (others)
```

**Request:**
```json
{
  "profileData": {
    "specialization": "Orthodontics",
    "registrationNumber": "KA-MED-2024-8832",
    "consultationFee": 500,
    "qualification": "BDS, MDS",
    "experience": 8
  },
  "profileSchemaVersion": 1
}
```

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `profileData` | object | Yes | Flexible JSON — any fields |
| `profileSchemaVersion` | integer | Yes | Currently `1` |

### Doctor Profile Fields

| Field | Type | Required for `isProfileComplete` | Example |
|-------|------|----------------------------------|---------|
| `specialization` | string | **Yes** | `"Orthodontics"`, `"General Medicine"`, `"Pediatrics"` |
| `registrationNumber` | string | **Yes** | `"KA-MED-2024-8832"` |
| `consultationFee` | integer | No | `500` (in INR) |
| `qualification` | string | No | `"MBBS, MD"`, `"BDS, MDS"` |
| `experience` | integer | No | `8` (years) |

> **`isProfileComplete` logic for doctors:** Automatically set to `true` when both `specialization` AND `registrationNumber` are present. For non-doctor roles, it's set to `true` immediately on profile update.

**Response (200):**
```json
{
  "success": true,
  "data": {
    "userId": "817326b1-2f56-482e-9e86-b34d26101e09",
    "phone": "6666666666",
    "name": "Dr. Rahul Verma",
    "roles": ["admin", "doctor"],
    "permissions": ["..."],
    "isActive": true,
    "profileData": {
      "specialization": "Orthodontics",
      "registrationNumber": "KA-MED-2024-8832",
      "consultationFee": 500,
      "qualification": "BDS, MDS",
      "experience": 8
    },
    "profileSchemaVersion": 1,
    "isProfileComplete": true,
    "joinedAt": "2026-02-25T08:22:55Z"
  }
}
```

---

## 5. Add Team Members

Admin adds other staff members to the org.

```
POST /v1/orgs/{orgId}/members
Headers: Authorization: Bearer <accessToken>
Permission Required: members:add
```

**Request — Add an assistant:**
```json
{
  "phone": "8888888888",
  "name": "Priya Sharma",
  "roles": ["assistant"],
  "assignedDoctorId": "817326b1-2f56-482e-9e86-b34d26101e09"
}
```

**Request — Add another doctor:**
```json
{
  "phone": "7777777777",
  "name": "Dr. Anjali Mehta",
  "roles": ["doctor"]
}
```

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `phone` | string | Yes | 10-digit Indian phone number |
| `name` | string | Yes | Display name |
| `roles` | string[] | Yes | At least one role |
| `assignedDoctorId` | string | No | Doctor UUID — only for assistants |

**Response (201):**
```json
{
  "success": true,
  "data": {
    "member": {
      "userId": "06656e6d-b078-488e-bcd9-aaa16feda036",
      "phone": "8888888888",
      "name": "Priya Sharma",
      "roles": ["assistant"],
      "permissions": [
        "org:view", "members:view", "queue:view", "queue:manage",
        "patient:view", "patient:create", "patient:update",
        "billing:view", "billing:create", "billing:mark_paid",
        "sms:view_templates", "sms:manual_send"
      ],
      "isActive": true,
      "isProfileComplete": true,
      "joinedAt": "2026-02-25T08:15:18.570661Z"
    },
    "inviteSent": false
  }
}
```

> The added member can now login with their phone number using the same OTP flow. They will automatically see the org context in their login response.

---

## 6. List Members

```
GET /v1/orgs/{orgId}/members
Headers: Authorization: Bearer <accessToken>
Permission Required: members:view
```

**Response (200):**
```json
{
  "success": true,
  "data": {
    "members": [
      {
        "userId": "817326b1-...",
        "phone": "6666666666",
        "name": "Dr. Rahul Verma",
        "roles": ["admin", "doctor"],
        "permissions": ["...24 permissions..."],
        "isActive": true,
        "isProfileComplete": true,
        "joinedAt": "2026-02-25T08:22:55Z"
      },
      {
        "userId": "06656e6d-...",
        "phone": "8888888888",
        "name": "Priya Sharma",
        "roles": ["assistant"],
        "permissions": ["...12 permissions..."],
        "isActive": true,
        "isProfileComplete": true,
        "joinedAt": "2026-02-25T08:15:18Z"
      }
    ]
  }
}
```

---

## 7. Get Organization Details

```
GET /v1/orgs/{orgId}
Headers: Authorization: Bearer <accessToken>
Permission Required: org:view
```

**Response (200):**
```json
{
  "success": true,
  "data": {
    "orgId": "ece62ec5-dc2d-4577-b55e-c42608df004e",
    "name": "Smile Dental Care",
    "brandColor": "#dc2626",
    "address": "45, Indiranagar 100ft Road",
    "city": "Bangalore",
    "state": "Karnataka",
    "pin": "560038",
    "smsLanguage": "en",
    "clinicalDataVisibility": "all_members",
    "workingHours": { "..." },
    "createdAt": "2026-02-25T08:22:55Z",
    "nextWorkingDay": "2026-02-26",
    "isOpenToday": true
  }
}
```

---

## 8. Update Organization

```
PUT /v1/orgs/{orgId}
Headers: Authorization: Bearer <accessToken>
Permission Required: org:update
```

**Request (partial update — only send fields to change):**
```json
{
  "name": "Smile Dental & Orthodontics",
  "brandColor": "#2563eb"
}
```

---

## Complete Onboarding Flow (Frontend)

```
┌──────────────────────────────────────────────────────────────────┐
│                     STEP 1: AUTHENTICATION                       │
│                                                                  │
│  Phone Input → POST /auth/otp/send                              │
│       ↓                                                          │
│  OTP Input  → POST /auth/otp/verify                             │
│       ↓                                                          │
│  Store accessToken + refreshToken                                │
│       ↓                                                          │
│  Check: orgId == null?                                           │
│    YES → Go to Step 2                                            │
│    NO  → Check isProfileComplete                                 │
│           false → Go to Step 4                                   │
│           true  → Go to Dashboard                                │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│                 STEP 2: CREATE ORGANIZATION                      │
│                                                                  │
│  Org Setup Form → POST /v1/orgs                                 │
│       ↓                                                          │
│  ⚠️  REPLACE tokens with new ones from response                 │
│       ↓                                                          │
│  User is now ADMIN with orgId                                    │
│       ↓                                                          │
│  Ask: "Do you also practice as a doctor?"                        │
│    YES → Go to Step 3                                            │
│    NO  → Go to Step 5                                            │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│                 STEP 3: ADD DOCTOR ROLE                           │
│                                                                  │
│  PUT /v1/orgs/{orgId}/members/{userId}                          │
│  Body: { "roles": ["admin", "doctor"] }                          │
│       ↓                                                          │
│  Go to Step 4                                                    │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│                 STEP 4: COMPLETE DOCTOR PROFILE                  │
│  (Only if user has "doctor" role AND isProfileComplete == false)  │
│                                                                  │
│  Profile Form → PUT /v1/orgs/{orgId}/members/{userId}/profile   │
│  Body: {                                                         │
│    "profileData": {                                              │
│      "specialization": "...",     ← REQUIRED                    │
│      "registrationNumber": "...", ← REQUIRED                    │
│      "consultationFee": 500,      ← optional                    │
│      "qualification": "...",      ← optional                    │
│      "experience": 8              ← optional                    │
│    },                                                            │
│    "profileSchemaVersion": 1                                     │
│  }                                                               │
│       ↓                                                          │
│  isProfileComplete → true                                        │
│  Go to Step 5                                                    │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│                 STEP 5: ADD TEAM (OPTIONAL)                      │
│                                                                  │
│  "Add your team members"                                         │
│       ↓                                                          │
│  For each member:                                                │
│    POST /v1/orgs/{orgId}/members                                │
│    Body: { "phone": "...", "name": "...", "roles": ["..."] }    │
│       ↓                                                          │
│  "Skip" or "Done" → Go to Dashboard                             │
└──────────────────────────────────────────────────────────────────┘
```

---

## Error Response Format

All errors follow this structure:

```json
{
  "success": false,
  "error": {
    "code": "ERROR_CODE",
    "message": "Human-readable message",
    "details": { },
    "retryable": false
  }
}
```

### Common Error Codes

| Code | HTTP Status | Meaning |
|------|-------------|---------|
| `VALIDATION_ERROR` | 400 | Invalid request body (check `details` for field-level errors) |
| `INSUFFICIENT_PERMISSION` | 403 | User lacks required permission |
| `RESOURCE_NOT_FOUND` | 404 | Entity not found |
| `MEMBER_EXISTS` | 400 | User is already a member of this org |
| `CONFLICT` | 409 | Duplicate operation |

### Token Expiry Handling

| HTTP Status | Meaning | Action |
|-------------|---------|--------|
| 401 | Access token expired/invalid | Call `POST /v1/auth/token/refresh` with refresh token |
| 401 after refresh | Refresh token also expired | Redirect to login screen |

---

## Permission Reference

### Admin (24 permissions — full access)
`org:view`, `org:update`, `members:view`, `members:add`, `members:update`, `members:remove`, `members:assign_roles`, `queue:view`, `queue:manage`, `queue:call_next`, `patient:view`, `patient:view_clinical`, `patient:create`, `patient:update`, `visit:create`, `visit:update`, `visit:view`, `billing:view`, `billing:create`, `billing:mark_paid`, `billing:manage_templates`, `analytics:view`, `sms:view_templates`, `sms:manual_send`

### Doctor (14 permissions)
`org:view`, `members:view`, `queue:view`, `queue:manage`, `queue:call_next`, `patient:view`, `patient:view_clinical`, `patient:create`, `patient:update`, `visit:create`, `visit:update`, `visit:view`, `billing:view`, `analytics:view`

### Assistant (12 permissions)
`org:view`, `members:view`, `queue:view`, `queue:manage`, `patient:view`, `patient:create`, `patient:update`, `billing:view`, `billing:create`, `billing:mark_paid`, `sms:view_templates`, `sms:manual_send`

> Use `permissions` array from login response to show/hide UI elements. Never hardcode role-based visibility — always check the actual permission strings.
