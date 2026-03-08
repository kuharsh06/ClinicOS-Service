# Application Metrics & Observability

---

## Goal

Before this change, we had **zero visibility** into what was happening inside the application:
- No way to know if sync events were silently failing
- No way to know if AI extraction was degrading
- No way to know if OTP delivery was broken
- No way to know if users were experiencing auth issues
- Had to manually query the DB (`SELECT ... FROM event_store WHERE status='REJECTED'`) to find problems — adding load to the DB itself

**What we built:** In-memory metrics that track every critical path in the app. Zero DB load, ~56 KB memory ceiling. Queryable via `/actuator/prometheus` (machine) or `/v1/admin/stats` (human).

**What this enables:**
1. **Right now:** One curl call tells you if anything is broken
2. **Near future:** Prometheus + Grafana dashboards with hourly/daily trends
3. **Long term:** Alerting — get notified when failure rates spike

---

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│                    Spring Boot App                        │
│                                                          │
│  ┌─────────────────┐    ┌──────────────────────────────┐ │
│  │   AppMetrics     │◄───│  SyncEventProcessor          │ │
│  │  (MeterRegistry) │◄───│  AiExtractionService         │ │
│  │                  │◄───│  AuthService                 │ │
│  │  In-memory       │◄───│  JwtAuthenticationFilter     │ │
│  │  lock-free       │    └──────────────────────────────┘ │
│  │  atomics         │                                     │
│  │  ~56 KB          │    ┌──────────────────────────────┐ │
│  │                  │───►│  /actuator/prometheus         │ │
│  │                  │───►│  /actuator/metrics/{name}     │ │
│  │                  │───►│  /v1/admin/stats              │ │
│  └─────────────────┘    └──────────────────────────────┘ │
│                                                          │
│  ┌──────────────────────────────────────────────────────┐ │
│  │  Spring Actuator (auto-instrumented)                  │ │
│  │  • http.server.requests — every endpoint              │ │
│  │  • jvm.memory.* — heap, GC                           │ │
│  │  • hikaricp.* — DB connection pool                    │ │
│  └──────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────┘
         │
         ▼ (future)
┌─────────────────┐      ┌──────────────┐
│   Prometheus     │─────►│   Grafana     │
│   (scrapes /15s) │      │  (dashboards) │
└─────────────────┘      └──────────────┘
```

**Memory:** Counters are fixed-size (one `double` per unique tag combination). 280 unique meters × ~200 bytes = ~56 KB. Never grows regardless of traffic.

**Counters reset on restart.** This is by design — for persistent history, Prometheus scrapes the counters every 15s and stores them in its time-series DB.

---

## Dependencies & Config

```xml
<!-- pom.xml -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

```properties
# application.properties
management.endpoints.web.exposure.include=health,info,metrics,prometheus
```

---

## Endpoints

| Endpoint | Auth | Format | Use case |
|----------|------|--------|----------|
| `GET /actuator/prometheus` | No (permitAll) | Prometheus text | Machine scraping, full dump of ALL metrics |
| `GET /actuator/metrics/{name}` | No | JSON | Query single metric (e.g., `sync.events`) |
| `GET /v1/admin/stats` | Yes (JWT) | JSON | Human-readable dashboard, one curl = full picture |

---

## Files Changed

| File | What |
|------|------|
| `pom.xml` | Added `micrometer-registry-prometheus` dependency |
| `application.properties` | Exposed `prometheus` actuator endpoint |
| `config/AppMetrics.java` | **NEW** — Central metrics class (all counters/timers) |
| `controller/AdminController.java` | **NEW** — `GET /v1/admin/stats` (reads MeterRegistry, no DB) |
| `service/SyncEventProcessor.java` | Added sync event counters + timers |
| `service/AiExtractionService.java` | Added extraction counters + timers |
| `service/AuthService.java` | Added auth counters (send, verify, refresh, logout) |
| `security/jwt/JwtTokenProvider.java` | `validateToken` returns specific failure reason (expired/malformed/unsupported/empty_claims) |
| `security/filter/JwtAuthenticationFilter.java` | JWT rejection counters with specific reasons + user_not_found |
| `security/PermissionAspect.java` | Added `access_denied` counter on permission failures |

