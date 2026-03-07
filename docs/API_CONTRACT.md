# ClinicOS — Backend API Contracts & Frontend Data Flow

> Complete specification for every API endpoint, response shape, real-time update strategy, sync protocol, permission enforcement, and how the frontend consumes each response.
> Companion to: Product Document v2.1 · Queue Management Spec v1.0 · Operations Conflict Reference v1.0
> **Version 3.2** · February 2026
>
> **Changes from v2.0:** Multi-role model (roles as array, union permissions), admin role separated from doctor (no queue management), profile-as-databag (profileData + profileSchemaVersion), creator always enrolled as admin (doctor role added separately), flat entries array (no pre-grouped response), SMS decoupled from queue entries (separate endpoint), stashed entries unified into QueueEntryFull (no separate type), updated three-role permission matrix, frontend routing based on roles + profile completeness.
>
> **Changes in v3.1:** Session-based queue model (date-decoupled), offline End Queue, `stashedFromQueueId` replaces date-based stash references. See §9.1.
>
> **Changes in v3.2:** Assistant→Doctor assignment (`assignedDoctorId` + `assignedDoctorName` on AuthUser §6.2 and OrgMember §8.1), `assignedDoctorId` on AddMember/UpdateMember requests (§8.3, §8.5), `user: AuthUser` added to RefreshTokenResponse (§6.3) for propagating assignment/role changes.

---

## Table of Contents

