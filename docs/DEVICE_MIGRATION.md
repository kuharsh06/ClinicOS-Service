# Device Migration Guide

## Prerequisites on New Device
- Git
- Java 17+ (for building)
- SSH client

## 1. Clone the Repo

```bash
git clone https://github.com/kuharsh06/ClinicOS-Service.git
cd ClinicOS-Service
```

Authenticate with GitHub via HTTPS (personal access token) or SSH key.

## 2. SSH Access to Server

Generate SSH key on new device (if needed):
```bash
ssh-keygen -t ed25519
cat ~/.ssh/id_ed25519.pub
```

Add the public key to the server:
```bash
ssh root@64.227.188.143 "echo 'YOUR_NEW_PUBLIC_KEY' >> ~/.ssh/authorized_keys"
```

Or add via DigitalOcean dashboard: Settings > Security > SSH Keys.

Test:
```bash
ssh root@64.227.188.143
```

## 3. Build & Deploy

```bash
./mvnw clean package -DskipTests
scp target/clinicos-service-0.0.1-SNAPSHOT.jar root@64.227.188.143:/opt/clinicos/clinicos-service-0.0.1-SNAPSHOT.jar
ssh root@64.227.188.143 "systemctl restart clinicos"
```

## 4. Server Details

| Item | Value |
|------|-------|
| Server IP | `64.227.188.143` |
| Domain | `clinicos.codingrippler.com` |
| SSH user | `root` |
| App directory | `/opt/clinicos/` |
| JAR file | `/opt/clinicos/clinicos-service-0.0.1-SNAPSHOT.jar` |
| Config | `/opt/clinicos/application.properties` |
| Uploads | `/opt/clinicos/uploads/` |
| Service name | `clinicos` |
| DB | MySQL 8.0, localhost:3306, database: `clinicos` |
| Java | OpenJDK 21 |

## 5. Useful Commands

```bash
# Check service status
ssh root@64.227.188.143 "systemctl status clinicos"

# View logs
ssh root@64.227.188.143 "journalctl -u clinicos --no-pager -n 50"

# Edit server config (API keys, storage, etc.)
ssh root@64.227.188.143 "nano /opt/clinicos/application.properties"

# Restart after config change
ssh root@64.227.188.143 "systemctl restart clinicos"
```

## 6. Sensitive Config (on server only, NOT in git)

The server's `/opt/clinicos/application.properties` has real credentials:
- `clinicos.ai.api-key` — Gemini API key
- `spring.datasource.password` — MySQL password
- `app.jwt.secret` — JWT signing secret

These are never committed to git. The repo has placeholder values.