---

## 1. Auth Flow — `auth.events`

### Why this matters

Auth is the entry point. If OTP delivery breaks, nobody can log in. If tokens aren't refreshing, the app appears broken. If someone is brute-forcing OTPs, we need to know.

### Complete flow with every tracked path

```
User opens app
    │
    ▼
POST /v1/auth/otp/send
    │
    ├─ OTP created + SMS sent ──► auth.events{action=send, status=success}
    │                              sms.send{status=success}
    │
    └─ SMS delivery failed ─────► auth.events{action=send, status=failure}
                                   sms.send{status=failure}

User enters OTP
    │
    ▼
POST /v1/auth/otp/verify
    │
    ├─ requestId not found ─────► auth.events{verify, failure, not_found}
    │   (invalid/crafted UUID)     Signal: possible abuse
    │
    ├─ OTP already used ────────► auth.events{verify, failure, already_verified}
    │   (replay attempt)           Signal: user confusion or replay attack
    │
    ├─ OTP expired (>5 min) ────► auth.events{verify, failure, expired}
    │                              Signal: SMS delayed or user too slow
    │
    ├─ 3+ wrong attempts ──────► auth.events{verify, failure, max_attempts}
    │                              Signal: brute force attempt
    │
    ├─ Wrong OTP code ──────────► auth.events{verify, failure, invalid_otp}
    │                              Signal: typo or wrong OTP
    │
    ├─ Correct — new user ──────► auth.events{verify, success, new_user}
    │   (user.name == null)        Signal: growth tracking — new signup
    │
    └─ Correct — returning ─────► auth.events{verify, success, returning_user}
        (user.name exists)         Signal: daily active users

App uses access token for API calls
    │
    ▼
JwtAuthenticationFilter (every authenticated request)
    │
    ├─ Valid access token ──────► (no metric — normal path)
    │
    ├─ Expired JWT ────────────► auth.events{jwt_rejected, failure, expired}
    │                              Signal: clients not refreshing in time (normal)
    │
    ├─ Malformed JWT ──────────► auth.events{jwt_rejected, failure, malformed}
    │                              Signal: SECURITY — tampered/garbage token
    │
    ├─ Unsupported JWT ────────► auth.events{jwt_rejected, failure, unsupported}
    │                              Signal: wrong algorithm or format
    │
    ├─ Empty claims ───────────► auth.events{jwt_rejected, failure, empty_claims}
    │                              Signal: empty or null token string
    │
    ├─ Refresh token used ─────► auth.events{jwt_rejected, failure, not_access_token}
    │   as access token            Signal: client bug — mixing up tokens
    │
    ├─ User not found ─────────► auth.events{jwt_rejected, failure, user_not_found}
    │   (deleted user, DB issue)   Signal: stale token or data inconsistency
    │
    └─ Exception (other) ─────► auth.events{jwt_rejected, failure, exception}
                                   Signal: infrastructure issue

Permission check (@RequirePermission on any endpoint)
    │
    ├─ Has permission ─────────► (no metric — normal path)
    │
    └─ Missing permission ─────► auth.events{access_denied, failure, insufficient_permission}
                                   Signal: role misconfiguration or privilege escalation

Access token expires (24h), app refreshes
    │
    ▼
POST /v1/auth/token/refresh
    │
    ├─ Token expired ──────────► auth.events{refresh, failure, token_expired}
    │                              Signal: refresh token also expired (30d)
    │
    ├─ Token malformed ────────► auth.events{refresh, failure, token_malformed}
    │                              Signal: corrupted/tampered refresh token
    │
    ├─ Not a refresh token ─────► auth.events{refresh, failure, not_refresh_token}
    │                              Signal: client sending access token here
    │
    ├─ Device mismatch ─────────► auth.events{refresh, failure, device_mismatch}
    │   (token from device A,      Signal: SECURITY — possible token theft,
    │    used from device B)       token shared between devices
    │
    ├─ Token revoked ───────────► auth.events{refresh, failure, token_revoked}
    │   (already logged out)       Signal: session hijack attempt or
    │                              user logged out from another device
    │
    └─ Success ─────────────────► auth.events{refresh, success}
                                   Signal: app activity, healthy token rotation

User logs out
    │
    ▼
POST /v1/auth/logout
    └───────────────────────────► auth.events{logout, success}
                                   Signal: intentional session end
```