1. [API Design Principles](#1-api-design-principles)
2. [Base Configuration](#2-base-configuration)
3. [Standard Response Envelope](#3-standard-response-envelope)
4. [Permission Enforcement Model](#4-permission-enforcement-model)
5. [Real-Time Update Strategy](#5-real-time-update-strategy)
6. [Authentication & Device Registration](#6-authentication--device-registration)
7. [Organization](#7-organization)
8. [Members, Roles & Profile Databag](#8-members-roles--profile-databag)
9. [Queue Operations](#9-queue-operations)
10. [Patient & Clinical](#10-patient--clinical)
11. [Billing](#11-billing)
12. [Sync Protocol (Offline-First Core)](#12-sync-protocol-offline-first-core)
13. [SMS Notifications](#13-sms-notifications)
14. [Analytics](#14-analytics)
15. [Frontend Data Flow Per Screen](#15-frontend-data-flow-per-screen)
16. [Error Code Catalog](#16-error-code-catalog)
17. [Rate Limits & Quotas](#17-rate-limits--quotas)
18. [Changelog from v2.0](#18-changelog-from-v20)

---

## 1. API Design Principles

| Principle                        | Rule                                                                                                                                                                                                              |
| -------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Offline-first, API-second**    | Every queue operation works locally via SQLite + event log. API calls are sync mechanisms, not action triggers. The frontend never blocks on an API call for core operations.                                     |
| **Event-sourced truth**          | The sync protocol (Section 12) is the authoritative data path. Direct REST endpoints exist for initial data loading, search, and analytics — NOT for queue mutations during normal operation.                     |
| **Cursor pagination everywhere** | No offset/page-number pagination. Every list endpoint uses cursor-based pagination for stable results under concurrent mutations.                                                                                 |
| **Dual timestamps**              | Every event gets `deviceTimestamp` (when it happened) + `serverReceivedAt` (when server got it). Pull filters by `serverReceivedAt` (nothing missed). Event ordering uses `deviceTimestamp` (correct chronology). |
| **Role validation on server**    | Every sync event and every API call is validated against the caller's roles and permissions. The frontend suggests, the backend decides.                                                                          |
| **Idempotent by event ID**       | Server deduplicates by `eventId` (UUID), not by timestamp window. Same event pushed twice = second is silently accepted with no side effects.                                                                     |
| **Minimal responses**            | Server returns only what the frontend cannot derive locally. No ETAs in responses (frontend computes). No side effects (frontend reducer handles). No redundant data.                                             |
| **Clinical data redaction**      | Clinical content (visit `data` field, images) is redacted server-side for users without `patient:view_full` permission. Controlled by org-level setting.                                                          |
| **Databag model**                | Clinical data (visits) and member profiles use opaque JSON blobs with `schemaVersion`. Backend stores without interpreting. Frontend defines structure and evolves freely without backend migrations.             |

---

## 2. Base Configuration

```
Base URL:     https://api.clinicos.in/v1
Content-Type: application/json
Auth:         Bearer <JWT> in Authorization header
Device ID:    X-Device-Id header on every request
App Version:  X-App-Version header on every request
```

---

## 3. Standard Response Envelope

```typescript
// All successful responses
interface ApiResponse<T> {
  success: true;
  data: T;
  meta?: {
    pagination?: CursorPagination;
    serverTimestamp?: number;
  };
}

interface CursorPagination {
  hasMore: boolean;
  nextCursor: string | null;
  totalEstimate?: number;
}

// All error responses
interface ApiError {
  success: false;
  error: {
    code: string; // machine-readable: "INSUFFICIENT_PERMISSION"
    message: string; // human-readable
    details?: Record<string, any>;
    retryable: boolean;
    retryAfterMs?: number;
  };
}
```

### HTTP Status Codes

| Status | When                                                   |
| ------ | ------------------------------------------------------ |
| `200`  | Success (GET, PUT, DELETE)                             |
| `201`  | Created (POST that creates a resource)                 |
| `400`  | Validation error (bad input, invalid state transition) |
| `401`  | JWT expired or invalid — trigger silent refresh        |
| `403`  | Valid JWT but insufficient permissions                 |
| `404`  | Resource not found                                     |
| `409`  | Conflict (entity already in target state)              |
| `413`  | Payload too large (sync push batch too big)            |
| `429`  | Rate limited — check `retryAfterMs`                    |
| `500`  | Server error — `retryable: true`                       |

### Frontend `ApiClient` Behavior

```typescript
class ApiClient {
  async request<T>(method, url, body?): Promise<T> {
    // 1. Attach headers: Authorization, X-Device-Id, X-App-Version
    // 2. Make request
    // 3. On 401: silent token refresh → retry once → if still 401, emit AUTH_EXPIRED
    // 4. On 403: surface to UI — "You don't have permission for this action"
    // 5. On 413: chunk payload and retry (sync push only)
    // 6. On 429: wait retryAfterMs, then retry
    // 7. On 5xx: exponential backoff (1s, 2s, 4s, 8s, max 30s), max 3 retries
    // 8. On success: unwrap envelope, return data
    // 9. On network error: throw NetworkError (caller handles offline)
  }
}
```

---

## 4. Permission Enforcement Model

### Three-Layer Architecture

```
┌─────────────────────────────────────────────────────────┐
│  Layer 3: Frontend UI (convenience — NOT security)       │
│  Hides tabs, buttons, screens user can't use.            │
│  Derived from server-provided permissions.                │
│  If removed entirely, system is still secure.             │
├─────────────────────────────────────────────────────────┤
│  Layer 2: Backend Middleware (mandatory)                  │
│  Every request: extract JWT → lookup member → resolve     │
│  ALL roles → compute union permissions → attach to        │
│  request context. Route-level permission checks via       │
│  requirePermission() middleware.                          │
├─────────────────────────────────────────────────────────┤
│  Layer 1: Sync Event Validation (mandatory)              │
│  Every pushed event: verify user's resolved permissions   │
│  against ROLE_PERMISSIONS matrix. Cross-check JWT's       │
│  actual roles from database. Reject unauthorized.         │
└─────────────────────────────────────────────────────────┘
```

### Backend Middleware

```typescript
// Runs on every authenticated request
async function authMiddleware(req, res, next) {
  const token = req.headers.authorization?.replace("Bearer ", "");
  const decoded = verifyJWT(token); // throws 401

  const member = await getMemberByUserId(decoded.userId, req.params.orgId);
  if (!member || !member.isActive) throw new ForbiddenError();

  // Resolve permissions as UNION of all assigned roles
  const permissions = resolvePermissions(member.roles);

  req.auth = {
    userId: decoded.userId,
    orgId: req.params.orgId,
    roles: member.roles, // ['admin', 'doctor'] or ['assistant']
    permissions: new Set(permissions),
  };
  next();
}

// Compute union of permissions across all roles
function resolvePermissions(roles: UserRole[]): Permission[] {
  const permSet = new Set<Permission>();
  for (const role of roles) {
    for (const perm of DEFAULT_PERMISSIONS[role]) {
      permSet.add(perm);
    }
  }
  return Array.from(permSet);
}

// Route-level permission check
function requirePermission(...perms: Permission[]) {
  return (req, res, next) => {
    for (const perm of perms) {
      if (!req.auth.permissions.has(perm)) {
        return res.status(403).json({
          success: false,
          error: {
            code: "INSUFFICIENT_PERMISSION",
            message: `Required permission: ${perm}`,
            retryable: false,
          },
        });
      }
    }
    next();
  };
}
```

### Role Model: Three Distinct Domains

Each role maps to a domain of the clinic. A user can have multiple roles. Permissions are the union.

| Role          | Domain                    | What They Do                                                                      | What They Don't Do                                           |
| ------------- | ------------------------- | --------------------------------------------------------------------------------- | ------------------------------------------------------------ |
| **admin**     | Organizational management | Manage team, edit settings, view analytics, export data, SMS templates            | Queue operations, clinical notes                             |
| **doctor**    | Clinical work             | View queue (read-only), mark complete, full patient view, add notes/prescriptions | Queue management (add/remove/pause), team management         |
| **assistant** | Clinic floor operations   | All queue operations, billing, limited patient view                               | Clinical notes, analytics, team management, settings editing |

**Why admin has no queue ops:** The admin runs the business. They don't manage the queue floor. Even Dr. Rajesh (admin + doctor) doesn't add patients to his own queue — Ravi (assistant) does that. This mirrors how clinics actually work.

### Permission Catalog

```typescript
type UserRole = "admin" | "doctor" | "assistant";

type Permission =
  // Queue
  | "queue:view"
  | "queue:add_patient"
  | "queue:remove_patient"
  | "queue:call_now"
  | "queue:step_out"
  | "queue:mark_complete"
  | "queue:pause"
  | "queue:resume"
  | "queue:end"
  | "queue:import_stash"
  // Patient
  | "patient:view" // see patient list + visit dates/complaints
  | "patient:view_full" // see clinical data inside visits
  | "patient:add_notes" // create/edit visits
  // Billing
  | "billing:view"
  | "billing:create"
  | "billing:mark_paid"
  // Analytics
  | "analytics:view"
  // SMS
  | "sms:view_templates"
  | "sms:edit_templates"
  | "sms:manual_send"
  // Settings
  | "settings:view"
  | "settings:edit_org"
  | "settings:manage_team"
  | "settings:export_data";
```

### Default Permissions by Role

```typescript
const DEFAULT_PERMISSIONS: Record<UserRole, Permission[]> = {
  admin: [
    // NO queue operations — admin doesn't manage clinic floor
    "patient:view", // can see patient list (for admin context)
    "billing:view", // can see revenue summaries
    "analytics:view",
    "sms:view_templates",
    "sms:edit_templates",
    "settings:view",
    "settings:edit_org",
    "settings:manage_team",
    "settings:export_data",
  ],
  doctor: [
    "queue:view",
    "queue:mark_complete", // view queue + complete own patients
    "patient:view",
    "patient:view_full",
    "patient:add_notes",
    "billing:view",
    // NO analytics, settings, team management — that's admin domain
  ],
  assistant: [
    "queue:view",
    "queue:add_patient",
    "queue:remove_patient",
    "queue:call_now",
    "queue:step_out",
    "queue:mark_complete",
    "queue:pause",
    "queue:resume",
    "queue:end",
    "queue:import_stash",
    "patient:view", // can see list + visit dates, NOT clinical data
    "billing:view",
    "billing:create",
    "billing:mark_paid",
    "sms:manual_send",
    "settings:view",
  ],
};
```

**Dr. Rajesh (roles: `['admin', 'doctor']`) gets the union:**

Everything admin has + everything doctor has = manage team, view analytics, edit settings, export data, AND view full patient data, add notes, mark complete, view queue. But he still can't add patients to queue or pause the queue — that's assistant-only.

### Permission Matrix (Complete)

```
Permission                  admin    doctor    assistant
─────────────────────────────────────────────────────────
queue:view                    -        ✓          ✓
queue:add_patient             -        -          ✓
queue:remove_patient          -        -          ✓
queue:call_now                -        -          ✓
queue:step_out                -        -          ✓
queue:mark_complete           -        ✓          ✓
queue:pause                   -        -          ✓
queue:resume                  -        -          ✓
queue:end                     -        -          ✓
queue:import_stash            -        -          ✓
patient:view                  ✓        ✓          ✓
patient:view_full             -        ✓          -
patient:add_notes             -        ✓          -
billing:view                  ✓        ✓          ✓
billing:create                -        -          ✓
billing:mark_paid             -        -          ✓
analytics:view                ✓        -          -
sms:view_templates            ✓        -          -
sms:edit_templates            ✓        -          -
sms:manual_send               -        -          ✓
settings:view                 ✓        -          ✓
settings:edit_org             ✓        -          -
settings:manage_team          ✓        -          -
settings:export_data          ✓        -          -
```

### Clinical Data Redaction

Controlled by org-level setting. Server-side — the frontend never receives data it shouldn't see.

```typescript
interface OrgSettings {
  clinicalDataVisibility: "all_members" | "clinical_roles_only";
  // 'all_members'          → pilot mode, everyone sees everything
  // 'clinical_roles_only'  → production mode, redact for non-clinical roles
}

// Applied server-side on every visit response
function filterVisitForUser(
  visit: Visit,
  auth: AuthContext,
  orgSettings: OrgSettings,
): Visit {
  // Pilot mode — return everything
  if (orgSettings.clinicalDataVisibility === "all_members") return visit;

  // Production mode — check permission
  if (auth.permissions.has("patient:view_full")) return visit;

  // Redact clinical content
  return {
    ...visit,
    data: null, // clinical blob hidden
    images: [], // clinical images hidden
  };
}
```

### Tab Visibility (Frontend — Derived from Server Permissions)

Permissions come FROM the server in the login response. Frontend reads them to build the tab bar. If someone tampers with local permissions, API still rejects unauthorized calls.

```typescript
const TAB_PERMISSION_MAP: Record<string, Permission> = {
  HomeTab: "analytics:view", // admin + admin/doctor combo
  QueueTab: "queue:view", // doctor + assistant
  PatientsTab: "patient:view", // all roles
  BillingTab: "billing:create", // assistant only (admin sees billing in analytics)
  AnalyticsTab: "analytics:view", // admin only
  SettingsTab: "settings:view", // admin + assistant
};

function getVisibleTabs(permissions: Permission[]): string[] {
  const permSet = new Set(permissions);
  return Object.entries(TAB_PERMISSION_MAP)
    .filter(([_, perm]) => permSet.has(perm))
    .map(([tab]) => tab);
}

// Admin only:           Home, Patients, Analytics, Settings
// Doctor only:          Queue, Patients
// Assistant only:       Queue, Patients, Billing, Settings
// Admin + Doctor:       Home, Queue, Patients, Analytics, Settings
```

---

## 5. Real-Time Update Strategy

### MVP: Smart Polling + Push-on-Action (P0)

**Push-on-action:** User performs action → reducer updates UI immediately (optimistic) → event logged locally → event pushed to server NOW (don't wait for timer).

**Pull-on-timer:** Adaptive timer fetches events from the other device.

```typescript
class SyncScheduler {
  private baseIntervalMs = 15_000; // 15s when active
  private idleIntervalMs = 60_000; // 60s when no changes for 5min
  private maxIntervalMs = 300_000; // 5min max backoff on errors
  private currentIntervalMs = 15_000;
  private lastChangeAt = Date.now();
  private consecutiveErrors = 0;

  onSyncSuccess(hadNewEvents: boolean) {
    this.consecutiveErrors = 0;
    if (hadNewEvents) {
      this.lastChangeAt = Date.now();
      this.currentIntervalMs = this.baseIntervalMs;
    } else if (Date.now() - this.lastChangeAt > 300_000) {
      this.currentIntervalMs = this.idleIntervalMs;
    }
  }

  onSyncError() {
    this.consecutiveErrors++;
    this.currentIntervalMs = Math.min(
      this.baseIntervalMs * Math.pow(2, this.consecutiveErrors),
      this.maxIntervalMs,
    );
  }

  onUserAction() {
    this.triggerImmediateSync(); // push now, pull in 2s
  }
}
```

**Additional triggers:**

- App foreground → immediate pull.
- Connectivity restored → immediate push + pull.

### P1: Server-Sent Events (SSE)

SSE replaces the pull timer. Push stays as HTTP POST.

```
GET /v1/sse?deviceId=<id>&orgId=<orgId>&since=<serverTimestamp>
Accept: text/event-stream

event: sync
data: {"events": [...], "serverTimestamp": 1707500000000}

event: heartbeat
data: {"serverTimestamp": 1707500060000}
```

### What Auto-Updates and What Doesn't

| Data Type                               | Update Mechanism                               | Latency   |
| --------------------------------------- | ---------------------------------------------- | --------- |
| Queue state (entries, serving, waiting) | Sync events (push/pull)                        | <5s       |
| Patient thread (clinical notes)         | Sync events                                    | <30s      |
| Billing status                          | Sync events                                    | <15s      |
| ETAs                                    | Client-side computation after any queue change | Immediate |
| SMS delivery status                     | Separate sync event (`sms_status_updated`)     | <60s      |
| Analytics dashboard                     | Fresh API call on screen focus                 | On-demand |
| Patient search results                  | Fresh API call on search                       | On-demand |

---

## 6. Authentication & Device Registration

### 6.1 Send OTP

```
POST /v1/auth/otp/send
```

**No auth required.**

```typescript
interface SendOTPRequest {
  phone: string; // 10-digit Indian mobile
  countryCode: "+91";
}

// Response 201
interface SendOTPResponse {
  requestId: string;
  expiresInSeconds: number; // default: 300
  retryAfterSeconds: number; // default: 30
}
```

### 6.2 Verify OTP

```
POST /v1/auth/otp/verify
```

**No auth required.**

```typescript
interface VerifyOTPRequest {
  requestId: string;
  otp: string; // 4-digit
  deviceId: string; // generated on first launch, persisted forever
  deviceInfo: {
    platform: "android" | "ios";
    osVersion: string;
    appVersion: string;
    deviceModel: string;
  };
}

// Response 200
interface VerifyOTPResponse {
  accessToken: string; // JWT, 24h expiry
  refreshToken: string; // 30-day expiry, single-use
  expiresAt: number; // Unix ms
  user: AuthUser;
  isNewUser: boolean; // true → redirect to org setup
}

interface AuthUser {
  userId: string;
  phone: string;
  name: string | null; // null for new users
  orgId: string | null; // null for new users
  roles: UserRole[]; // [] for new users, ['admin', 'doctor'] or ['assistant'] etc.
  permissions: Permission[]; // union of all role permissions — SOURCE OF TRUTH for frontend
  isProfileComplete: boolean; // false if roles includes 'doctor' but profile not filled
  assignedDoctorId: string | null; // which doctor this assistant manages (null for non-assistants or unassigned)
  assignedDoctorName: string | null; // denormalized — needed at boot before members list loads (see §8.9)
}
```

> **Added in v3.2 — Assistant→Doctor Assignment:** `assignedDoctorId` and `assignedDoctorName` enable single-writer queue management. One assistant manages one doctor's queue — prevents multi-device conflicts. The admin assigns via ManageTeam (§8.5). `assignedDoctorName` is denormalized because the boot sequence (SQLite cache → queue store) needs the doctor name before the members list is fetched. Same denormalization pattern as `patientName` on `QueueEntryFull`. Server populates via JOIN on the member table. Both fields are `null` for doctors, admins, new users, and unassigned assistants.

**Frontend routing based on AuthUser:**

```typescript
function getInitialRoute(user: AuthUser): string {
  // No org yet → create one
  if (!user.orgId) return "CreateOrg";

  // Has org but no roles → invited but not yet configured (edge case)
  if (user.roles.length === 0) return "PendingInvite";

  // Doctor role but profile incomplete → must fill profile before using app
  if (user.roles.includes("doctor") && !user.isProfileComplete)
    return "CompleteDoctorProfile";

  // Ready → main app (tabs filtered by permissions)
  return "MainApp";
}
```

**Critical behaviors:**

- Store tokens in secure storage (Keychain/Keystore).
- Store `user` in local DB for offline access.
- **Permissions in `user.permissions`** → used by frontend to derive visible tabs, buttons, screens. These come FROM the server.
- **Re-auth preserves local data.** If tokens expire after 30 days offline, the user re-authenticates. Login flow writes new tokens but does NOT clear local SQLite. All unsynced events, queue state, patient threads survive. On next sync, events push with new token.

### 6.3 Refresh Token

```
POST /v1/auth/token/refresh
```

**No auth required (uses refresh token).**

```typescript
interface RefreshTokenRequest {
  refreshToken: string;
  deviceId: string;
}

// Response 200
interface RefreshTokenResponse {
  accessToken: string;
  refreshToken: string; // rotated — new single-use token
  expiresAt: number;
  user: AuthUser; // ADDED v3.2 — latest user data (roles, permissions, assignment)
}
```

> **Added in v3.2 — `user` in refresh response:** Without this, the client has no mechanism to learn about server-side changes to the user's data (role changes, assignment changes, permission changes) without logging out and back in. Adding `AuthUser` to the refresh response piggybacks updated user data on an existing call (runs every 24h max). The client updates both in-memory store (`authStore.setUser`) and SQLite cache (`database.setCachedAuthUser`) with the returned user. This is how assistant→doctor assignment changes propagate: admin reassigns in ManageTeam → server updates member record → assistant's next token refresh (within 24h) returns updated `assignedDoctorId` + `assignedDoctorName` → queue screen shows correct doctor's queue on next mount.

### 6.4 Register Device

```
POST /v1/devices/register
```

**Requires auth.** Called after successful authentication.

```typescript
interface RegisterDeviceRequest {
  deviceId: string;
  pushToken?: string; // FCM (future)
}

// Response 200
interface RegisterDeviceResponse {
  registered: true;
}
```

**No `primaryRole` field.** All authorization is based on the user's `roles` from the member record, not the device. Sync events flow to all devices in the org (filtered only by `deviceId` to exclude the originator). The frontend derives UI capabilities from `permissions`, not device tags. If v2 multi-doctor needs device-to-doctor affinity (this tablet shows Dr. Kumar's queue), add a `doctorId` preference — not a role tag.

---

## 7. Organization

### 7.1 Create Organization

```
POST /v1/orgs
```

**Requires auth. No org context yet (user is new).**

**The creator is always enrolled as admin. No doctor profile here — that's a separate step. This supports both doctor-creates-org and manager-creates-org flows.**

```typescript
interface CreateOrgRequest {
  // Pure org data
  name: string;
  address: string;
  city: string;
  state: string;
  pin: string;
  logo?: string; // base64 (max 500KB)
  brandColor?: string; // hex, default: "#059669"
  smsLanguage?: string; // ISO 639-1 code, default: 'hi'
  clinicalDataVisibility?: "all_members" | "clinical_roles_only"; // default: 'all_members'
  workingHours: WorkingHours;

  // Creator info — always becomes admin
  creator: {
    name: string;
    // No role field. Always admin.
    // No doctor profile. Filled in next step if they practice medicine.
  };
}

interface WorkingHours {
  monday: DaySchedule | null; // null = closed
  tuesday: DaySchedule | null;
  wednesday: DaySchedule | null;
  thursday: DaySchedule | null;
  friday: DaySchedule | null;
  saturday: DaySchedule | null;
  sunday: DaySchedule | null;
}

interface DaySchedule {
  shifts: Shift[]; // supports split shifts (morning + evening)
}

interface Shift {
  open: string; // "09:00" (24h format)
  close: string; // "13:00"
}
```

**Response (201):**

```typescript
interface CreateOrgResponse {
  org: Organization;
  member: OrgMember; // the creator, enrolled as admin
  accessToken: string; // refreshed JWT now includes orgId + roles
  refreshToken: string;
}

interface Organization {
  orgId: string;
  name: string;
  logo: string | null;
  brandColor: string;
  address: string;
  city: string;
  state: string;
  pin: string;
  smsLanguage: string;
  clinicalDataVisibility: "all_members" | "clinical_roles_only";
  workingHours: WorkingHours;
  createdAt: string;

  // Computed
  nextWorkingDay: string; // ISO date — informational only (no longer used by stash system in v3.1)
  isOpenToday: boolean;
  currentShift: Shift | null;
}
```

**What happens after org creation — the "Do you practice?" flow:**

```
POST /orgs → org created, creator enrolled as admin (roles: ['admin'])

Frontend immediately asks: "Do you also practice at this clinic?"

→ Yes:
  PUT /orgs/:orgId/members/:userId  (body: { roles: ['admin', 'doctor'] })
  → App shows doctor profile form
  PUT /orgs/:orgId/members/:userId/profile  (body: { profileData: {...}, profileSchemaVersion: 1 })
  → Done. Creator is admin + doctor with complete profile.

→ No:
  Skip. Creator stays as admin only. Go to "Add team" screen.
```

### 7.2 Get Organization

```
GET /v1/orgs/:orgId
```

**Requires:** `settings:view`

**Response (200):** Same `Organization` interface.

### 7.3 Update Organization

```
PUT /v1/orgs/:orgId
```

**Requires:** `settings:edit_org`

**Request:** Partial fields — only include what's changing.

**Response (200):** Updated `Organization`.

---

## 8. Members, Roles & Profile Databag

### Design Philosophy

**Roles are a list, not a single value.** A person can be `['admin', 'doctor']`. Their effective permissions are the union of both roles.

**Profile is a databag.** The backend stores `profileData` as an opaque JSON blob with `profileSchemaVersion`. The frontend defines the structure based on the member's roles. This lets us add specialty-specific fields (dentist tooth chart, ophthalmologist vision test) without backend migrations.

**Creator is always admin first.** Doctor role is added as a second step if they practice medicine. This supports both doctor-owned clinics and manager-owned clinics with the same flow.

### 8.1 The OrgMember Model

```typescript
interface OrgMember {
  userId: string;
  phone: string;
  name: string;
  roles: UserRole[]; // ['admin', 'doctor'] or ['assistant'] etc.
  permissions: Permission[]; // union of all role defaults
  isActive: boolean;

  // Profile databag — opaque to backend, structured by frontend
  profileData: Record<string, any> | null; // null if profile not yet filled
  profileSchemaVersion: number | null; // null if no profile data

  // Derived flags (computed by server)
  isProfileComplete: boolean; // true if all required fields present for roles

  // Assistant→Doctor assignment (v3.2)
  assignedDoctorId: string | null; // which doctor this assistant manages (null for non-assistants)
  assignedDoctorName: string | null; // denormalized — avoids O(n) member lookup per assistant card in ManageTeam

  lastActiveAt: string | null;
  joinedAt: string;
}
```

### 8.2 Profile Databag — What It Contains

The backend stores `profileData` as-is. The frontend defines the structure based on roles.

**If roles includes `'doctor'`:**

```typescript
// profileSchemaVersion: 1
interface DoctorProfileV1 {
  specialization: string; // "General Physician", "Pediatrician"
  registrationNumber: string; // "MCI-12345-BH"
  experience: number; // years
  consultationFee: number; // INR
  about?: string; // free text bio
  qualifications?: string[]; // ["MBBS", "MD"]
  languages?: string[]; // ["Hindi", "English"]
}
```

**If roles is only `['assistant']`:**

```typescript
// profileSchemaVersion: 1
interface AssistantProfileV1 {
  // Minimal for MVP. Future: shift preferences, skills
}
```

**If roles is only `['admin']` (non-doctor manager):**

```typescript
// profileSchemaVersion: 1
interface AdminProfileV1 {
  designation?: string; // "Clinic Manager", "Owner"
}
```

**If roles is `['admin', 'doctor']`:**

Same as `DoctorProfileV1`. The doctor fields are what matter — admin role doesn't need its own profile fields. Frontend checks: `if (roles.includes('doctor')) → show doctor profile form`.

**Profile completeness logic (frontend):**

```typescript
function getRequiredProfileFields(roles: UserRole[]): string[] {
  if (roles.includes("doctor")) {
    return [
      "specialization",
      "registrationNumber",
      "experience",
      "consultationFee",
    ];
  }
  // Admin-only and assistant — no required profile fields
  return [];
}

function isProfileComplete(member: OrgMember): boolean {
  const required = getRequiredProfileFields(member.roles);
  if (required.length === 0) return true;
  if (!member.profileData) return false;
  return required.every((field) => member.profileData![field] != null);
}
```

**Server-side indexing:** If `roles` includes `doctor` and `profileData` contains `specialization`, the server extracts it to an indexed column for search/filtering (patient app needs to filter by specialization). Everything else stays in the blob.

**Why databag:** A dentist might need `clinicSetup: 'single_chair' | 'multi_chair'`. A pediatrician might need `ageGroupFocus`. An ophthalmologist might need `equipmentAvailable: ['slit_lamp', 'fundus_camera']`. With databag + schemaVersion, the frontend evolves freely.

### 8.3 Add Member (Invite)

```
POST /v1/orgs/:orgId/members
```

**Requires:** `settings:manage_team`

```typescript
interface AddMemberRequest {
  phone: string;
  name: string;
  roles: UserRole[]; // ['doctor'] or ['assistant'] or ['admin']
  assignedDoctorId?: string; // v3.2 — optional, only for assistant role. Server validates target is a doctor in same org.
  // No profile data here. The added person fills their own profile on first login.
  // Clean separation: admin assigns roles, people fill their own professional details.
}

// Response 201
interface AddMemberResponse {
  member: OrgMember;
  inviteSent: boolean; // SMS invite sent
}
```

**Server behavior:**

1. Creates user record if phone number is new.
2. Creates org membership with specified roles.
3. Computes permissions as union of role defaults.
4. Sets `profileData: null`, `isProfileComplete: false` (if doctor role).
5. Sends SMS invite: "You've been added to [Clinic Name] on ClinicOS. Download the app: [link]"

**What happens when the added person logs in:**

```
Person downloads app → OTP → verified → server detects: existing member of an org

if roles.includes('doctor') && !isProfileComplete:
  → "Complete your profile" screen
  → PUT /members/:userId/profile
  → Done. App loads.

if roles is ['assistant']:
  → Straight to queue screen. No profile needed.

if roles is ['admin'] without doctor:
  → Straight to admin dashboard.
```

### 8.4 List Members

```
GET /v1/orgs/:orgId/members
```

**Requires:** `settings:manage_team`

**Response (200):**

```typescript
interface ListMembersResponse {
  members: OrgMember[];
  // No pagination — clinics have 2-5 members. Even hospitals rarely exceed 50-60.
}
```

### 8.5 Update Member (Roles)

```
PUT /v1/orgs/:orgId/members/:userId
```

**Requires:** `settings:manage_team` (or `userId === self` for name changes only)

```typescript
interface UpdateMemberRequest {
  name?: string;
  roles?: UserRole[]; // change roles — permissions auto-recompute
  isActive?: boolean; // deactivate without deleting
  assignedDoctorId?: string | null; // v3.2 — omit = no change, string = set, null = clear assignment
}
```

**Server behavior on role change:**

1. Update `roles` field.
2. Recompute `permissions` as union of new role defaults.
3. If `doctor` added to roles and no `profileData` → set `isProfileComplete: false`.
4. If `doctor` removed from roles → `isProfileComplete` recalculates (may become true).
5. Return updated `OrgMember`.

**Server behavior on `assignedDoctorId` change (v3.2):**

1. Validate the target `assignedDoctorId` references a member with `doctor` role in the same org.
2. Validate the member being updated has `assistant` role. Reject if not.
3. Update `assignedDoctorId` and denormalize `assignedDoctorName` from the doctor's member record.
4. Return updated `OrgMember` (with both `assignedDoctorId` and `assignedDoctorName`).
5. If `assignedDoctorId` is `null`, clear both fields (unassign the assistant).

**Stale permissions on the affected user's device:** When admin changes a member's roles, the affected member's local `AuthUser.permissions` remain stale until their next token refresh or app restart. The server middleware always validates against current DB roles, so API calls and sync events are correctly gated — but the frontend UI may temporarily show buttons for permissions the user no longer has (tapping them returns 403), or hide buttons for permissions they just gained. For MVP this is acceptable: role changes happen during initial setup, not mid-day operations. The dev team should be aware that **role changes take effect server-side immediately, but client-side on next app restart or token refresh.**

**Stale assignment on the assistant's device (v3.2):** Same staleness model applies to `assignedDoctorId` changes. When admin reassigns an assistant to a different doctor, the assistant's cached `AuthUser.assignedDoctorId` remains stale until their next token refresh (§6.3 now returns `user: AuthUser`). Events for the old doctor stay in `event_log` and sync correctly (isolated by `queueId`). On next boot after refresh, the queue screen reads the updated `assignedDoctorId` and loads the correct doctor's queue.

**Response (200):** Updated `OrgMember`.

### 8.6 Update Profile (Databag)

```
PUT /v1/orgs/:orgId/members/:userId/profile
```

**Requires:** `settings:manage_team` OR `userId === self` (you can always edit your own profile)

```typescript
interface UpdateProfileRequest {
  profileData: Record<string, any>; // the databag — backend stores as-is
  profileSchemaVersion: number;
}

// Response 200: Updated OrgMember (with isProfileComplete recalculated)
```

**Server-side on profile update:**

1. Store `profileData` and `profileSchemaVersion` as-is.
2. If `roles` includes `doctor` and `profileData` contains `specialization` → extract to indexed column.
3. Recompute `isProfileComplete` based on required fields for the member's roles.
4. Return updated `OrgMember`.

### 8.7 Get Doctors (for queue/patient context)

```
GET /v1/orgs/:orgId/doctors
```

**Requires:** `queue:view` or `patient:view`

Returns all members with `doctor` role and their profile data. Used for queue doctor selector (multi-doctor, P2) and patient-facing doctor list.

```typescript
// Response 200
interface DoctorsListResponse {
  doctors: {
    userId: string;
    name: string;
    profileData: Record<string, any> | null;
    profileSchemaVersion: number | null;
    isProfileComplete: boolean;
    isActive: boolean;
    // Extracted for convenience — also in profileData
    specialization: string | null;
    consultationFee: number | null;
  }[];
}
```

### 8.8 Complete Onboarding Flows

**Flow A: Doctor creates org (most common for Munger pilot)**

```
Step 1: Dr. Rajesh downloads app → OTP → verified → no org
        AuthUser: { orgId: null, roles: [], isProfileComplete: false }
        Frontend route: CreateOrg

Step 2: Create org
        POST /orgs → org created, Rajesh enrolled as admin
        AuthUser: { orgId: 'xxx', roles: ['admin'], isProfileComplete: true }
        Frontend: "Do you also practice at this clinic?"

Step 3: "Yes, I practice"
        PUT /members/:self → roles: ['admin', 'doctor']
        AuthUser: { roles: ['admin', 'doctor'], isProfileComplete: false }
        Frontend route: CompleteDoctorProfile

Step 4: Fill doctor profile
        PUT /members/:self/profile → { specialization, regNumber, fee... }
        AuthUser: { roles: ['admin', 'doctor'], isProfileComplete: true }
        Frontend route: AddTeam

Step 5: Add team
        POST /members → Ravi, roles: ['assistant']
        POST /members → Dr. Anita, roles: ['doctor']
        Frontend route: MainApp

Step 6: Team members log in
        Ravi: assistant → straight to queue screen
        Dr. Anita: doctor, profile incomplete → "Complete your profile" → fills → ready
```

**Flow B: Manager creates org**

```
Step 1: Manager downloads → OTP → verified → no org

Step 2: Create org → enrolled as admin (roles: ['admin'])
        "Do you also practice?" → No → skip profile

Step 3: Add team
        POST /members → Dr. Rajesh, roles: ['doctor']
        POST /members → Dr. Anita, roles: ['doctor']
        POST /members → Ravi, roles: ['assistant']

Step 4: Each person logs in
        Doctors: profile incomplete → fill profile → ready
        Ravi: assistant → straight to queue
```

**Flow C: Promote existing member**

```
Ravi has been handling billing so well that Dr. Rajesh makes him admin too:
  PUT /members/:ravi → roles: ['assistant', 'admin']
  Ravi now sees: Queue + Patients + Billing + Analytics + Settings
  Union of assistant + admin permissions.
```

---

## 9. Queue Operations

### Design Decision: Dual Path

Queue mutations happen through TWO paths:

1. **Sync events (primary):** All queue actions logged locally, pushed via `/sync/push`. This is how the system normally operates.
2. **Direct REST (secondary):** For initial data loading and online-only convenience. The frontend NEVER waits for REST to update the UI.

**Queue actions that exist ONLY as sync events (no REST endpoint):**

- Pause / Resume (frontend computes all derived state)
- Call Now, Step Out, Mark Complete, Remove (all handled by reducer + sync)

**REST endpoints exist only for:**

- Loading today's full queue (app boot)
- Patient lookup during registration
- End queue / stash (needs server to compute next working day)
- Import stash

### 9.1 Get Active Queue

> **CHANGED in v3.1:** Endpoint renamed from `/queues/today` to `/queues/active`. Queue identity is now session-based, not date-based. See "Why Session-Based" below.

```
GET /v1/orgs/:orgId/queues/active?doctorId=<id>
```

**Requires:** `queue:view`

Called on app open. Returns the doctor's current active/paused queue (if any) and stashed entries from the most recently ended queue.

**Response (200):**

```typescript
interface GetActiveQueueResponse {
  queue: QueueSnapshot | null; // null if no active queue
  previousQueueStash: QueueEntryFull[]; // stashed entries from last ended queue
}

interface QueueSnapshot {
  queueId: string;
  orgId: string;
  doctorId: string;
  status: "active" | "paused" | "ended";
  lastToken: number;
  pauseStartTime: number | null;
  totalPausedMs: number;
  createdAt: string;
  endedAt: string | null; // ISO timestamp — set when queue_ended event applied

  // Flat list of ALL entries — frontend groups by state
  entries: QueueEntryFull[];

  // Sync checkpoint — subsequent pulls use this as `since`
  lastEventTimestamp: number;
}

interface QueueEntryFull {
  entryId: string;
  queueId: string;
  patientId: string;
  tokenNumber: number;
  state: "waiting" | "now_serving" | "completed" | "removed" | "stashed";
  position: number | null; // current position in waiting pool (null if not waiting)
  complaintTags: string[]; // structured tags for analytics
  complaintText: string | null; // free text for clinical context
  isBilled: boolean;
  billAmount: number | null;
  billId: string | null;
  registeredAt: number;
  servedAt: number | null;
  completedAt: number | null;
  stashedFromQueueId: string | null; // set when entry was stashed — references the queue it came from

  // Denormalized patient info (avoids separate lookup)
  patientName: string;
  patientPhone: string;
  patientAge: number | null;
  patientGender: "M" | "F" | null;
  isReturningPatient: boolean;
  totalPreviousVisits: number;
}

// ── Why Session-Based (changed from v3.0) ──
//
// PREVIOUS (v3.0): Queue identity = doctor + date. stashedToDate/stashedFromDate
//   referenced calendar dates. End Queue required server to compute next working
//   day from WorkingHours + holidays. This BROKE offline-first: if assistant is
//   offline when ending queue, the call fails and remaining patients can't be stashed.
//
// CURRENT (v3.1): Queue identity = session. A queue lives until explicitly ended.
//   Stash references sourceQueueId (the previous queue), not a target date.
//   End Queue is a pure sync event — no server computation needed. Fully offline.
//
// WHAT CHANGED:
//   - QueueSnapshot: removed `date` field, added `endedAt`, status includes 'ended'
//   - QueueEntryFull: removed `stashedToDate` + `stashedFromDate`, added `stashedFromQueueId`
//   - Boot endpoint: /queues/today → /queues/active (lookup by status, not date)
//   - Stash lookup: "entries WHERE stashedToDate = today" → "entries on doctor's
//     most recently ended queue WHERE state = 'stashed' AND hasStashedEntries = true"
//   - End Queue: server no longer computes stashTargetDate (field removed)
```

**Key change from v2:** Flat `entries` array instead of pre-grouped `nowServing`/`waiting`/`completed`/`removed`. Frontend derives the grouping:

```typescript
// Frontend on boot — one pass through entries
function deriveQueueView(entries: QueueEntryFull[]) {
  const nowServing = entries.find((e) => e.state === "now_serving") || null;
  const waiting = entries
    .filter((e) => e.state === "waiting")
    .sort((a, b) => (a.position ?? 0) - (b.position ?? 0));
  const completed = entries
    .filter((e) => e.state === "completed")
    .sort((a, b) => (b.completedAt ?? 0) - (a.completedAt ?? 0));
  const removed = entries.filter((e) => e.state === "removed");

  return { nowServing, waiting, completed, removed };
}
```

**Why flat:** Server stores data. Frontend derives views. Clean separation. No redundant `nowServing` field that's just `entries.find(e => e.state === 'now_serving')`.

**SMS status is NOT on QueueEntryFull.** It's a separate concern — see Section 13.5.

**Stashed entries:** Entries with `state: 'stashed'` use the same `QueueEntryFull` type. No separate `StashedEntry` interface needed. The `stashedFromQueueId` field provides stash context — it references which queue the entry was stashed from. Server finds stash by looking at the doctor's most recently ended queue with stashed entries.

**No pagination needed:** This returns one day's data. Patna worst case: 80 entries. That's a small payload (~50KB). The complete picture on boot is worth the single request.

### 9.2 Lookup Patient by Phone

```
GET /v1/orgs/:orgId/patients/lookup?phone=<10digits>
```

**Requires:** `queue:add_patient`

Called during registration as the assistant types. Returns existing patient info for auto-fill.

**Response (200):**

```typescript
interface PatientLookupResponse {
  found: boolean;
  patient: PatientSummary | null;
}

interface PatientSummary {
  patientId: string;
  phone: string;
  name: string;
  age: number | null;
  gender: "M" | "F" | null;
  totalVisits: number;
  lastVisitDate: string | null;
  lastComplaintTags: string[];
  isRegular: boolean; // >3 visits
}
```

**Frontend behavior:**

- On 10-digit entry → call (debounced 300ms).
- If `found` → auto-fill, show "Returning patient" badge.
- **Offline:** Query local SQLite `patient_master` table.

### 9.3 End Queue / Stash

> **CHANGED in v3.1:** End Queue is now a pure sync event (see §12 `queue_ended`). The REST endpoint exists only as a fallback acknowledgment — the client does NOT depend on it. All state transitions happen locally via the reducer. The server no longer computes `stashTargetDate`.

```
POST /v1/orgs/:orgId/queues/:queueId/end
```

**Requires:** `queue:end`

```typescript
interface EndQueueRequest {
  stashRemaining: boolean;
}

// Response 200
interface EndQueueResponse {
  stashedCount: number;
}
```

**Why no `stashTargetDate`:** In v3.0, the server computed the next working day from WorkingHours + holidays. This broke offline-first — if the assistant was offline when ending the queue, patients couldn't be stashed. In v3.1, stash entries simply live on the ended queue. The next queue discovers them on boot via `previousQueueStash` in the active queue response. No date computation needed.

### 9.4 Import Stashed Patients

> **CHANGED in v3.1:** `sourceDate` replaced with `sourceQueueId`. Stash is referenced by queue ID, not calendar date.

```
POST /v1/orgs/:orgId/queues/:queueId/import-stash
```

**Requires:** `queue:import_stash`

```typescript
interface ImportStashRequest {
  sourceQueueId: string; // queueId of the ended queue with stashed entries
  entryIds?: string[]; // specific entries (null = all)
}

// Response 200
interface ImportStashResponse {
  importedCount: number;
  importedEntryIds: string[];
}
```

**Token handling:** Frontend computes next token as `max(allTokensInQueue) + 1`. If queue already has tokens 1-3 from new patients and imports stash with tokens 38-52, next new patient gets token 53. All computed client-side — server doesn't return `newTokenStart`.

**Stash patients keep original tokens:** Patient received SMS saying "Token #38" yesterday. Renumbering would confuse them.

### 9.5 Predefined Complaint Tags

```
GET /v1/orgs/:orgId/complaint-tags
```

**Requires:** `queue:view`

Returns the list of quick-select complaint tags used during registration.

```typescript
// Response 200
interface ComplaintTagsResponse {
  tags: ComplaintTag[];
}

interface ComplaintTag {
  tagId: string;
  labelHi: string; // "बुखार"
  labelEn: string; // "Fever"
  key: string; // "fever" — used in data storage
  sortOrder: number;
  isCommon: boolean; // shown prominently in quick-select
}
```

**Default tags (seeded per org):** Fever, Cold/Cough, Body Pain, Headache, Stomach Pain, BP Check, Diabetes Follow-up, Skin Issue, Eye Problem, Ear/Throat, Injury, General Checkup, Other.

**Why structured tags:** Analytics requires queryable data. "Fever", "bukhar", "Fever and cold" as free text can't be aggregated. Tags are structured; free text is for clinical context.

### 9.6 Add Patient — Complete Flow Walkthrough

Adding a patient to the queue is the most frequent operation in the system (25–80 times per doctor per day). There is **no dedicated REST endpoint** for it. The mutation flows through the sync protocol — specifically, the `patient_added` event pushed via `POST /v1/sync/push`. The only REST call in the entire flow is the phone lookup for auto-fill.

This walkthrough traces the full lifecycle from the patient walking into the clinic to their token appearing on both devices.

**Setting:** Ravi (assistant) is at the registration desk. A patient walks in and says their phone number.

#### Step 1: Phone Number Entry + Lookup

Ravi types the phone number. On 10-digit completion (debounced 300ms), the frontend calls:

```
GET /v1/orgs/:orgId/patients/lookup?phone=9876543210
```

This is the **only network call** in the entire add-patient flow. If the device is offline, it queries local SQLite instead — the `patient_master` table synced from previous sessions.

- **Returning patient** → `found: true` with `PatientSummary` (name, age, gender, last complaint, total visits). Frontend auto-fills the form. Ravi sees "Returning patient detected — auto-filling details."
- **New patient** → `found: false`. Ravi enters name manually. Age, gender, complaint are all optional — phone number is the only required field.

#### Step 2: Token Assignment (Client-Side)

The frontend computes the next token locally:

```typescript
const nextToken = queueState.lastToken + 1;
// e.g., last token was 23 → new patient gets Token #24
```

No server call needed. `lastToken` is tracked in local queue state, updated every time a patient is added. If stash patients were imported with tokens 38–52, `lastToken` would be 52, and the next new patient gets 53.

#### Step 3: Local State Update (Immediate — Sub-Second)

The frontend creates a `patient_added` event and applies it to the local reducer **immediately** — before any network call. This is why registration completes in under 10 seconds. The UI updates instantly.

```typescript
const event: SyncEvent = {
  eventId: uuid(), // generated client-side
  deviceId: this.deviceId,
  userId: ravi.userId,
  userRoles: ["assistant"],

  eventType: "patient_added",
  targetEntity: newQueueEntryId, // also generated client-side
  targetTable: "queue_entry",

  payload: {
    tokenNumber: 24,
    patientId: existingPatientId || newPatientId,
    phone: "9876543210",
    name: "Meena Devi",
    age: 32,
    gender: "F",
    complaintTags: ["fever", "body_pain"],
    complaintText: "Fever, cough since 3 days",
    isReturning: true,
    queueId: todayQueueId,
  },

  deviceTimestamp: Date.now(),
  serverReceivedAt: null, // not synced yet
  synced: false,
  schemaVersion: 1,
};
```

The reducer processes this immediately:

```typescript
case 'patient_added': {
  const entry = eventToQueueEntry(event);
  newState.entries[entry.entryId] = entry;
  newState.waitingOrder.push(entry.entryId);
  newState.lastToken = Math.max(newState.lastToken, entry.tokenNumber);
  // If this is the first patient of the day, queue becomes active
  if (newState.queueStatus === 'idle') newState.queueStatus = 'active';
  break;
}
```

At this point, Token #24 is visible in the queue on Ravi's screen. The patient is in `waiting` state. If nobody is in `now_serving` and the queue is active, auto-promotion kicks in and Token #24 goes straight to `now_serving`.

#### Step 4: Event Persisted to Local SQLite

The event is written to the local `event_log` table in SQLite with `synced: false`. This is the crash-recovery guarantee — even if the app dies right now, the event survives and will be pushed when the app restarts.

#### Step 5: Sync Push (Background, Non-Blocking)

The `SyncScheduler` detects an unsynced event and triggers an immediate push:

```
POST /v1/sync/push
Body: { deviceId: "...", events: [the patient_added event] }
```

**Server-side processing:**

1. **Dedup** — is this `eventId` already in the event store? If yes, return `DUPLICATE_IGNORED`.
2. **Role check** — Ravi's JWT → lookup member → roles include `assistant` → `patient_added` is allowed for assistant. ✓
3. **Schema check** — `schemaVersion: 1` is compatible. ✓
4. **State guard** — `patient_added` has no guard (it creates new records, never modifies existing). Always applies. ✓
5. **Apply** — server creates the `queue_entry` row + creates/updates `patient_master` if new patient.
6. **Set** `serverReceivedAt = NOW()`.
7. **Store** event in `event_store`.
8. **Side effect: SMS** — server sends the registration SMS to 9876543210.

**Server response:**

```json
{
  "accepted": [{ "eventId": "...", "serverReceivedAt": 1707500000000 }],
  "rejected": [],
  "serverTimestamp": 1707500000000
}
```

Frontend marks the event as `synced: true` in local SQLite.

#### Step 6: Registration SMS Sent (Server-Side)

The server triggers the SMS via the gateway (MSG91 primary, Textlocal failover):

```
ClinicOS: You are Token #24 at Kumar Health Clinic.
5 patients ahead. Expected time: ~60 min.
```

When the gateway reports delivery status, the server creates an `sms_status_updated` sync event that the frontend picks up on the next pull — the SMS delivery indicator appears next to Token #24 in the queue view.

#### Step 7: Doctor's Device Gets Updated

On the doctor's device, the next sync pull (every 15s, or instantly via SSE in P1) fetches the `patient_added` event. The doctor's reducer processes it identically — Token #24 appears in their read-only queue view.

If the doctor is offline, this happens whenever connectivity returns. But the doctor doesn't need the queue view to function — the physical clinic is the sync layer. Ravi sends the patient in, the doctor sees them walk in.

#### First Patient of the Day (Queue Auto-Activation)

When the queue has no patients yet (`queueStatus === 'idle'`), the `patient_added` event does two things: creates the queue entry AND transitions the queue from `idle` to `active`. There is no "Start Queue" button. Adding the first patient IS starting the queue.

#### Fully Offline Scenario

If Ravi's device has no connectivity when adding the patient:

- Steps 1–4 happen identically (phone lookup hits local SQLite instead of API).
- Step 5 is deferred — event sits in local SQLite with `synced: false`.
- Step 6 doesn't happen yet — SMS is delayed until the event reaches the server.
- Step 7 is also delayed.

The orange pulsing sync indicator appears. When connectivity returns, all queued events push at once, SMS sends, and the doctor's device catches up. The patient has been in the queue on Ravi's screen the entire time.

#### Visual Summary

```
Patient walks in → Ravi enters phone
                        │
                        ▼
              ┌─────────────────┐
              │ Phone lookup     │ ← only network call (or local SQLite if offline)
              │ GET /lookup      │
              └────────┬────────┘
                       │ auto-fill if returning
                       ▼
              ┌─────────────────┐
              │ Ravi taps "Add"  │
              └────────┬────────┘
                       │
           ┌───────────┼───────────┐
           ▼           ▼           ▼
     Local reducer  SQLite log  UI updates
     (immediate)    (crash-safe) (Token #24 visible)
                       │
                       ▼
              ┌─────────────────┐
              │ Sync push        │ ← background, non-blocking
              │ POST /sync/push  │
              └────────┬────────┘
                       │
              ┌────────┼────────┐
              ▼                 ▼
         Server stores    SMS triggered
         + validates      to patient
              │                 │
              ▼                 ▼
         Doctor's device   Patient receives
         gets update       Token #24 SMS
         (next sync pull)
```

The key insight: the patient is in the queue the moment Ravi taps "Add." Everything after that — sync, SMS, doctor's device update — is important but never blocking.

---

## 10. Patient & Clinical

### 10.1 Patient List (Cursor Paginated)

```
GET /v1/orgs/:orgId/patients?after=<cursor>&limit=20&search=<query>&sort=<field>
```

**Requires:** `patient:view`

| Param    | Type   | Default           | Description                                                  |
| -------- | ------ | ----------------- | ------------------------------------------------------------ |
| `after`  | string | (none)            | Cursor from previous response                                |
| `limit`  | number | 20                | Max 50                                                       |
| `search` | string | (none)            | Name or phone, min 2 chars                                   |
| `sort`   | string | `last_visit_desc` | `last_visit_desc`, `name_asc`, `created_desc`, `visits_desc` |

**Response (200):**

```typescript
interface PatientListResponse {
  patients: PatientListItem[];
  meta: {
    pagination: CursorPagination;
    serverTimestamp: number;
  };
}

interface PatientListItem {
  patientId: string;
  phone: string;
  name: string;
  age: number | null;
  gender: "M" | "F" | null;
  totalVisits: number;
  lastVisitDate: string | null;
  lastComplaintTags: string[];
  isRegular: boolean;
  createdAt: string;
}
```

**Offline:** Queries local SQLite with same cursor/search logic. Search uses FTS5 index.

### 10.2 Patient Thread (Visit History)

```
GET /v1/orgs/:orgId/patients/:patientId/thread?after=<cursor>&limit=10
```

**Requires:** `patient:view`

**Response (200):**

```typescript
interface PatientThreadResponse {
  patient: PatientSummary;
  visits: Visit[];
  meta: {
    pagination: CursorPagination;
  };
}

interface PatientSummary {
  patientId: string;
  name: string;
  phone: string | null;
  age: number | null;
  gender: string | null;
  totalVisits: number;
  isRegular: boolean;
  createdAt: string;
}
```

### 10.3 The Visit Model (Databag)

The visit stores clinical content as a flexible JSON blob. The backend persists and returns it without interpreting the contents. This allows the frontend to evolve clinical schemas (new specializations, new data types) without backend schema migrations.

```typescript
interface Visit {
  visitId: string;
  patientId: string;
  queueEntryId: string | null; // null for visits outside queue context
  date: string; // ISO date
  complaintTags: string[]; // extracted for analytics (indexed server-side)

  // The databag — opaque to backend, structured by frontend
  data: Record<string, any> | null; // null when REDACTED for insufficient permissions
  schemaVersion: number; // frontend uses this to know how to parse `data`

  // Attachments
  images: ImageRef[]; // empty array when redacted

  // Metadata
  createdBy: { userId: string; name: string; role: UserRole };
  createdAt: string;
  updatedAt: string;
}
```

**What `data` contains (defined by frontend, opaque to backend):**

```typescript
// schemaVersion: 1
interface VisitDataV1 {
  complaint?: string; // "Fever 3 days, body ache"
  vitals?: {
    bp?: string; // "130/85"
    pulse?: number;
    temperature?: number; // Fahrenheit
    weight?: number; // kg
    spo2?: number; // percentage
  };
  diagnosis?: string;
  prescriptions?: {
    medicineName: string;
    dosage: string;
    frequency: string; // "BD", "TDS", "OD", "SOS"
    timing: string; // "after food", "before food"
    duration: string; // "5 days", "1 week"
    notes?: string;
  }[];
  notes?: string;
}
```

**Redaction behavior (server-side, based on org setting + user permission):**

| `clinicalDataVisibility` | User has `patient:view_full` | Result                     |
| ------------------------ | ---------------------------- | -------------------------- |
| `all_members`            | Yes or No                    | Full visit returned        |
| `clinical_roles_only`    | Yes (doctor)                 | Full visit returned        |
| `clinical_roles_only`    | No (assistant, admin)        | `data: null`, `images: []` |

**When redacted, the assistant/admin sees:**

```json
{
  "visitId": "...",
  "date": "2026-02-04",
  "complaintTags": ["fever"],
  "data": null,
  "schemaVersion": 1,
  "images": [],
  "createdBy": { "name": "Dr. Kumar", "role": "doctor" },
  "createdAt": "2026-02-04T10:34:00Z"
}
```

They see THAT a visit happened, for what complaint, by whom, and when. They do NOT see what the doctor wrote.

### 10.4 Image References

```typescript
interface ImageRef {
  imageId: string;
  thumbnailUrl: string; // low-res, loads immediately in list
  fullUrl: string; // high-res, loads on tap
  caption?: string;
  uploadedAt: string;
}
```

Images are uploaded separately (to S3/cloud storage). The visit `data` blob or `images` array stores references only. Image upload endpoint is P2 — the schema slot exists now to avoid migration later.

### 10.5 Create Visit

```
POST /v1/orgs/:orgId/patients/:patientId/visits
```

**Requires:** `patient:add_notes`

```typescript
interface CreateVisitRequest {
  queueEntryId?: string;
  complaintTags?: string[];
  data: Record<string, any>; // the databag — backend stores as-is
  schemaVersion: number;
  images?: ImageRef[];
}

// Response 201
interface CreateVisitResponse {
  visit: Visit;
}
```

**Why is this simple:** The backend doesn't validate the contents of `data`. It stores the blob. The frontend structures it however it needs. Adding a dentist tooth chart or an ophthalmologist vision test just means a different `schemaVersion` with different `data` contents. Zero backend changes.

### 10.6 Update Visit

```
PUT /v1/orgs/:orgId/patients/:patientId/visits/:visitId
```

**Requires:** `patient:add_notes`

```typescript
interface UpdateVisitRequest {
  complaintTags?: string[];
  data?: Record<string, any>;
  schemaVersion?: number;
  images?: ImageRef[];
}

// Response 200: Updated Visit
```

### 10.7 Patient Search (Autocomplete)

```
GET /v1/orgs/:orgId/patients/search?q=<query>&limit=5
```

**Requires:** `patient:view`

Lightweight search for autocomplete. Minimal fields, fast response.

```typescript
// Response 200
interface PatientSearchResponse {
  results: {
    patientId: string;
    phone: string;
    name: string;
    age: number | null;
    gender: "M" | "F" | null;
    lastVisitDate: string | null;
  }[];
}
```

---

## 11. Billing

### 11.1 Create Bill

```
POST /v1/orgs/:orgId/bills
```

**Requires:** `billing:create`

```typescript
interface CreateBillRequest {
  patientId: string;
  queueEntryId: string;
  items: BillItemInput[];
  sendSMS: boolean;
}

interface BillItemInput {
  name: string;
  amount: number; // INR
}

// Response 201
interface CreateBillResponse {
  bill: Bill;
}

interface Bill {
  billId: string;
  orgId: string;
  patientId: string;
  queueEntryId: string;
  items: BillItem[];
  totalAmount: number; // server-computed sum
  isPaid: boolean;
  paidAt: string | null;
  createdAt: string;

  // Denormalized for display on bill/invoice
  patientName: string;
  patientPhone: string;
  tokenNumber: number;
  doctorName: string;
  clinicName: string;
}

interface BillItem {
  itemId: string;
  name: string;
  amount: number;
}
```

**Frontend must show a confirmation screen before creating the bill.** The assistant reviews patient name, all line items, and total amount. Only after explicit "Confirm" tap does the `bill_created` event generate. This is the validation gate — once confirmed, the bill is final and immutable. The patient is physically present during billing, making this the natural moment to catch mistakes.

**MVP: No bill editing or voiding.** Once a bill is created, it cannot be modified. If a mistake slips past confirmation, the assistant creates a corrected bill and the original is orphaned (both remain in records for audit). Bill void/edit is P1 — it introduces complexity around SMS (which version did the patient receive?), payment reconciliation (patient already paid the old amount), and event sourcing (edits to immutable events require compensation events).

### 11.2 Get Bill

```
GET /v1/orgs/:orgId/bills/:billId
```

**Requires:** `billing:view`

**Response (200):** `Bill` interface.

### 11.3 Mark Bill Paid

```
PUT /v1/orgs/:orgId/bills/:billId/mark-paid
```

**Requires:** `billing:mark_paid`

**Response (200):** Updated `Bill` with `isPaid: true`.

### 11.4 List Bills

```
GET /v1/orgs/:orgId/bills?date=<ISO>&status=<paid|unpaid|all>&after=<cursor>&limit=20
```

**Requires:** `billing:view`

```typescript
// Response 200
interface BillListResponse {
  bills: Bill[];
  summary: {
    totalBilled: number;
    totalPaid: number;
    totalUnpaid: number;
    billCount: number;
  };
  meta: {
    pagination: CursorPagination;
    serverTimestamp: number;
  };
}
```

### 11.5 Bill Templates (Saved Service Items)

```
GET /v1/orgs/:orgId/bill-templates
```

**Requires:** `billing:view`

```typescript
// Response 200
interface BillTemplatesResponse {
  templates: {
    templateId: string;
    name: string; // "Consultation Fee", "ECG"
    defaultAmount: number;
    isDefault: boolean; // auto-added to every new bill
    sortOrder: number;
  }[];
}
```

### 11.6 Create Bill Template

```
POST /v1/orgs/:orgId/bill-templates
```

**Requires:** `billing:create`

```typescript
interface CreateBillTemplateRequest {
  name: string;
  defaultAmount: number;
  isDefault: boolean;
}
```

---

## 12. Sync Protocol (Offline-First Core)

This is the most critical section. Every queue mutation, every clinical note, every billing action flows through this protocol.

### 12.1 Event Structure

```typescript
interface SyncEvent {
  // Identity
  eventId: string; // UUID — THE dedup key
  deviceId: string;
  userId: string;
  userRoles: UserRole[]; // actual roles from member record (not self-reported)

  // What happened
  eventType: SyncEventType;
  targetEntity: string; // UUID of affected entity
  targetTable: "queue" | "queue_entry" | "patient_thread" | "billing";
  payload: Record<string, any>;

  // Dual timestamps
  deviceTimestamp: number; // when action happened (device clock)
  serverReceivedAt: number | null; // when server got it (null until pushed)

  // Sync metadata
  synced: boolean;
  schemaVersion: number;
}
```

### 12.2 Event Types & Payloads

```typescript
type SyncEventType =
  | "patient_added"
  | "patient_removed"
  | "call_now"
  | "step_out"
  | "mark_complete"
  | "queue_paused"
  | "queue_resumed"
  | "queue_ended"
  | "stash_imported"
  | "visit_saved" // SINGLE event for all clinical data
  | "bill_created"
  | "bill_updated";
```

#### Event: `patient_added`

```typescript
// Allowed roles: assistant
// Target table: queue_entry + patient_master (upsert if new patient)
// State guard: none (always applies — creates new entry)
{
  eventType: 'patient_added',
  targetEntity: '<new_queue_entry_id>',
  targetTable: 'queue_entry',
  payload: {
    tokenNumber: 24,
    patientId: '<patient_uuid>',
    phone: '9876543210',
    name: 'Meena Devi',
    age: 32,
    gender: 'F',
    complaintTags: ['fever', 'body_pain'],
    complaintText: 'Fever, cough since 3 days',
    isReturning: true,
    queueId: '<queue_uuid>',
  }
}
```

**Server-side note:** This event creates a `queue_entry` row AND upserts a `patient_master` row (create if new phone number, update `lastVisitDate` if existing). The `targetTable` field says `queue_entry` because that's the primary entity — but the dev team must handle the patient upsert in the same transaction.

#### Event: `patient_removed`

```typescript
// Allowed roles: assistant
// Target table: queue_entry
// State guard: entry must be in 'waiting'
{
  eventType: 'patient_removed',
  targetEntity: '<queue_entry_id>',
  targetTable: 'queue_entry',
  payload: {
    reason: 'no_show' | 'cancelled' | 'left',
  }
}
```

#### Event: `call_now`

```typescript
// Allowed roles: assistant
// Target table: queue_entry
// State guard: entry must be in 'waiting'
{
  eventType: 'call_now',
  targetEntity: '<queue_entry_id>',
  targetTable: 'queue_entry',
  payload: {
    tokenNumber: 34,
    previousNowServing: '<queue_entry_id>' | null,
  }
}
```

#### Event: `step_out`

```typescript
// Allowed roles: assistant
// Target table: queue_entry
// State guard: entry must be in 'now_serving'
{
  eventType: 'step_out',
  targetEntity: '<queue_entry_id>',
  targetTable: 'queue_entry',
  payload: {
    reason: 'blood_test' | 'xray' | 'other',
  }
}
```

#### Event: `mark_complete`

```typescript
// Allowed roles: assistant, doctor (ONLY shared queue operation)
// Target table: queue_entry
// State guard: entry must be in 'now_serving'
// Idempotent: first by deviceTimestamp wins
{
  eventType: 'mark_complete',
  targetEntity: '<queue_entry_id>',
  targetTable: 'queue_entry',
  payload: {
    completedBy: '<user_id>',
    completedByRole: 'assistant' | 'doctor',
  }
}
```

#### Event: `queue_paused`

```typescript
// Allowed roles: assistant
// Target table: queue
// State guard: queue must be 'active'
{
  eventType: 'queue_paused',
  targetEntity: '<queue_id>',
  targetTable: 'queue',
  payload: {
    pauseStartTime: 1707500000000,
  }
}
```

#### Event: `queue_resumed`

```typescript
// Allowed roles: assistant
// Target table: queue
// State guard: queue must be 'paused'
{
  eventType: 'queue_resumed',
  targetEntity: '<queue_id>',
  targetTable: 'queue',
  payload: {
    pauseDurationMs: 1800000,
    totalPausedMs: 3600000,
  }
}
```

#### Event: `queue_ended`

> **CHANGED in v3.1:** `stashTargetDate` removed from payload. End Queue is now fully self-contained — no server-side date computation needed. Queue transitions to status `'ended'`, queueId is cleared on client. Stashed entries reference this queue via `stashedFromQueueId`.

```typescript
// Allowed roles: assistant
// Target table: queue + queue_entry (waiting → stashed)
// State guard: queue must be 'active' or 'paused'
{
  eventType: 'queue_ended',
  targetEntity: '<queue_id>',
  targetTable: 'queue',
  payload: {
    stashedCount: 15,
    stashedEntryIds: ['<entry_id_1>', '<entry_id_2>', ...],
  }
}
// Server-side effects:
//   1. Queue status → 'ended', endedAt → event.deviceTimestamp
//   2. now_serving entry → 'completed'
//   3. All waiting entries → 'stashed' with stashedFromQueueId = queue_id
//   4. Trigger stash SMS to all stashed patients (when event syncs)
```

#### Event: `stash_imported`

> **CHANGED in v3.1:** `sourceDate` removed from payload. `targetEntity` is the NEW queue's ID (generated by client). Server creates/activates this new queue and moves entries into it. Entry `queueId` fields are updated to the new queue.

```typescript
// Allowed roles: assistant
// Target table: queue_entry (stashed → waiting)
// State guard: entries must be in 'stashed' state on the source queue
{
  eventType: 'stash_imported',
  targetEntity: '<new_queue_id>',     // the NEW queue session ID (generated by client)
  targetTable: 'queue_entry',
  payload: {
    importedCount: 15,
    importedEntryIds: ['<entry_id_1>', '<entry_id_2>', ...],
  }
}
// Server-side effects:
//   1. Create new queue record with targetEntity as queueId (if not exists)
//   2. Copy referenced entries into new queue with state → 'waiting'
//   3. Update entry.queueId to new queue ID
//   4. entry.stashedFromQueueId preserved (references source queue)
//   5. New queue status → 'active'
```

#### Event: `visit_saved`

```typescript
// Allowed roles: doctor
// Target table: patient_thread
// State guard: none (creates new entry or updates existing)
{
  eventType: 'visit_saved',
  targetEntity: '<patient_id>',
  targetTable: 'patient_thread',
  payload: {
    visitId: '<visit_uuid>',
    queueEntryId: '<queue_entry_id>' | null,
    complaintTags: ['fever', 'body_pain'],
    data: { /* clinical databag */ },
    schemaVersion: 1,
    images: [],
  }
}
```

**Single event replaces `notes_added` + `prescription_added`.** The doctor saves a visit — that's one action, one event. The `data` blob contains everything.

#### Event: `bill_created`

```typescript
// Allowed roles: assistant, doctor
// Target table: billing
// State guard: none (creates new record)
{
  eventType: 'bill_created',
  targetEntity: '<bill_id>',
  targetTable: 'billing',
  payload: {
    patientId: '<patient_id>',
    queueEntryId: '<queue_entry_id>',
    items: [
      { name: 'Consultation Fee', amount: 300 },
      { name: 'ECG', amount: 200 },
    ],
    totalAmount: 500,
    sendSMS: true,
  }
}
```

#### Event: `bill_updated`

```typescript
// Allowed roles: assistant, doctor
// Target table: billing
// State guard: bill must exist
{
  eventType: 'bill_updated',
  targetEntity: '<bill_id>',
  targetTable: 'billing',
  payload: {
    isPaid: true,
    paidAt: 1707500000000,
  }
}
```

### 12.3 Role Validation Matrix (Server-Side — Mandatory)

```typescript
// Maps event types to which roles can generate them
// Server checks caller's ACTUAL roles (from DB via JWT), not self-reported
const EVENT_ALLOWED_ROLES: Record<SyncEventType, UserRole[]> = {
  patient_added: ["assistant"],
  patient_removed: ["assistant"],
  call_now: ["assistant"],
  step_out: ["assistant"],
  mark_complete: ["assistant", "doctor"], // ONLY shared queue operation
  queue_paused: ["assistant"],
  queue_resumed: ["assistant"],
  queue_ended: ["assistant"],
  stash_imported: ["assistant"],
  visit_saved: ["doctor"], // clinical data — doctor only
  bill_created: ["assistant", "doctor"],
  bill_updated: ["assistant", "doctor"],
};

// Validation: check if ANY of user's roles is in allowed list
function isEventAllowed(
  userRoles: UserRole[],
  eventType: SyncEventType,
): boolean {
  const allowed = EVENT_ALLOWED_ROLES[eventType];
  return userRoles.some((role) => allowed.includes(role));
}
```

The server cross-checks the JWT's actual roles from the database member record. It does NOT trust the event's `userRoles` field — that's informational only.

**Admin role note:** Admin does not appear in this matrix at all. Admin cannot generate queue or clinical events. If someone with `['admin']` only tries to push a `patient_added` event, the server rejects it with `UNAUTHORIZED_ROLE`. If someone with `['admin', 'doctor']` pushes a `visit_saved` event, it's allowed because they have the `doctor` role.

### 12.4 Push Events to Server

```
POST /v1/sync/push
```

**Requires auth.** No specific permission — auth middleware validates role per event.

```typescript
interface SyncPushRequest {
  deviceId: string;
  events: SyncEvent[]; // max 50 per batch
}

// Response 200
interface SyncPushResponse {
  accepted: AcceptedEvent[];
  rejected: RejectedEvent[];
  serverTimestamp: number;
}

interface AcceptedEvent {
  eventId: string;
  serverReceivedAt: number;
}

interface RejectedEvent {
  eventId: string;
  reason: string;
  code: SyncRejectionCode;
}

type SyncRejectionCode =
  | "INVALID_STATE" // state guard failed
  | "UNAUTHORIZED_ROLE" // caller's roles cannot perform this event type
  | "ENTITY_NOT_FOUND" // target doesn't exist
  | "SCHEMA_MISMATCH" // payload schema version incompatible
  | "DUPLICATE_IGNORED"; // eventId already processed — not an error
```

**Server-side processing per event:**

```
1. DEDUP by eventId → if exists, return DUPLICATE_IGNORED (idempotent, no side effects)
2. ROLE CHECK → verify JWT's actual roles against EVENT_ALLOWED_ROLES[eventType]
   → if no matching role → reject UNAUTHORIZED_ROLE
3. SCHEMA CHECK → verify schemaVersion compatibility
   → if mismatch → reject SCHEMA_MISMATCH
4. STATE GUARD → check entity's current state against required state
   → if failed → reject INVALID_STATE
5. APPLY → update relevant table
6. SET serverReceivedAt = NOW()
7. STORE in event_store
8. TRIGGER SIDE EFFECTS:
   - patient_added → send registration SMS
   - mark_complete → send "turn now" SMS to next waiting patient
   - queue_ended → send stash SMS to all stashed patients
   - bill_created (sendSMS: true) → send bill SMS
   - queue_paused → check all waiting patients' ETA shift; if >45 min, send delay SMS
```

**Chunked pushing:**

```typescript
async pushEvents(events: SyncEvent[]) {
  const BATCH_SIZE = 50;
  for (let i = 0; i < events.length; i += BATCH_SIZE) {
    const batch = events.slice(i, i + BATCH_SIZE);
    const result = await ApiClient.post('/sync/push', {
      deviceId: this.deviceId,
      events: batch,
    });
    await EventLog.markSynced(result.accepted.map(e => e.eventId));
    result.rejected.forEach(r => {
      if (r.code !== 'DUPLICATE_IGNORED') {
        console.warn(`Sync rejected: ${r.eventId} — ${r.code}: ${r.reason}`);
      }
    });
  }
}
```

### 12.5 Pull Events from Server

```
GET /v1/sync/pull?since=<serverTimestamp>&deviceId=<id>&limit=100
```

**Requires auth.**

| Param      | Description                                                                        |
| ---------- | ---------------------------------------------------------------------------------- |
| `since`    | `serverReceivedAt` from last pull. Returns events with `serverReceivedAt > since`. |
| `deviceId` | Exclude events from this device (already applied locally).                         |
| `limit`    | Max events to return (default: 100, max: 500).                                     |

```typescript
// Response 200
interface SyncPullResponse {
  events: SyncEvent[]; // ordered by serverReceivedAt ASC
  serverTimestamp: number; // latest serverReceivedAt in batch
  hasMore: boolean; // paginate if true
}
```

**Dual timestamp usage:**

- **Filtering:** `serverReceivedAt` → nothing missed, even with clock drift.
- **Ordering for replay:** `deviceTimestamp` → correct chronological order.
- **Display:** `deviceTimestamp` → shows when action actually happened.

**Clinical data in pulled events:** If the pulling device's user lacks `patient:view_full` and org setting is `clinical_roles_only`, the server REDACTS `visit_saved` events:

```typescript
// What assistant's device receives for a visit_saved event:
{
  eventType: 'visit_saved',
  payload: {
    visitId: '...',
    queueEntryId: '...',
    complaintTags: ['fever'],
    data: null,                    // REDACTED
    schemaVersion: 1,
    images: [],                    // REDACTED
  }
}
```

The assistant's local DB stores the redacted version. If the org setting later changes to `all_members`, the next full sync refreshes the data.

**Frontend pull handler:**

```typescript
async pullEvents() {
  let since = await StorageService.get('lastSyncPull') || 0;
  let hasMore = true;

  while (hasMore) {
    const result = await ApiClient.get('/sync/pull', { since, deviceId: this.deviceId, limit: 100 });

    if (result.events.length > 0) {
      const sorted = result.events.sort((a, b) => a.deviceTimestamp - b.deviceTimestamp);
      EventBus.emit('SYNC_EVENTS_RECEIVED', { events: sorted });
      this.showSyncToasts(sorted);
    }

    // ATOMIC: checkpoint in same SQLite transaction as event persistence
    await StorageService.set('lastSyncPull', result.serverTimestamp);
    hasMore = result.hasMore;
    since = result.serverTimestamp;
  }
}
```

### 12.6 Applying Pulled Events to Local State

> **⚠️ The reducer specification lives in the Frontend Architecture document, not here.**
>
> This API contract defines what the server sends and receives. How the frontend processes those events internally (data structures, state shape, promotion logic, position management) is a frontend concern specified in `ClinicOS_Frontend_Architecture.md`, Section 6.4.
>
> Previously, this section contained a complete `SYNC_RECEIVED` reducer implementation using a hashmap-based state shape. That created two competing reducer specifications with different data structures and different bugs. The Frontend Architecture document is now the **single authoritative source** for all queue state transition logic.
>
> **What the server guarantees:** Events arrive in `serverReceivedAt` order. Each event has a state guard (e.g., `mark_complete` only applies if the entry is `now_serving`). Events that fail their guard are safe to ignore — the state is already correct. Duplicate events (same `eventId`) should be skipped.

---

## 13. SMS Notifications

### 13.1 Get Templates

```
GET /v1/orgs/:orgId/sms/templates
```

**Requires:** `sms:view_templates`

```typescript
// Response 200
interface SMSTemplatesResponse {
  templates: SMSTemplate[];
}

interface SMSTemplate {
  templateId: string;
  trigger: SMSTrigger;
  templates: Record<string, string>; // language code → template string
  // e.g., { "hi": "ClinicOS: आपका टोकन {{token}}...", "en": "ClinicOS: You are Token {{token}}..." }
  variables: string[]; // available variables: ["token", "clinic", "wait_time", ...]
  isActive: boolean;
  dltTemplateIds: Record<string, string>; // language code → DLT registration ID
  maxLength: number; // keep under 160 for single segment
}

type SMSTrigger =
  | "registration"
  | "turn_near"
  | "turn_now"
  | "bill_generated"
  | "stashed"
  | "pause_delay";
```

**Language extensibility:** `Record<string, string>` keyed by ISO 639-1 codes. Add Bengali by adding a `"bn"` key. No schema migration.

### 13.2 Update Template

```
PUT /v1/orgs/:orgId/sms/templates/:templateId
```

**Requires:** `sms:edit_templates`

### 13.3 SMS Delivery Tracking (Server-Generated Sync Event)

SMS delivery status flows as a server-generated sync event — not from any device.

```typescript
// Event pushed to devices via sync pull
{
  eventType: 'sms_status_updated',
  targetEntity: '<queue_entry_id>',
  targetTable: 'queue_entry',
  payload: {
    smsType: 'registration' | 'turn_near' | 'turn_now' | 'bill' | 'stash',
    status: 'queued' | 'sent' | 'delivered' | 'failed' | 'dnd_blocked',
    failureReason: string | null,
  }
}
```

### 13.4 SMS Gateway (Server-Side Only)

The frontend never calls the SMS gateway. Server handles:

1. Primary gateway (MSG91) → retry once on failure.
2. Failover to secondary (Textlocal).
3. Third failure → `failed` status, dead-letter queue.
4. Dead-letter visible via admin analytics.

### 13.5 SMS Status — Separate Endpoint (Decoupled from Queue)

SMS status is a separate concern from queue state. The assistant might want to resend a failed SMS or send a custom message. These are communication actions that don't touch queue state.

```
GET /v1/orgs/:orgId/queues/:queueId/sms-status
```

**Requires:** `queue:view`

Returns SMS delivery status for all entries in today's queue.

```typescript
type SMSDeliveryStatus =
  | "queued"
  | "sent"
  | "delivered"
  | "failed"
  | "dnd_blocked";

// Response 200
interface QueueSMSStatusResponse {
  statuses: Record<string, EntrySMSStatus>; // keyed by entryId
}

interface EntrySMSStatus {
  registration: SMSDeliveryStatus;
  turnNear: SMSDeliveryStatus | null;
  turnNow: SMSDeliveryStatus | null;
  bill: SMSDeliveryStatus | null;
}
```

**Frontend behavior:**

1. Fetch once on queue load (alongside `GET /queues/today`).
2. Update via `sms_status_updated` sync events.
3. Display delivery indicators next to each patient in queue.

### 13.6 Manual SMS Send / Resend

```
POST /v1/orgs/:orgId/sms/send
```

**Requires:** `sms:manual_send`

```typescript
interface ManualSMSSendRequest {
  entryId: string;
  templateType: SMSTrigger; // resend a specific template
  customMessage?: string; // or send custom (must be <160 chars)
}

// Response 200
interface ManualSMSSendResponse {
  smsId: string;
  status: SMSDeliveryStatus;
}
```

**Why separate from queue:** Three reasons. (1) Different concerns — queue entry = position/state, SMS = communication layer. (2) Different update patterns — queue state changes from devices via sync, SMS status updates from server via gateway callbacks. (3) Manual send is a separate action that doesn't touch queue state.

---

## 14. Analytics

### 14.1 Dashboard Analytics

```
GET /v1/orgs/:orgId/analytics?period=today|week|month&doctorId=<id>
```

**Requires:** `analytics:view`

```typescript
// Response 200
interface AnalyticsResponse {
  period: "today" | "week" | "month";
  dateRange: { from: string; to: string };

  summary: {
    totalPatients: number;
    totalRevenue: number;
    avgWaitTimeMs: number;
    avgConsultationTimeMs: number;
    busiestHour: string; // "10:00-11:00 AM"
    queueCompletionRate: number; // seen / registered (%)
  };

  comparison: {
    patients: { value: number; changePercent: number };
    revenue: { value: number; changePercent: number };
    avgWait: { value: number; changePercent: number };
  };

  dailyBreakdown: {
    date: string;
    patients: number;
    revenue: number;
    avgWaitMs: number;
  }[];

  // Aggregated from complaintTags (structured data, not free text)
  topComplaints: {
    tagKey: string; // "fever" — the structured tag
    label: string; // "Fever" — display name in org's language
    count: number;
    percentage: number;
  }[];

  hourlyDistribution?: {
    // today only
    hour: string;
    patients: number;
  }[];
}
```

**How `topComplaints` works:** Aggregated from `complaintTags` on queue entries — these are predefined structured keys ("fever", "bp_check", "diabetes_followup"), NOT free text. The chart works because the data is structured at registration via the complaint tag picker.

---

## 15. Frontend Data Flow Per Screen

### 15.1 Doctor Dashboard (HomeTab — Admin or Admin+Doctor)

| Data            | Source                                                   | Update       |
| --------------- | -------------------------------------------------------- | ------------ |
| Patient count   | Analytics API or derived from queue state                | Screen focus |
| Revenue         | Analytics API or derived from billing                    | Screen focus |
| Avg wait time   | Analytics API                                            | Screen focus |
| Live queue card | Queue state: `nowServingEntryId` + `waitingOrder.length` | Sync events  |
| Recent patients | Last 5 from `completedOrder`                             | Sync events  |

**API calls:** `GET /analytics?period=today` on mount. If user has `queue:view`, also `GET /queues/today` for live queue card.

**Who sees this tab:** Admin (with or without doctor role). Shows analytics + live queue summary.

### 15.2 Queue Management (QueueTab — Doctor + Assistant)

| Data           | Source                                                                  | Update                      |
| -------------- | ----------------------------------------------------------------------- | --------------------------- |
| Now Serving    | `entries` where `state === 'now_serving'`                               | Sync events                 |
| Waiting list   | `entries` where `state === 'waiting'`, sorted by position               | Sync events                 |
| Done list      | `entries` where `state === 'completed'`, sorted by completedAt desc     | Sync events                 |
| Stash banner   | `stashedFromPrevious.length > 0`                                        | Checked on app open         |
| Pause state    | `queueStatus`                                                           | Sync events                 |
| Sync indicator | SyncContext                                                             | Real-time                   |
| SMS status     | Separate SMS status fetch                                               | `sms_status_updated` events |
| ETAs           | `ETAEngine.calculate(waitingOrder, avgConsultationTime, totalPausedMs)` | Immediate (local)           |

**API calls:** `GET /queues/today` + `GET /queues/:id/sms-status` on mount. Phone lookup during registration. All mutations via local reducer + sync push.

**Permission filtering on queue screen:**

- Doctor sees queue read-only. Only action: Mark Complete.
- Assistant sees all queue actions: Add, Remove, Call Now, Step Out, Pause, Resume, End, Import.

### 15.3 Patient List (PatientsTab — All Roles)

**API calls:**

- Mount: `GET /patients?limit=20&sort=last_visit_desc`
- Scroll: `GET /patients?after=<cursor>&limit=20`
- Search: `GET /patients?search=<query>&limit=20`
- Tap patient: `GET /patients/:id/thread?limit=10`
- **Offline:** All queries hit local SQLite

**Permission note:** All roles call the same endpoints. Doctor sees full visit data. Assistant and admin see visits with `data: null` (redacted server-side based on org setting).

### 15.4 Billing Tab (Assistant Only)

| Data              | Source                                   | Update                     |
| ----------------- | ---------------------------------------- | -------------------------- |
| Today's bills     | `GET /bills?date=today`                  | Screen focus + sync events |
| Unbilled patients | Queue state: completed where `!isBilled` | Sync events                |
| Bill templates    | `GET /bill-templates`                    | Cached                     |
| Summary           | Bill list response `.summary`            | Screen focus               |

### 15.5 Analytics Dashboard (Admin Only)

**API calls:** `GET /analytics?period=today` on mount. Tab switch triggers `period=week` or `month`. Cached 5 min.

### 15.6 Settings (Permission-Filtered)

| Setting            | Permission Required    | Admin  | Doctor   | Assistant   |
| ------------------ | ---------------------- | ------ | -------- | ----------- |
| Clinic Profile     | `settings:edit_org`    | ✓ edit | ✗ hidden | ✗ hidden    |
| Working Hours      | `settings:edit_org`    | ✓ edit | ✗ hidden | ✗ hidden    |
| Manage Team        | `settings:manage_team` | ✓ edit | ✗ hidden | ✗ hidden    |
| SMS Templates      | `sms:edit_templates`   | ✓ edit | ✗ hidden | ✗ hidden    |
| Language           | `settings:view`        | ✓      | ✗        | ✓           |
| Alert Preferences  | `settings:view`        | ✓      | ✗        | ✓           |
| Export Records     | `settings:export_data` | ✓      | ✗ hidden | ✗ hidden    |
| Privacy & Security | `settings:view`        | ✓      | ✗        | ✓ read-only |
| Offline Data Sync  | `settings:view`        | ✓      | ✗        | ✓           |
| My Profile         | (always)               | ✓      | ✓        | ✓           |
| Logout             | (always)               | ✓      | ✓        | ✓           |

**Implementation:**

```typescript
const SETTINGS_ITEMS: SettingsItem[] = [
  {
    key: "clinic_profile",
    label: "Clinic Profile",
    permission: "settings:edit_org",
    screen: "ClinicProfile",
  },
  {
    key: "working_hours",
    label: "Working Hours",
    permission: "settings:edit_org",
    screen: "WorkingHours",
  },
  {
    key: "manage_team",
    label: "Manage Team",
    permission: "settings:manage_team",
    screen: "ManageTeam",
  },
  {
    key: "sms_templates",
    label: "SMS Templates",
    permission: "sms:edit_templates",
    screen: "SMSTemplates",
  },
  {
    key: "language",
    label: "Language",
    permission: "settings:view",
    screen: "Language",
  },
  {
    key: "alerts",
    label: "Alerts",
    permission: "settings:view",
    screen: "Alerts",
  },
  {
    key: "export",
    label: "Export Records",
    permission: "settings:export_data",
    screen: "DataExport",
  },
  {
    key: "privacy",
    label: "Privacy",
    permission: "settings:view",
    screen: "PrivacySecurity",
  },
  {
    key: "sync",
    label: "Offline Sync",
    permission: "settings:view",
    screen: "OfflineSync",
  },
];

const visibleItems = SETTINGS_ITEMS.filter((item) =>
  userPermissions.has(item.permission),
);
```

---

## 16. Error Code Catalog

### Authentication

| Code                    | HTTP | Description                    |
| ----------------------- | ---- | ------------------------------ |
| `INVALID_PHONE`         | 400  | Phone format invalid           |
| `OTP_EXPIRED`           | 400  | OTP expired (>5 min)           |
| `OTP_INVALID`           | 400  | Incorrect OTP                  |
| `OTP_RATE_LIMITED`      | 429  | Too many OTP requests          |
| `TOKEN_EXPIRED`         | 401  | JWT expired — refresh          |
| `TOKEN_INVALID`         | 401  | JWT malformed/tampered         |
| `REFRESH_TOKEN_EXPIRED` | 401  | Re-authenticate required       |
| `DEVICE_NOT_REGISTERED` | 403  | Device not associated with org |

### Permission

| Code                      | HTTP | Description                                             |
| ------------------------- | ---- | ------------------------------------------------------- |
| `INSUFFICIENT_PERMISSION` | 403  | User lacks required permission                          |
| `UNAUTHORIZED_EVENT_ROLE` | 403  | Sync: none of user's roles can generate this event type |
| `MEMBER_DEACTIVATED`      | 403  | Member exists but isActive: false                       |

### Organization & Members

| Code                       | HTTP | Description                                             |
| -------------------------- | ---- | ------------------------------------------------------- |
| `ORG_NOT_FOUND`            | 404  | Organization doesn't exist                              |
| `MEMBER_NOT_FOUND`         | 404  | Member doesn't exist in org                             |
| `MEMBER_ALREADY_EXISTS`    | 409  | Phone number already enrolled in org                    |
| `PROFILE_INCOMPLETE`       | 400  | Doctor role requires complete profile before proceeding |
| `INVALID_ROLE_COMBINATION` | 400  | Invalid role array (e.g., empty)                        |

### Queue

| Code                       | HTTP | Description                   |
| -------------------------- | ---- | ----------------------------- |
| `QUEUE_NOT_FOUND`          | 404  | No queue for this date/doctor |
| `QUEUE_ALREADY_ENDED`      | 409  | Queue already ended           |
| `ENTRY_NOT_FOUND`          | 404  | Queue entry doesn't exist     |
| `INVALID_STATE_TRANSITION` | 400  | Entry not in required state   |
| `NO_PATIENTS_TO_STASH`     | 400  | No waiting patients to stash  |

### Sync

| Code                   | HTTP | Description                                    |
| ---------------------- | ---- | ---------------------------------------------- |
| `SYNC_BATCH_TOO_LARGE` | 413  | >50 events in push batch                       |
| `INVALID_EVENT_SCHEMA` | 400  | Payload doesn't match expected shape           |
| `SYNC_CLOCK_DRIFT`     | 400  | Device timestamp >1 hour from server (warning) |

### Patient

| Code                | HTTP | Description                       |
| ------------------- | ---- | --------------------------------- |
| `PATIENT_NOT_FOUND` | 404  | Patient doesn't exist in this org |
| `SEARCH_TOO_SHORT`  | 400  | Search query < 2 characters       |

### Billing

| Code                | HTTP | Description        |
| ------------------- | ---- | ------------------ |
| `BILL_NOT_FOUND`    | 404  | Bill doesn't exist |
| `BILL_ALREADY_PAID` | 409  | Already paid       |
| `BILL_NO_ITEMS`     | 400  | Must have ≥1 item  |

---

## 17. Rate Limits & Quotas

| Category       | Limit      | Window              |
| -------------- | ---------- | ------------------- |
| OTP send       | 3          | per phone per 5 min |
| OTP verify     | 5 attempts | per requestId       |
| Sync push      | 60         | per device per min  |
| Sync pull      | 120        | per device per min  |
| Patient search | 30         | per device per min  |
| Analytics      | 10         | per user per min    |
| All other      | 120        | per device per min  |

**Headers on every response:**

```
X-RateLimit-Limit: 60
X-RateLimit-Remaining: 45
X-RateLimit-Reset: 1707500060
```

**On 429:** Read `retryAfterMs`. Silently retry for sync. Show error only for user-initiated actions.

---

## 18. Changelog from v2.0

| What Changed                                             | Why                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| -------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Multi-role model: `roles: UserRole[]`**                | A person can be admin + doctor. Permissions are the union of all assigned roles. Single `role` field replaced with `roles` array throughout (AuthUser, OrgMember, SyncEvent).                                                                                                                                                                                                                                                                                                                            |
| **Three distinct roles: admin, doctor, assistant**       | Admin manages org (team, settings, analytics, export). Doctor does clinical work (notes, prescriptions, mark complete). Assistant manages clinic floor (all queue ops, billing). No role is a superset of another — they're separate domains.                                                                                                                                                                                                                                                            |
| **Admin has NO queue operations**                        | Admin runs the business, not the queue floor. Even a doctor-owner doesn't manage their own queue — the assistant does. This mirrors how clinics actually work.                                                                                                                                                                                                                                                                                                                                           |
| **Creator always enrolled as admin**                     | `POST /orgs` creates org + enrolls creator as `['admin']`. Doctor role added separately via `PUT /members/:self` if they practice. Supports both doctor-creates-org and manager-creates-org with the same flow.                                                                                                                                                                                                                                                                                          |
| **Profile as databag**                                   | `profileData: Record<string, any>` + `profileSchemaVersion` replaces hardcoded `DoctorProfile`. Frontend defines structure based on roles. Backend stores as-is, extracts `specialization` for indexing. Allows specialty-specific fields without backend migrations.                                                                                                                                                                                                                                    |
| **`isProfileComplete` computed flag**                    | Server checks if required fields present for member's roles. Frontend uses to route (doctor without profile → "Complete your profile" screen).                                                                                                                                                                                                                                                                                                                                                           |
| **Flat `entries` array in queue response**               | Replaced pre-grouped `nowServing`/`waiting`/`completed`/`removed`. Frontend derives grouping in one pass. Server stores data, frontend derives views. Clean separation.                                                                                                                                                                                                                                                                                                                                  |
| **SMS decoupled from QueueEntryFull**                    | `smsStatus` removed from queue entries. Separate `GET /queues/:id/sms-status` endpoint. Different concerns (queue state vs communication), different update patterns (sync events vs gateway callbacks), and manual send capability.                                                                                                                                                                                                                                                                     |
| **No separate `StashedEntry` type**                      | Stashed entries use `QueueEntryFull` with `state: 'stashed'` + `stashedFromQueueId` field. Same table, different state. No redundant type.                                                                                                                                                                                                                                                                                                                                                               |
| **Session-based queue (v3.1)**                           | Queue identity is a session, not a date. `date` field removed, `endedAt` added, status includes `'ended'`. Stash references `sourceQueueId` not target dates. End Queue is fully offline — no server date computation. See §9.1 for full rationale.                                                                                                                                                                                                                                                      |
| **`previousQueueStash` in queue response**               | Replaces `stashedFromPrevious` field. Server query: find doctor's most recently ended queue `WHERE status = 'ended'` that has entries with `state = 'stashed'`. No date matching needed.                                                                                                                                                                                                                                                                                                                 |
| **`sms:manual_send` permission**                         | New permission for assistant to manually resend or send custom SMS. Separate from template management.                                                                                                                                                                                                                                                                                                                                                                                                   |
| **Manual SMS send endpoint**                             | `POST /sms/send` — assistant can resend failed SMS or send custom message. Separate action that doesn't touch queue state.                                                                                                                                                                                                                                                                                                                                                                               |
| **`EVENT_ALLOWED_ROLES` replaces `ROLE_PERMISSIONS`**    | Renamed for clarity. Now checks if ANY of user's roles matches allowed list (supports multi-role). Admin explicitly excluded from all queue/clinical events.                                                                                                                                                                                                                                                                                                                                             |
| **`userRoles` in SyncEvent**                             | Replaced singular `userRole` with `userRoles: UserRole[]` to support multi-role users. Server still validates against DB, not self-reported.                                                                                                                                                                                                                                                                                                                                                             |
| **Updated tab visibility**                               | Three-role matrix: Admin sees Home/Patients/Analytics/Settings. Doctor sees Queue/Patients. Assistant sees Queue/Patients/Billing/Settings. Admin+Doctor sees union.                                                                                                                                                                                                                                                                                                                                     |
| **Updated settings visibility**                          | Settings items now correctly show/hide for three roles. Admin sees org management items. Assistant sees operational items. Doctor sees only My Profile + Logout.                                                                                                                                                                                                                                                                                                                                         |
| **Added Section 8.8: Complete Onboarding Flows**         | Three complete flow walkthroughs: doctor-creates-org, manager-creates-org, promote-existing-member. Step-by-step with API calls and AuthUser state at each step.                                                                                                                                                                                                                                                                                                                                         |
| **New error codes**                                      | Added: `MEMBER_ALREADY_EXISTS`, `PROFILE_INCOMPLETE`, `INVALID_ROLE_COMBINATION`. For multi-role and profile databag edge cases.                                                                                                                                                                                                                                                                                                                                                                         |
| **Section 9.6: Add Patient — Complete Flow Walkthrough** | End-to-end trace of adding a patient: phone lookup (only network call), client-side token assignment, immediate local reducer update, SQLite persistence, background sync push, server-side SMS trigger, doctor's device update. Covers first-patient auto-activation, fully offline scenario, and visual flow diagram.                                                                                                                                                                                  |
| **Removed `primaryRole` from RegisterDeviceRequest**     | Field was never consumed by any flow in the spec. All authorization uses user roles from member record, not device tags. Ambiguous for multi-role users. Device registration now requires only `deviceId` and optional `pushToken`.                                                                                                                                                                                                                                                                      |
| **Billing: confirmation gate + no-edit MVP policy**      | Frontend must show confirmation screen (patient, items, total) before generating `bill_created` event. Once confirmed, bill is final and immutable. No edit/void for MVP — mistakes are handled by creating a corrected bill. Edit/void is P1.                                                                                                                                                                                                                                                           |
| **Role change staleness documented**                     | When admin changes a member's roles, the affected user's local permissions remain stale until app restart or token refresh. Server-side enforcement is immediate. Documented in Section 8.5 for dev team awareness.                                                                                                                                                                                                                                                                                      |
| **`patient_added` multi-table write clarified**          | Server must upsert `patient_master` in addition to creating `queue_entry`. Documented in event spec and walkthrough for dev team.                                                                                                                                                                                                                                                                                                                                                                        |
| **Assistant→Doctor assignment (v3.2)**                   | `assignedDoctorId: string \| null` + `assignedDoctorName: string \| null` added to `AuthUser` (§6.2) and `OrgMember` (§8.1). One assistant manages one doctor's queue — prevents multi-device queue conflicts. Admin assigns via ManageTeam. `assignedDoctorName` denormalized on both types: on `AuthUser` because the boot sequence needs the name before members list loads; on `OrgMember` to avoid O(n) member lookups per assistant card in ManageTeam. Server populates via JOIN on member table. |
| **`assignedDoctorId` on AddMember/UpdateMember (v3.2)**  | `AddMemberRequest` gets `assignedDoctorId?: string` (optional, for assistant role). `UpdateMemberRequest` gets `assignedDoctorId?: string \| null` (omit = no change, string = set, null = clear). Server validates target is a doctor in same org and member has assistant role.                                                                                                                                                                                                                        |
| **`user: AuthUser` in RefreshTokenResponse (v3.2)**      | Previously the client had no way to learn about server-side changes (role/assignment/permission changes) without re-login. Adding `AuthUser` to the refresh response (§6.3) piggybacks updated user data on an existing 24h call. This is how assignment changes propagate: admin reassigns → server updates → assistant's next token refresh returns new `assignedDoctorId`.                                                                                                                            |
