# ClinicOS Backend - Progress Tracker

## Project Overview

ClinicOS is a clinic queue management system for Indian healthcare clinics with:
- Queue management (token system)
- Patient registration & records
- Billing
- SMS notifications
- Multi-tenant SaaS
- Offline-first with event-sourced sync

---

## Current State: Auth Complete (~25% Complete)

| Aspect               | Status                                    |
|----------------------|-------------------------------------------|
| Project structure    | ✅ Maven + Spring Boot 4.0.2              |
| Java version         | ✅ 21                                     |
| Core dependencies    | ✅ JPA, Security, Validation, Web, Lombok |
| MySQL driver         | ✅ mysql-connector-j                      |
| JWT libraries        | ✅ jjwt 0.12.6                            |
| OpenAPI/Swagger      | ✅ springdoc-openapi 2.8.4                |
| Actuator             | ✅ spring-boot-starter-actuator           |
| application.properties | ✅ MySQL, JWT, Swagger configured        |
| JPA Entities         | ✅ 23 of 23                               |
| Repositories         | ✅ 23 repositories                        |
| Auth Service         | ✅ OTP, Login, Refresh, Logout            |
| Security config      | ✅ JWT filter, stateless auth             |

---

## Database Schema (23 Tables - Fully Designed)

| Group                    | Tables                                                                              |
|--------------------------|-------------------------------------------------------------------------------------|
| Auth & Users (4)         | users, otp_requests, devices, refresh_tokens                                        |
| Organizations & RBAC (6) | organizations, roles, permissions, role_permissions, org_members, org_member_roles  |
| Event Store (2)          | event_store, event_role_snapshot                                                    |
| Queue & Entries (3)      | queues, queue_entries, complaint_tags                                               |
| Patients & Visits (3)    | patients, visits, visit_images                                                      |
| Billing (3)              | bills, bill_items, bill_item_templates                                              |
| SMS (2)                  | sms_templates, sms_logs                                                             |

---

## Key Design Decisions

- **PK**: INT AUTO_INCREMENT (internal use)
- **External ID**: CHAR(36) uuid (client-generated for offline sync)
- **Soft delete**: deleted_at TIMESTAMP NULL
- **Status columns**: VARCHAR(20) (not ENUM - no migration needed)
- **Permissions**: resource:action format (e.g., queue:add_patient)

---

## SQL Scripts Location

```
~/Desktop/clinicos_db/
├── create_tables.sql    # 23 tables
├── drop_tables.sql      # Rollback
├── seed_data.sql        # Roles, permissions
└── reset_database.sql   # Full reset
```

---

## Implementation Checklist

### Phase 1: Project Setup ✅
- [x] 1. Add missing dependencies (MySQL, JWT, OpenAPI, Actuator)
- [x] 2. Configure application.properties (MySQL, JWT, server port)

### Phase 2: Data Layer ✅
- [x] 3. Create 23 JPA entities
  - [x] Auth & Users (4): users, otp_requests, devices, refresh_tokens
  - [x] Organizations & RBAC (6): organizations, roles, permissions, role_permissions, org_members, org_member_roles
  - [x] Event Store (2): event_store, event_role_snapshot
  - [x] Queue & Entries (3): queues, queue_entries, complaint_tags
  - [x] Patients & Visits (3): patients, visits, visit_images
  - [x] Billing (3): bills, bill_items, bill_item_templates
  - [x] SMS (2): sms_templates, sms_logs
- [x] 4. Create repositories for all entities

### Phase 3: Security ✅
- [x] 5. Configure JWT security
  - [x] JWT filter (JwtAuthenticationFilter)
  - [x] Security configuration (SecurityConfig)
  - [x] Token provider (JwtTokenProvider)
  - [x] CustomUserDetails & CustomUserDetailsService

### Phase 4: Auth Service ✅
- [x] 6. Auth service & controller
  - [x] POST /v1/auth/otp/send - Send OTP
  - [x] POST /v1/auth/otp/verify - Verify OTP & login
  - [x] POST /v1/auth/token/refresh - Refresh tokens
  - [x] POST /v1/auth/logout - Logout

### Phase 5: Scalability Patterns (Pending)
- [ ] 7. Cache Pattern - Redis for OTP/token validation
- [ ] 8. Event-Driven - Async SMS sending via message queue
- [ ] 9. Rate Limiting - Token bucket for OTP requests
- [ ] 10. Circuit Breaker - For external SMS provider calls

### Phase 6: Business Services (Pending)
- [ ] 11. Organization service
- [ ] 12. Queue service
- [ ] 13. Patient service
- [ ] 14. Visit service
- [ ] 15. Billing service
- [ ] 16. SMS service

---

## Progress Log

| Date       | Task                                      | Status |
|------------|-------------------------------------------|--------|
| 2026-02-19 | Added dependencies (MySQL, JWT, OpenAPI)  | Done   |
| 2026-02-19 | Created 23 JPA entities                   | Done   |
| 2026-02-19 | Created 23 repositories                   | Done   |
| 2026-02-19 | Implemented JWT security                  | Done   |
| 2026-02-19 | Implemented Auth service (OTP flow)       | Done   |
| 2026-02-19 | Aligned with API contract (/v1/ paths)    | Done   |

---

## Scalability Patterns (Checkpoint)

These patterns will be implemented to ensure production-readiness:

| Pattern         | Purpose                              | Technology      | Status  |
|-----------------|--------------------------------------|-----------------|---------|
| Cache           | OTP/token validation caching         | Redis           | Pending |
| Event-Driven    | Async SMS sending                    | RabbitMQ/Kafka  | Pending |
| Rate Limiting   | Prevent OTP abuse                    | Bucket4j        | Pending |
| Circuit Breaker | Handle SMS provider failures         | Resilience4j    | Pending |

---

## Notes

_Add any important notes or decisions here_