### Key questions this answers

| Question | How to check |
|----------|-------------|
| How many new users signed up? | `auth.events{action=verify, reason=new_user}` |
| Are OTPs expiring too often (SMS delay)? | `auth.events{action=verify, reason=expired}` |
| Is someone brute-forcing OTPs? | `auth.events{action=verify, reason=max_attempts}` |
| Are clients refreshing tokens in time? | `jwt_rejected{reason=expired}` — high count = normal token lifecycle |
| Is someone sending tampered tokens? | `jwt_rejected{reason=malformed}` — any count is suspicious |
| Deleted user still hitting API? | `jwt_rejected{reason=user_not_found}` |
| Are roles/permissions configured correctly? | `auth.events{action=access_denied}` |
| Possible token theft? | `auth.events{action=refresh, reason=device_mismatch}` |
| Is login working end-to-end? | `send{success}` → `verify{success}` conversion rate |
| SMS delivery reliability? | `sms.send{success}` vs `sms.send{failure}` |

---

## 2. Sync Event Flow — `sync.events`

### Why this matters

Sync is the core of the app — every queue operation, every visit, every bill goes through the sync pipeline. If events are silently failing, the clinic's queue is broken.

### Complete flow

```
Device sends events
    │
    ▼
POST /v1/sync/push (SyncService)
    │
    ├─ Resolve queueId for lock
    ├─ Acquire per-queue lock (if queue-mutating)
    │
    ▼
SyncEventProcessor.processSingleEvent() (REQUIRES_NEW transaction)
    │
    │  ◄─── Timer starts: sync.events.duration
    │
    ├─ Event already exists? ───► sync.events{type, rejected, DUPLICATE_IGNORED}
    │   (dedup by eventId)         Normal for retries after network timeout
    │
    ├─ Role not allowed? ──────► sync.events{type, rejected, UNAUTHORIZED_ROLE}
    │   (assistant doing           Signal: permission misconfiguration
    │    doctor-only action)
    │
    ├─ Invalid event type? ────► sync.events{type, rejected, INVALID_EVENT_TYPE}
    │                              Signal: client sending unknown event type
    │
    ├─ Bad schema version? ────► sync.events{type, rejected, SCHEMA_MISMATCH}
    │                              Signal: very old app version
    │
    ├─ Business logic error ───► sync.events{type, rejected, PROCESSING_ERROR}
    │   • Invalid state transition (COMPLETED → CALLED)
    │   • Queue entry not found
    │   • Doctor already has active queue
    │   • Cannot add to ENDED queue
    │   Signal: client out of sync, race condition, or bug
    │
    ├─ Success ────────────────► sync.events{type, applied, none}
    │                              Normal operation
    │
    └─ Idempotent no-op ──────► sync.events{type, applied, IDEMPOTENT}
        (already paused/ended,     Event accepted but no work done
         bill already exists)      Signal: duplicate events from client retry
    │
    │  ◄─── Timer stops: sync.events.duration{type, status}

    Queue operation metrics (recorded inside handlers):

    patient_added / stash_imported (new queue created):
        └─────────────────────────► queue.started              ← queues started per day

    call_now:
        └─────────────────────────► queue.wait_time            ← calledAt − registeredAt

    mark_complete:
        └─────────────────────────► queue.consultation_time    ← completedAt − calledAt

    queue_ended:
        ├─────────────────────────► queue.ended                ← queues ended per day
        ├─────────────────────────► queue.duration             ← endedAt − startedAt
        ├─────────────────────────► queue.patients_per_session ← completed entries count
        └─────────────────────────► queue.auto_completed       ← CALLED entries force-completed
```

### Event types tracked

