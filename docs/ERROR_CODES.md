# ClinicOS API — Error Codes Reference

All API errors follow a standard JSON envelope:

```json
{
  "success": false,
  "error": {
    "code": "OTP_EXPIRED",
    "message": "OTP has expired",
    "retryable": false,
    "retryAfterMs": null,
    "details": null
  }
}
```

| Field | Type | Description |
|-------|------|-------------|
| `code` | string | Machine-readable error code (use this for client-side branching) |
| `message` | string | Human-readable message (safe to display to user) |
| `retryable` | boolean | Whether the client should retry the same request |
| `retryAfterMs` | long? | Suggested wait before retrying (milliseconds) |
| `details` | map? | Field-level validation errors (key = field name, value = message) |

---

## HTTP 400 — Bad Request

### Authentication

| Code | Message | Retryable | When |
|------|---------|-----------|------|
| `SMS_SEND_FAILED` | Failed to send OTP. Please try again. | false | SMS provider fails to deliver OTP |
| `OTP_REQUEST_NOT_FOUND` | No OTP request found | false | `requestId` doesn't match any OTP request |
| `OTP_ALREADY_VERIFIED` | OTP already verified | false | OTP was already verified successfully |
| `OTP_EXPIRED` | OTP has expired | false | OTP verification window (5 min) passed |
| `OTP_MAX_ATTEMPTS` | Maximum OTP attempts exceeded | false | 3+ failed OTP verification attempts |
| `OTP_INVALID` | Invalid OTP | **true** | Wrong OTP entered (user can retry) |
| `TOKEN_INVALID` | Invalid refresh token / Not a refresh token | false | Malformed or wrong token type on `/auth/token/refresh` |
| `DEVICE_MISMATCH` | Device ID mismatch | false | Refresh token's device ID doesn't match request |
| `TOKEN_REVOKED` | Refresh token has been revoked | false | Token was revoked (logout, new login, or account deletion) |

### Account Lifecycle

| Code | Message | Retryable | When |
|------|---------|-----------|------|
| `ACCOUNT_SCHEDULED_DELETION` | Account is scheduled for permanent deletion on {date}. Contact support to cancel. | false | OTP verify attempted during 30-day grace period |
| `ACCOUNT_DELETED` | Account has been deleted | false | Token refresh attempted for a deleted account |
| `ACCOUNT_ALREADY_DELETED` | Account is already scheduled for deletion | false | User tries to delete an already-deleted account |
| `ACTIVE_QUEUE_EXISTS` | Please end all active queues before deleting your account | false | Doctor has ACTIVE or PAUSED queues |
| `SOLE_ADMIN_CANNOT_DELETE` | You are the sole administrator of organization '{name}'. Please assign another admin before deleting your account. | false | Only admin in org with other members |

### Members & Organization

| Code | Message | Retryable | When |
|------|---------|-----------|------|
| `USER_ACCOUNT_DELETED` | This phone number belongs to an account pending deletion | false | Adding a deleted user's phone to an org |
| `MEMBER_EXISTS` | User is already a member of this organization | false | Adding user who is already a member |
| `INVALID_ASSIGNMENT` | Only assistants can be assigned to doctors | false | Assigning non-assistant role to a doctor |
| `NOT_A_DOCTOR` | The specified member is not a doctor | false | Doctor-only operation on non-doctor member |

### Queue (REST endpoints)

| Code | Message | Retryable | When |
|------|---------|-----------|------|
| `INVALID_SOURCE_QUEUE` | Source queue does not belong to this organization / Can only import stash from an ended queue | false | Invalid source queue for stash import |
| `BUSINESS_ERROR` | Queue does not belong to this organization | false | Queue org mismatch |
| `BUSINESS_ERROR` | Queue has already ended | false | Operating on an ended queue |
| `BUSINESS_ERROR` | Cannot import to an ended queue | false | Stash import target queue is ended |

### Billing

| Code | Message | Retryable | When |
|------|---------|-----------|------|
| `BUSINESS_ERROR` | Patient does not belong to this organization | false | Billing for patient in different org |
| `BUSINESS_ERROR` | Queue entry does not belong to this organization | false | Queue entry org mismatch |
| `BUSINESS_ERROR` | Bill already exists for this queue entry | false | Duplicate bill for same queue entry |
| `BUSINESS_ERROR` | Bill is already marked as paid | false | Marking already-paid bill as paid |

### AI Extraction

| Code | Message | Retryable | When |
|------|---------|-----------|------|
| `SERVICE_UNAVAILABLE` | AI extraction is not available | false | AI extraction disabled in config |
| `AI_ERROR` | AI extraction failed: {details} | false | Non-retryable AI provider error |
| `AI_ERROR` | AI extraction failed after {N} attempts | **true** | All retry attempts exhausted |

### Image Upload

| Code | Message | Retryable | When |
|------|---------|-----------|------|
| `BUSINESS_ERROR` | File is required | false | No file in upload request |
| `BUSINESS_ERROR` | File size exceeds 10MB limit | false | File too large |
| `BUSINESS_ERROR` | Unsupported file type: {type}. Allowed: JPEG, PNG, WebP, PDF | false | Invalid file format |

