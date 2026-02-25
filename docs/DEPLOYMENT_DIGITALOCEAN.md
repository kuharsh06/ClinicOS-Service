# ClinicOS — DigitalOcean Deployment Guide

> Complete step-by-step record of deploying ClinicOS backend to a DigitalOcean Droplet.
> Includes all commands, errors encountered, and resolutions.
> Server IP: `64.227.188.143` | Region: Bangalore (BLR1)

---

## 1. Create Droplet

1. Log into [DigitalOcean](https://cloud.digitalocean.com)
2. Create → Droplets
3. Settings used:
   - **OS:** Ubuntu 24.04 LTS
   - **Plan:** Basic $12/mo (1 vCPU, 2 GB RAM)
   - **Region:** Bangalore (BLR1)
   - **Authentication:** SSH Key

### Generate SSH Key (local machine)

```bash
ssh-keygen -t ed25519 -C "harshkumar@clinicos"
# Press Enter for default location (~/.ssh/id_ed25519)
# Press Enter for no passphrase

cat ~/.ssh/id_ed25519.pub
# Copy the output → paste into DigitalOcean SSH Key field
```

### Connect to Droplet

```bash
ssh root@64.227.188.143
```

---

## 2. Server Setup

### Update system

```bash
apt update && apt upgrade -y
```

### Install Java 21

```bash
apt install -y openjdk-21-jre-headless
java -version  # verify: openjdk 21.x
```

### Install MySQL 8

```bash
apt install -y mysql-server
systemctl start mysql
systemctl enable mysql
```

### Secure MySQL

```bash
mysql_secure_installation
# Answer security questions as prompted
```

### Create Database & User

```bash
mysql -u root -p
```

```sql
CREATE DATABASE clinicos;
CREATE USER 'clinicos'@'localhost' IDENTIFIED BY '<your-password>';
GRANT ALL PRIVILEGES ON clinicos.* TO 'clinicos'@'localhost';
FLUSH PRIVILEGES;
exit
```

**Error encountered:** Initially created user with placeholder `<strong-password>` as literal text.
**Resolution:** Dropped and recreated:
```sql
DROP USER 'clinicos'@'localhost';
CREATE USER 'clinicos'@'localhost' IDENTIFIED BY 'actual_password_here';
GRANT ALL PRIVILEGES ON clinicos.* TO 'clinicos'@'localhost';
FLUSH PRIVILEGES;
```

### Run Schema

Upload schema from local machine:
```bash
# Run from LOCAL machine, not server
scp /Users/harshkumar/Downloads/clinicos-service-main/sql/schema.sql root@64.227.188.143:/tmp/schema.sql
```

Run on server:
```bash
mysql -u clinicos -p clinicos < /tmp/schema.sql
```

---

## 3. Build & Deploy JAR

### Build locally

```bash
# From project root on local machine
./mvnw clean package -DskipTests -Dmaven.compiler.release=23
```

**Error encountered:** `release version 21 not supported` — local machine has Java 23, project targets Java 21.
**Resolution:** Added `-Dmaven.compiler.release=23` flag. The JAR runs fine on the server's Java 21.

### Create deployment directory

```bash
# Run from LOCAL machine
ssh root@64.227.188.143 "mkdir -p /opt/clinicos"
```

**Error encountered:** `scp: dest open "/opt/clinicos/": Failure` — directory didn't exist.
**Resolution:** Created directory first with `mkdir -p`.

### Upload JAR

```bash
# Run from LOCAL machine
scp target/clinicos-service-0.0.1-SNAPSHOT.jar root@64.227.188.143:/opt/clinicos/
```

### Upload & Edit application.properties

```bash
# Run from LOCAL machine
scp src/main/resources/application.properties root@64.227.188.143:/opt/clinicos/
```

Edit on server:
```bash
nano /opt/clinicos/application.properties
```

Update these values:
```properties
spring.datasource.url=jdbc:mysql://localhost:3306/clinicos?useSSL=false&serverTimezone=Asia/Kolkata&allowPublicKeyRetrieval=true
spring.datasource.username=clinicos
spring.datasource.password=<your-actual-password>
app.jwt.secret=<generate-a-strong-256-bit-secret>
```

Save: Ctrl+O → Enter → Ctrl+X

---

## 4. Create Systemd Service

```bash
cat > /etc/systemd/system/clinicos.service << 'EOF'
[Unit]
Description=ClinicOS Backend
After=mysql.service

[Service]
User=root
WorkingDirectory=/opt/clinicos
ExecStart=/usr/bin/java -jar -Xmx512m clinicos-service-0.0.1-SNAPSHOT.jar --spring.config.location=file:/opt/clinicos/application.properties
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable clinicos
systemctl start clinicos
```

### Verify

```bash
systemctl status clinicos
journalctl -u clinicos -f   # live logs
```

**Error encountered:** `Access denied for user 'root'@'localhost'` — application.properties still had `root` as username instead of `clinicos`.
**Resolution:** Edited `/opt/clinicos/application.properties` with `nano`, changed username to `clinicos` and set correct password, then `systemctl restart clinicos`.

---

## 5. Firewall

```bash
ufw allow 8080
ufw allow 22     # SSH (should already be open)
```

---

## 6. Nginx (Optional — for domain + SSL)

### Install

```bash
apt install -y nginx
```

### Configure

```bash
cat > /etc/nginx/sites-available/clinicos << 'EOF'
server {
    listen 80;
    server_name api.clinicos.in;

    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
EOF

ln -s /etc/nginx/sites-available/clinicos /etc/nginx/sites-enabled/
nginx -t
systemctl restart nginx
```

**Skipped for now** — domain `clinicos.in` not yet purchased.

### Disabled Nginx proxy (using direct IP access for now)

```bash
rm /etc/nginx/sites-enabled/clinicos
systemctl stop nginx
systemctl disable nginx
```

### SSL (when domain is ready)

```bash
apt install -y certbot python3-certbot-nginx
certbot --nginx -d api.clinicos.in
```

**Error encountered:** `NXDOMAIN looking up A for api.clinicos.in` — domain DNS not configured.
**Resolution:** Skipped SSL. Will configure when domain is purchased and DNS A record points to `64.227.188.143`.

---

## 7. Verify Deployment

```bash
curl http://64.227.188.143:8080/actuator/health
```

**Response:**
```json
{"groups":["liveness","readiness"],"status":"UP"}
```

App is live and running.

---

## 8. Useful Commands

| Command | What it does |
|---------|-------------|
| `systemctl start clinicos` | Start the app |
| `systemctl stop clinicos` | Stop the app |
| `systemctl restart clinicos` | Restart after new JAR deploy |
| `systemctl status clinicos` | Check if running |
| `journalctl -u clinicos -f` | Live tail logs |
| `journalctl -u clinicos --no-pager \| tail -100` | Last 100 log lines |

### Redeploy new version

From local machine:
```bash
# 1. Build
./mvnw clean package -DskipTests -Dmaven.compiler.release=23

# 2. Upload
scp target/clinicos-service-0.0.1-SNAPSHOT.jar root@64.227.188.143:/opt/clinicos/

# 3. Restart
ssh root@64.227.188.143 "systemctl restart clinicos"
```

---

## Server Details

| Item | Value |
|------|-------|
| **IP** | 64.227.188.143 |
| **OS** | Ubuntu 24.04 LTS |
| **Java** | 21.0.10 |
| **MySQL** | 8.x |
| **App Port** | 8080 |
| **Base URL** | `http://64.227.188.143:8080` |
| **Health Check** | `http://64.227.188.143:8080/actuator/health` |
| **OTP (dev)** | Always `123456` |
| **DB Name** | clinicos |
| **DB User** | clinicos |
| **App Location** | `/opt/clinicos/` |
| **Config** | `/opt/clinicos/application.properties` |
| **Service** | `/etc/systemd/system/clinicos.service` |
| **Logs** | `journalctl -u clinicos` |