| Event type | What it represents | Key failure scenarios |
|------------|-------------------|----------------------|
| `patient_added` | Patient joins queue | Doctor has active queue (wrong queueId), ENDED queue |
| `call_now` | Patient called to consult | Another patient already being served, invalid state |
| `mark_complete` | Consultation done | Already completed (duplicate event) |
| `step_out` | Patient stepped out | Not in CALLED state |
| `patient_removed` | Patient removed from queue | Already removed/completed |
| `queue_paused` | Queue paused | Already ended |
| `queue_resumed` | Queue resumed | Already ended |
| `queue_ended` | Queue ended for the day | Already ended (idempotent) |
| `stash_imported` | Yesterday's patients imported | Doctor has active queue |
| `visit_saved` | Clinical data saved | Patient not found, visit belongs to different patient |
| `bill_created` | Bill created | Patient not in org, duplicate bill |
| `bill_updated` | Bill payment status changed | Bill not in org |
| `bill_paid` | Bill created as paid | Duplicate, bill already exists for queue entry |

### Key questions this answers

| Question | How to check |
|----------|-------------|
| Overall sync reliability? | `applied / (applied + rejected)` = success rate |
| Which event type fails most? | Sort `sync.events{status=rejected}` by type |
| Are we getting duplicate events? | `sync.events{code=DUPLICATE_IGNORED}` + `IDEMPOTENT` counts |
| Is any event type slow? | `sync.events.duration{type=X}` avg and max |
| State transition bugs? | `sync.events{code=PROCESSING_ERROR}` — check logs for details |
| How many queues per day? | `queue.started` count |
| Average patient wait time? | `queue.wait_time` avg — target under 15 mins |
| Average consultation length? | `queue.consultation_time` avg |
| How long are clinic sessions? | `queue.duration` avg and max |
| Patients per session? | `queue.patients_per_session` avg and max |
| Are consultations interrupted? | `queue.auto_completed` — patients still being served when queue ended |

---

## 3. AI Extraction Flow — `ai.extraction`

### Why this matters

AI extraction powers the voice-to-prescription feature. If Gemini is failing or returning garbage, doctors lose their dictated prescriptions.

### Complete flow

```
Doctor dictates → transcript sent
    │
    ▼
POST /v1/orgs/:orgId/ai/extract
    │
    │  ◄─── Timer starts: ai.extraction.duration
    │
    ├─ AI disabled? ────────────► ai.extraction{disabled, 0}
    │                              Config issue — clinicos.ai.enabled=false
    │
    ▼
Retry loop (max 3 attempts)
    │
    ├─ Attempt 1:
    │   ├─ Success ─────────────► ai.extraction{success, 1}     ← ideal
    │   ├─ Malformed JSON ──────► ai.extraction{malformed, 1}   ← Gemini returned bad JSON
    │   ├─ Retryable error ─────► ai.extraction{retry, 1}      ← rate limit, timeout
    │   │   (wait 1s + jitter)        (known AiProviderException)
    │   ├─ Unexpected error ──► ai.extraction{unexpected_error, 1} ← bug, unknown exception
    │   │   (wait 1s + jitter)        Signal: investigate logs
    │   └─ Non-retryable error ─► ai.extraction{error, 1}      ← API key invalid, 403
    │       (throw immediately)
    │
    ├─ Attempt 2:
    │   ├─ Success ─────────────► ai.extraction{success, 2}     ← recovered on retry
    │   └─ ... same as above
    │
    ├─ Attempt 3:
    │   ├─ Success ─────────────► ai.extraction{success, 3}     ← barely made it
    │   └─ ... same as above
    │
    └─ All 3 failed ────────────► ai.extraction{exhausted, 3}   ← total failure
    │
    │  ◄─── Timer stops: ai.extraction.duration{status}
```

### Key questions this answers

| Question | How to check |
|----------|-------------|
| AI extraction success rate? | `success / (success + error + exhausted)` |
| Is Gemini degrading? | Rising `retry` count or `malformed` count |
| Are we hitting rate limits? | `retry` count spiking |
| Bug in AI integration code? | `unexpected_error` count — non-provider exceptions, check logs |
| Average transcription latency? | `ai.extraction.duration{status=success}` |
| Total failures (doctor lost data)? | `exhausted` count — these are user-visible failures |