### SMS (bill notifications)

| Code | Message | Retryable | When |
|------|---------|-----------|------|
| `BUSINESS_ERROR` | Queue entry does not belong to this organization | false | Queue entry org mismatch |
| `BUSINESS_ERROR` | Custom message must be less than 160 characters | false | SMS message too long |

### Validation Errors

| Code | Message | Retryable | When |
|------|---------|-----------|------|
| `VALIDATION_ERROR` | Invalid request data | false | `@Valid` annotation failure — `details` map contains field-level errors |
| `VALIDATION_ERROR` | Missing required parameter: {name} | false | Required query/form parameter missing |

---

## HTTP 401 — Unauthorized

| Code | Message | When |
|------|---------|------|
| `UNAUTHORIZED` | Authentication required | Missing or invalid JWT in `Authorization` header |

---

## HTTP 403 — Forbidden

| Code | Message | When |
|------|---------|------|
| `INSUFFICIENT_PERMISSION` | Required permission: {permissionName} | User lacks the `@RequirePermission` for the endpoint |
| `ACCESS_DENIED` | Access denied | Spring Security access denial |

---

## HTTP 404 — Not Found

| Code | Message | When |
|------|---------|------|
| `NOT_FOUND` | {Type} not found: {uuid} | Resource doesn't exist or doesn't belong to user's org |

Resource types: `Organization`, `Patient`, `Queue`, `Source Queue`, `Doctor`, `Doctor member`, `QueueEntry`, `Bill`, `BillTemplate`, `Visit`, `VisitImage`, `SMS Template`, `Member`, `User`, `Role`, `ComplaintTag`, `Image`

---

## HTTP 409 — Conflict

| Code | Message | When |
|------|---------|------|
| `CONFLICT` | User already belongs to an organization | Creating org when user is already in one |
| `CONFLICT` | Complaint tag with key '{key}' already exists | Duplicate complaint tag key |

---

## HTTP 500 — Internal Server Error

| Code | Message | Retryable | When |
|------|---------|-----------|------|
| `INTERNAL_ERROR` | An unexpected error occurred | **true** | Unhandled exception (storage failures, missing entities, etc.) |

---

## Sync Rejection Codes

Sync push (`POST /v1/sync/push`) returns rejected events inline in the response body — these are **not** HTTP errors. The HTTP response is always `200` with accepted and rejected arrays.

```json
{
  "success": true,
  "data": {
    "accepted": [{ "eventId": "...", "serverReceivedAt": 1234567890 }],
    "rejected": [{ "eventId": "...", "code": "PROCESSING_ERROR", "reason": "..." }]
  }
}
```

| Code | When |
|------|------|
| `DUPLICATE_IGNORED` | Event with this `eventId` was already processed |
| `INVALID_EVENT_TYPE` | Unknown event type |
| `UNAUTHORIZED_ROLE` | User's role cannot perform this event type |
| `SCHEMA_MISMATCH` | Invalid or missing schema version |
| `PROCESSING_ERROR` | Event handler threw an exception — `reason` contains the error message |

### Common PROCESSING_ERROR reasons

| Reason | Event types | When |
|--------|-------------|------|
| Cannot add patient to an ENDED queue | `patient_added` | Queue already ended |
| Doctor already has an active queue | `patient_added`, `stash_imported` | Doctor has ACTIVE/PAUSED queue |
| Doctor not found or inactive | `patient_added`, `stash_imported` | Doctor UUID invalid or soft-deleted |
| Queue entry not found: {id} | `patient_removed`, `call_now`, `step_out`, `mark_complete` | Entry UUID doesn't exist |
| Queue not found: {id} | `queue_paused`, `queue_resumed`, `queue_ended` | Queue UUID doesn't exist |
| Source queue not found: {id} | `stash_dismissed` | Source queue UUID doesn't exist |
| Cannot call patient — another patient is already being served | `call_now` | Queue already has a CALLED entry |
| Invalid state transition: {from} → {to} | Any queue-mutating event | State machine violation |
| Cannot pause/resume an ENDED queue | `queue_paused`, `queue_resumed` | Queue already ended |
| Queue does not belong to doctor | `stash_dismissed` | Doctor ownership mismatch |
| Can only dismiss stash from an ended queue | `stash_dismissed` | Source queue not ended |
| Patient not found: {id} | `visit_saved`, `bill_created`, `bill_paid` | Patient UUID doesn't exist |
| Member not found: {id} | `visit_saved`, `bill_created`, `bill_paid` | User not an org member |
| Bill not found: {id} | `bill_updated` | Bill UUID doesn't exist |
| Visit {id} does not belong to patient {id} | `visit_saved` | Visit-patient mismatch on update |
| visitId is required | `visit_saved` | Missing required field |
| patientId is required | `bill_created`, `bill_paid` | Missing required field |
| Bill already exists for queue entry | `bill_paid` | Duplicate bill |
