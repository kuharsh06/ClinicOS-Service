# ClinicOS — Production Environment

> Live production environment serving real clinics and patients.
> **DO NOT** use for testing. Any data created here affects real users.

---

## Access

| Item | Value |
|------|-------|
| **URL** | `https://clinicos.codingrippler.com` |
| **Direct IP** | `http://64.227.188.143:8080` |
| **Health Check** | `https://clinicos.codingrippler.com/actuator/health` |
| **Swagger** | `https://clinicos.codingrippler.com/swagger-ui.html` |

---

## Server

| Item | Value |
|------|-------|
| **IP** | `64.227.188.143` |
| **OS** | Ubuntu 24.04 LTS |
| **Region** | DigitalOcean Bangalore (BLR1) |
| **Plan** | Basic $12/mo (1 vCPU, 2 GB RAM) |
| **Java** | OpenJDK 21 |
| **MySQL** | 8.x |

---

## Application

| Item | Value |
|------|-------|
| **Port** | `8080` |
| **Database** | `clinicos` |
| **JAR** | `/opt/clinicos/clinicos-service-0.0.1-SNAPSHOT.jar` |
| **Config** | `/opt/clinicos/application.properties` |
| **Service** | `clinicos.service` |
| **Uploads** | `/opt/clinicos/uploads/` |
| **JVM Memory** | `-Xmx512m` |
| **Schema** | `/opt/clinicos/schema.sql` |

---

## Key Configuration

```properties
server.port=8080
spring.datasource.url=jdbc:mysql://localhost:3306/clinicos?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true
spring.datasource.username=clinicos
spring.datasource.password=<REDACTED>

# SMS — ENABLED (real OTP sent via Fast2SMS)
clinicos.sms.enabled=true
clinicos.sms.provider=fast2sms
clinicos.sms.sender-id=CODRIP

# AI Extraction — ENABLED
clinicos.ai.enabled=true
clinicos.ai.provider=gemini
clinicos.ai.model=gemini-2.0-flash

# JWT
app.jwt.secret=<REDACTED>
app.jwt.access-token-expiration=86400000      # 24 hours
app.jwt.refresh-token-expiration=2592000000   # 30 days

# Storage
clinicos.storage.type=local
clinicos.storage.local.base-path=/opt/clinicos/uploads
clinicos.storage.local.base-url=https://clinicos.codingrippler.com/uploads

# Hibernate — VALIDATE only (no auto DDL)
spring.jpa.hibernate.ddl-auto=validate
```

---

## Nginx

- Domain: `clinicos.codingrippler.com`
- SSL: Let's Encrypt (auto-renews via Certbot)
- Config: `/etc/nginx/sites-available/clinicos`
- Proxies `443 → localhost:8080`
- Serves uploads from `/opt/clinicos/uploads/` at `/uploads/`

---

## Service Management

```bash
# SSH into server
ssh root@64.227.188.143

# Service commands
systemctl start clinicos
systemctl stop clinicos
systemctl restart clinicos
systemctl status clinicos

# Logs
journalctl -u clinicos -f                    # live tail
journalctl -u clinicos --no-pager | tail -100  # last 100 lines
```

---

## Deploy

```bash
# 1. Build locally
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./mvnw clean package -DskipTests

# 2. Upload JAR
scp target/clinicos-service-0.0.1-SNAPSHOT.jar root@64.227.188.143:/opt/clinicos/

# 3. Restart
ssh root@64.227.188.143 "systemctl restart clinicos"

# 4. Verify
curl https://clinicos.codingrippler.com/actuator/health
```

> **IMPORTANT:** Always deploy and test on staging first before production.
> See [ENV_STAGING.md](ENV_STAGING.md) for staging deploy process.

---

## Monitoring

- Prometheus metrics: `/actuator/prometheus`
- Grafana dashboard: Not running yet. See [monitoring/docker-compose.yml](../monitoring/docker-compose.yml) to set up.

---

## Troubleshooting

### App won't start
```bash
journalctl -u clinicos --no-pager | tail -50
```
Common issues: MySQL not running, config error, out of memory.

### Check both services are running
```bash
ssh root@64.227.188.143 "systemctl status clinicos --no-pager | head -5 && echo '---' && systemctl status clinicos-staging --no-pager | head -5"
```

### SSL certificate renewal
Certbot auto-renews. To manually check/renew:
```bash
ssh root@64.227.188.143 "certbot renew --dry-run"
```

---

## What's Different from Staging

| Setting | Production | Staging |
|---------|-----------|---------|
| Port | `8080` | `8081` |
| Database | `clinicos` | `clinicos_test` |
| SMS | **Enabled** (real OTP) | Disabled (`123456`) |
| OTP response | `devOtp: null` | `devOtp: "123456"` |
| URL | `clinicos.codingrippler.com` | `staging.clinicos.codingrippler.com` |
| Upload URL | `clinicos.codingrippler.com/uploads` | `staging.clinicos.codingrippler.com/uploads` |
| Data | **Real patient data** | Test data only |