---

## 4. SMS — `sms.send`

| Tag | Values | Signal |
|-----|--------|--------|
| `status=success` | SMS delivered | Fast2SMS working |
| `status=failure` | SMS failed | Provider down, quota exceeded, invalid number |

Currently SMS is disabled in production (`clinicos.sms.enabled=false`). When enabled, this metric tracks Fast2SMS reliability.

---

## 5. HTTP Endpoints (auto — Spring Actuator)

**Metric:** `http.server.requests` — auto-instrumented, no custom code needed.

| Tag | Example values |
|-----|----------------|
| `method` | `GET`, `POST`, `PUT`, `DELETE` |
| `uri` | `/v1/sync/push`, `/v1/auth/otp/send`, `/v1/orgs/{orgId}/patients` |
| `status` | `200`, `400`, `401`, `404`, `500` |

Tracks request count, latency (avg, max), and status code for every endpoint.

---

## Admin Stats Response Example

`GET /v1/admin/stats` (requires JWT)

```json
{
  "data": {
    "syncEvents": {
      "total": 150,
      "applied": 145,
      "rejected": 5,
      "successRate": "96.67%",
      "idempotent": 2,
      "rejectionCodes": { "DUPLICATE_IGNORED": 3, "PROCESSING_ERROR": 1 },
      "byType": {
        "patient_added": { "applied": 50, "rejected": 1, "avgMs": 12.5, "maxMs": 45.0 },
        "call_now": { "applied": 40, "rejected": 0, "avgMs": 8.2, "maxMs": 22.0 },
        "visit_saved": { "applied": 30, "rejected": 0, "avgMs": 15.0, "maxMs": 60.0 }
      }
    },
    "queue": {
      "started": 5,
      "ended": 4,
      "autoCompleted": 1,
      "avgWaitTimeMins": 8.5,
      "maxWaitTimeMins": 22.0,
      "avgConsultationMins": 6.2,
      "maxConsultationMins": 18.0,
      "avgSessionMins": 180.0,
      "maxSessionMins": 240.0,
      "avgPatientsPerSession": 25.0,
      "maxPatientsPerSession": 40
    },
    "aiExtraction": {
      "totalRequests": 20,
      "success": 18,
      "errors": 0,
      "exhausted": 2,
      "retries": 3,
      "unexpectedErrors": 0,
      "malformed": 1,
      "successRate": "90.0%",
      "avgDurationMs": 1250,
      "maxDurationMs": 3400
    },
    "auth": {
      "otpSend": { "success": 25 },
      "otpVerify": {
        "success:new_user": 3,
        "success:returning_user": 20,
        "failure:invalid_otp": 2,
        "failure:expired": 1
      },
      "tokenRefresh": { "success": 100, "failure:token_expired": 5 },
      "logout": 8,
      "jwtRejected": { "failure:expired": 80, "failure:malformed": 2, "failure:user_not_found": 1 },
      "accessDenied": { "failure:insufficient_permission": 3 }
    },
    "sms": { "success": 25 },
    "http": {
      "endpoints": {
        "POST /v1/sync/push": {
          "totalRequests": 200, "avgMs": 43.7, "maxMs": 800.0,
          "4xx": 8, "5xx": 2,
          "byStatus": {
            "200": { "count": 190, "avgMs": 40.0, "maxMs": 100.0 },
            "400": { "count": 8, "avgMs": 15.0, "maxMs": 30.0 },
            "500": { "count": 2, "avgMs": 500.0, "maxMs": 800.0 }
          }
        },
        "POST /v1/auth/otp/send": {
          "totalRequests": 25, "avgMs": 180.0, "maxMs": 400.0,
          "byStatus": { "200": { "count": 25, "avgMs": 180.0, "maxMs": 400.0 } }
        },
        "GET /v1/orgs/{orgId}/patients": {
          "totalRequests": 80, "avgMs": 22.0, "maxMs": 95.0,
          "byStatus": { "200": { "count": 80, "avgMs": 22.0, "maxMs": 95.0 } }
        }
      }
    }
  }
}
```

---

## How to Use (Quick Reference)

