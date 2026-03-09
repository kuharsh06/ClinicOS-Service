# ClinicOS — Staging Environment

> Isolated testing environment. SMS disabled, fixed OTP, separate database.
> Use this for all development, testing, and frontend integration.

---

## Current Status: STOPPED

Staging is currently **stopped and disabled** to free memory on the 2GB droplet (only production runs).

- Service: `clinicos-staging` — stopped and disabled (won't auto-start on reboot)
- Database: `clinicos_test` — **dropped** (must be recreated before starting)
- Config files still on server: `application-staging.properties`, `clinicos-staging.service`, nginx server block — all harmless while stopped

### To bring staging back up

```bash
ssh root@64.227.188.143

# 1. Recreate the database
mysql -u root -e "CREATE DATABASE clinicos_test;"
sed 's/USE clinicos;/USE clinicos_test;/' /opt/clinicos/schema.sql | mysql -u root

# 2. Re-enable and start the service
systemctl enable clinicos-staging
systemctl start clinicos-staging

# 3. Verify (wait ~30-40s for JVM startup)
curl https://staging.clinicos.codingrippler.com/actuator/health
```

---

## Access

| Item | Value |
|------|-------|
| **URL** | `https://staging.clinicos.codingrippler.com` |
| **Direct IP** | `http://64.227.188.143:8081` |
| **Health Check** | `https://staging.clinicos.codingrippler.com/actuator/health` |
| **Swagger** | `https://staging.clinicos.codingrippler.com/swagger-ui.html` |
| **OTP** | Always `123456` (returned in `devOtp` field) |

---

## Quick Start (Test Login)

```bash
# 1. Send OTP — no SMS sent, devOtp returned in response
curl -s -X POST https://staging.clinicos.codingrippler.com/v1/auth/otp/send \
  -H "Content-Type: application/json" \
  -d '{"phone":"0000000000","countryCode":"+91"}'

# Response: { "devOtp": "123456", "requestId": "..." }

# 2. Verify with 123456
curl -s -X POST https://staging.clinicos.codingrippler.com/v1/auth/otp/verify \
  -H "Content-Type: application/json" \
  -d '{
    "requestId": "<requestId from step 1>",
    "otp": "123456",
    "deviceId": "test-device",
    "deviceInfo": {"model":"CLI","osVersion":"1.0","appVersion":"1.0","platform":"cli"}
  }'

# Response: { "accessToken": "...", "user": { ... } }
```

---

## Server

| Item | Value |
|------|-------|
| **IP** | `64.227.188.143` (shared with production) |
| **OS** | Ubuntu 24.04 LTS |
| **Java** | OpenJDK 21 |
| **MySQL** | 8.x |

---

## Application

| Item | Value |
|------|-------|
| **Port** | `8081` |
| **Database** | `clinicos_test` |
| **JAR** | `/opt/clinicos/clinicos-service-0.0.1-SNAPSHOT.jar` (shared with production) |
| **Config** | `/opt/clinicos/application-staging.properties` |
| **Service** | `clinicos-staging.service` |
| **Uploads** | `/opt/clinicos/uploads-staging/` |
| **JVM Memory** | `-Xmx512m` |
| **Schema** | `/opt/clinicos/schema.sql` (shared) |

---

## Key Configuration

```properties
server.port=8081
spring.datasource.url=jdbc:mysql://localhost:3306/clinicos_test?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true
spring.datasource.username=clinicos
spring.datasource.password=<REDACTED>

# SMS — DISABLED (OTP always 123456, no SMS sent)
clinicos.sms.enabled=false
clinicos.sms.provider=fast2sms

# AI Extraction — ENABLED (same Gemini API key as production)
clinicos.ai.enabled=true
clinicos.ai.provider=gemini
clinicos.ai.model=gemini-2.0-flash

# JWT (same secret as production — tokens work across both)
app.jwt.secret=<REDACTED>
app.jwt.access-token-expiration=86400000      # 24 hours
app.jwt.refresh-token-expiration=2592000000   # 30 days

# Storage
clinicos.storage.type=local
clinicos.storage.local.base-path=/opt/clinicos/uploads-staging
clinicos.storage.local.base-url=https://staging.clinicos.codingrippler.com/uploads

# Hibernate — VALIDATE only
spring.jpa.hibernate.ddl-auto=validate
```

---

## Nginx

- Domain: `staging.clinicos.codingrippler.com`
- SSL: Let's Encrypt (auto-renews via Certbot, expires 2026-06-07)
- Config: `/etc/nginx/sites-available/clinicos` (same file, separate server block)
- Proxies `443 → localhost:8081`
- Serves uploads from `/opt/clinicos/uploads-staging/` at `/uploads/`
- DNS: A record `staging.clinicos.codingrippler.com → 64.227.188.143`

---

## Service Management

```bash
# SSH into server
ssh root@64.227.188.143

# Service commands
systemctl start clinicos-staging
systemctl stop clinicos-staging
systemctl restart clinicos-staging
systemctl status clinicos-staging

# Logs
journalctl -u clinicos-staging -f                    # live tail
journalctl -u clinicos-staging --no-pager | tail -100  # last 100 lines
```

---

## Deploy to Staging

```bash
# 1. Build locally
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./mvnw clean package -DskipTests

# 2. Upload JAR
scp target/clinicos-service-0.0.1-SNAPSHOT.jar root@64.227.188.143:/opt/clinicos/

# 3. Restart STAGING ONLY (test here first!)
ssh root@64.227.188.143 "systemctl restart clinicos-staging"

# 4. Verify staging
curl https://staging.clinicos.codingrippler.com/actuator/health

# 5. If all good → deploy to production
ssh root@64.227.188.143 "systemctl restart clinicos"
```

---

## Reset Staging Database

When you want a clean slate:

```bash
ssh root@64.227.188.143

# Drop and recreate
mysql -u root -e "DROP DATABASE clinicos_test; CREATE DATABASE clinicos_test;"

# Reload schema (persistent copy at /opt/clinicos/schema.sql)
sed 's/USE clinicos;/USE clinicos_test;/' /opt/clinicos/schema.sql | mysql -u root

# Restart staging
systemctl restart clinicos-staging
```

---

## Troubleshooting

### Staging won't start
```bash
journalctl -u clinicos-staging --no-pager | tail -50
```
Common issues: port 8081 already in use, database connection failed, out of memory (two JVMs on 2GB RAM — ~1.6GB used normally, ~370MB available).

### Check both services are running
```bash
ssh root@64.227.188.143 "systemctl status clinicos --no-pager | head -5 && echo '---' && systemctl status clinicos-staging --no-pager | head -5"
```

### 502 Bad Gateway after restart
The staging JVM takes ~30-40 seconds to start on a 2GB droplet. Wait and retry.

### Save memory — stop staging when not testing
Both JVMs + MySQL use ~1.6GB of the 2GB droplet. Stop staging when not in use:
```bash
ssh root@64.227.188.143 "systemctl stop clinicos-staging"   # frees ~500MB
ssh root@64.227.188.143 "systemctl start clinicos-staging"  # start when needed
```

---

## What's Different from Production

| Setting | Staging | Production |
|---------|---------|-----------|
| Port | `8081` | `8080` |
| Database | `clinicos_test` | `clinicos` |
| SMS | **Disabled** | Enabled (real OTP) |
| OTP | `123456` (in `devOtp`) | Random 6-digit |
| URL | `staging.clinicos.codingrippler.com` | `clinicos.codingrippler.com` |
| Upload path | `/opt/clinicos/uploads-staging/` | `/opt/clinicos/uploads/` |
| Upload URL | `staging...com/uploads` | `clinicos...com/uploads` |
| Data | Test data (safe to wipe) | **Real patient data** |
| Safe to break? | Yes | **NO** |

---

## Frontend Configuration

```typescript
const API_BASE_URL = __DEV__
  ? "https://staging.clinicos.codingrippler.com"
  : "https://clinicos.codingrippler.com"
```

---

## Known Limitations & Future Improvements

### Current: Shared JAR
Both environments use the same JAR file (`clinicos-service-0.0.1-SNAPSHOT.jar`). Uploading a new JAR affects both when restarted.

**Future fix — Separate JARs:**
```bash
# Production JAR
/opt/clinicos/clinicos-service-prod.jar

# Staging JAR
/opt/clinicos/clinicos-service-staging.jar
```

Update systemd services:
```ini
# clinicos.service
ExecStart=/usr/bin/java -jar -Xmx512m clinicos-service-prod.jar ...

# clinicos-staging.service
ExecStart=/usr/bin/java -jar -Xmx512m clinicos-service-staging.jar ...
```

Deploy script:
```bash
# Deploy to staging only
scp target/*.jar root@64.227.188.143:/opt/clinicos/clinicos-service-staging.jar
ssh root@64.227.188.143 "systemctl restart clinicos-staging"

# Promote staging JAR to production (after testing)
ssh root@64.227.188.143 "cp /opt/clinicos/clinicos-service-staging.jar /opt/clinicos/clinicos-service-prod.jar && systemctl restart clinicos"
```

### Current: Shared JWT Secret
Tokens from staging work on production (same secret). This is convenient but could be a security concern.

**Future fix — Separate JWT secrets:**
```properties
# production application.properties
app.jwt.secret=<PRODUCTION_SECRET>

# staging application-staging.properties
app.jwt.secret=<STAGING_SECRET>
```

### Current: Shared AI API Key
Both environments use the same Gemini API key. AI extraction on staging consumes the same quota.

**Future fix:** Use a separate API key for staging, or disable AI on staging if not needed:
```properties
clinicos.ai.enabled=false  # or use a separate key
```

### Future: CI/CD Pipeline
Automate the deploy process:
1. Push to `main` → auto-deploy to staging
2. Manual approve → promote to production
3. GitHub Actions or similar

### Future: Separate Server
When the app grows beyond what a single droplet can handle:
- Move staging to a separate droplet
- Or use Docker containers for isolation
- Or migrate to managed services (DigitalOcean App Platform, AWS ECS)