### Check if everything is healthy
```bash
curl -s http://64.227.188.143:8080/v1/admin/stats \
  -H "Authorization: Bearer <JWT>" | python3 -m json.tool
```

### Check specific metric
```bash
# Sync event counts
curl -s http://64.227.188.143:8080/actuator/metrics/sync.events

# Filter by tag
curl -s "http://64.227.188.143:8080/actuator/metrics/sync.events?tag=status:rejected"

# Auth events
curl -s http://64.227.188.143:8080/actuator/metrics/auth.events
```

### Full Prometheus dump
```bash
curl -s http://64.227.188.143:8080/actuator/prometheus | grep "sync_events\|auth_events\|ai_extraction"
```

---

## Next Steps

### Phase 1: Deploy & Validate (This session)
- [ ] Build JAR with metrics changes
- [ ] Deploy to production server
- [ ] Verify `/actuator/prometheus` returns data
- [ ] Verify `/v1/admin/stats` returns correct structure
- [ ] Do a test sync push and confirm counters increment

### Phase 2: Queue Operations Metrics ✅ Done
All queue metrics implemented — derived from timestamps already in event handlers, zero DB queries.

| Metric | Where added | What it tracks |
|--------|------------|----------------|
| `queue.started` counter | `processPatientAdded`, `processStashImported` (new queue creation) | Queues started per day |
| `queue.ended` counter | `processQueueEnded` | Queues ended per day |
| `queue.auto_completed` counter | `processQueueEnded` (CALLED entries auto-completed) | Patients auto-completed on queue end |
| `queue.wait_time` timer | `processCallNow` (calledAt - registeredAt) | How long patients wait |
| `queue.consultation_time` timer | `processMarkComplete` (completedAt - calledAt) | How long consultations take |
| `queue.duration` timer | `processQueueEnded` (endedAt - startedAt) | Total clinic session length |
| `queue.patients_per_session` summary | `processQueueEnded` (count COMPLETED entries) | Patients seen per queue |

Also added: **Idempotent no-op tracking** — `processEvent()` returns boolean, rejection code `IDEMPOTENT` recorded for no-op paths (already paused, already ended, bill exists, etc.).

### Phase 3: Real-time Gauges (Future — needs scheduled task)
Add gauges that show current state (requires a `@Scheduled` task polling DB every 60s):

| Gauge | Query |
|-------|-------|
| `queue.active` | `SELECT COUNT(*) FROM queues WHERE status IN ('ACTIVE','PAUSED')` |
| `queue.waiting_patients` | `SELECT COUNT(*) FROM queue_entries WHERE state = 'WAITING'` |
| `queue.now_serving` | `SELECT COUNT(*) FROM queue_entries WHERE state = 'CALLED'` |

Trade-off: These hit DB every 60s. Low load, but not zero. Defer until needed.

### Phase 4: Prometheus + Grafana ✅ Done

Deployed on a separate monitoring server. Full Docker Compose stack with auto-provisioned Grafana dashboard.

- **Config files**: `monitoring/` directory in project root
- **Dashboard**: 18 panels, 26 queries covering auth, SMS, sync, AI, queue, HTTP, and JVM metrics
- **Retention**: 1 year / 2 GB cap (~75 MB/month actual usage)
- **Full documentation**: See [MONITORING.md](MONITORING.md) for line-by-line config explanation, deployment guide, and troubleshooting

### Phase 5: Alerting (Future)
When Prometheus is running, add alert rules:

| Alert | Condition | Why |
|-------|-----------|-----|
| Sync failure spike | `rate(sync_events{status="rejected"}[5m]) > 0.1` | Events failing faster than 1/min |
| AI extraction down | `rate(ai_extraction{status="exhausted"}[5m]) > 0` | Doctor losing transcriptions |
| OTP brute force | `rate(auth_events{reason="max_attempts"}[5m]) > 0.05` | Someone hammering OTP verify |
| SMS delivery failure | `rate(sms_send{status="failure"}[5m]) > 0` | OTP delivery broken |
| High 5xx rate | `rate(http_server_requests{status=~"5.."}[5m]) > 0.01` | Server errors |
