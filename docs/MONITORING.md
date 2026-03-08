# Monitoring Stack — Prometheus + Grafana

---

## Overview

The ClinicOS app exports metrics at `/actuator/prometheus`. These are in-memory snapshots with no history. To get historical time-series data (trends over days/months/years), we deploy Prometheus + Grafana on a **separate server**.

```
App Server (64.227.188.143)              Monitoring Server (<monitoring-ip>)
┌─────────────────────────┐              ┌──────────────────────────────────┐
│  Spring Boot App :8080  │   scrape     │  Docker                         │
│                         │◄─every 15s───│  ┌────────────┐                 │
│  /actuator/prometheus   │              │  │ Prometheus  │ :9090           │
│  (live metric snapshot) │              │  │ (stores on  │                 │
│                         │              │  │  disk, 1yr) │                 │
└─────────────────────────┘              │  └──────┬─────┘                 │
                                         │         │ query                 │
                                         │  ┌──────▼─────┐                 │
                                         │  │  Grafana    │ :3000           │
                                         │  │ (dashboards │                 │
                                         │  │  + alerts)  │                 │
                                         │  └────────────┘                 │
                                         └──────────────────────────────────┘
```

**Why a separate server?**
- App server is 1 vCPU / 2 GB — Prometheus + Grafana would eat ~200-300 MB
- Monitoring should survive even if the app server crashes
- A $6/mo droplet (1 vCPU, 1 GB) is more than enough

---

## File Structure

```
monitoring/
├── docker-compose.yml                              # Defines both containers
├── prometheus.yml                                  # Prometheus scrape config
└── grafana/
    ├── provisioning/
    │   ├── datasources/
    │   │   └── prometheus.yml                      # Auto-connects Grafana → Prometheus
    │   └── dashboards/
    │       └── dashboards.yml                      # Tells Grafana where to find dashboard JSON
    └── dashboards/
        └── clinicos-overview.json                  # Pre-built dashboard (14 panels, 20 queries)
```

---

## docker-compose.yml — Line by Line

```yaml
services:
```
> Top-level key. Defines the containers to run.

### Prometheus

```yaml
  prometheus:
    image: prom/prometheus:latest
```
> Official Prometheus Docker image. `latest` pulls the most recent stable version.

```yaml
    container_name: clinicos-prometheus
```
> Fixed name so we can reference it easily (`docker logs clinicos-prometheus`).

```yaml
    restart: unless-stopped
```
> Auto-restart on crash or server reboot. Only stops if you explicitly `docker stop` it.

```yaml
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml:ro
```
> Mounts our local `prometheus.yml` config into the container at the path Prometheus expects.
> `:ro` = read-only — the container can't modify our config file.

```yaml
      - prometheus_data:/prometheus
```
> Named Docker volume for persistent storage. Prometheus writes its time-series database here.
> **This is critical** — without this, all historical data is lost on container restart.
> Data lives at `/var/lib/docker/volumes/monitoring_prometheus_data/` on the host.

```yaml
    ports:
      - "9090:9090"
```
> Exposes Prometheus UI at `http://<server-ip>:9090`.
> Used for: checking targets status, running ad-hoc PromQL queries, debugging scrape issues.

```yaml
    command:
      - --config.file=/etc/prometheus/prometheus.yml
```
> Tells Prometheus where to find its config (matches the volume mount above).

```yaml
      - --storage.tsdb.retention.time=1y
```
> Keep data for 1 year. After 1 year, oldest data is automatically deleted.
> Change to `30d`, `90d`, etc. based on needs.

```yaml
      - --storage.tsdb.retention.size=2GB
```
> Safety cap — if data exceeds 2 GB before 1 year, oldest data is pruned.
> With our ~470 metrics scraped every 15s, we use ~75 MB/month (~900 MB/year).
> The 2 GB cap is a safety net, not a limit we'll hit in practice.

```yaml
      - --web.enable-lifecycle
```
> Enables `POST /-/reload` API to reload config without restarting the container.
> Useful when updating `prometheus.yml` — just `curl -X POST http://localhost:9090/-/reload`.

### Grafana

```yaml
  grafana:
    image: grafana/grafana:latest
```
> Official Grafana Docker image.

```yaml
    container_name: clinicos-grafana
```
> Fixed name for easy reference.

```yaml
    restart: unless-stopped
```
> Auto-restart on crash or reboot.

```yaml
    volumes:
      - grafana_data:/var/lib/grafana
```
> Persistent volume for Grafana's internal database (users, dashboard edits, preferences).
> Without this, any dashboard changes made in the UI are lost on restart.

```yaml
      - ./grafana/provisioning:/etc/grafana/provisioning:ro
```
> Auto-configuration files — Grafana reads these on startup to:
> 1. Connect to Prometheus as a data source (no manual setup needed)
> 2. Load dashboard JSON files automatically
> `:ro` = read-only.

```yaml
      - ./grafana/dashboards:/var/lib/grafana/dashboards:ro
```
> The actual dashboard JSON files. Grafana's provisioning config (above) points to this path.
> Any `.json` file placed here becomes a dashboard on startup.

```yaml
    ports:
      - "3000:3000"
```
> Grafana UI at `http://<server-ip>:3000`.

```yaml
    environment:
      - GF_SECURITY_ADMIN_USER=admin
```
> Default admin username. Change if desired.

```yaml
      - GF_SECURITY_ADMIN_PASSWORD=clinicos2024
```
> Default admin password. **Change this in production.**
> After first login you can change it in the Grafana UI.

```yaml
      - GF_USERS_ALLOW_SIGN_UP=false
```
> Disables the public sign-up page. Only admin can create users.

```yaml
    depends_on:
      - prometheus
```
> Grafana starts after Prometheus. Ensures the data source is available when Grafana boots.

### Volumes

```yaml
volumes:
  prometheus_data:
  grafana_data:
```
> Named Docker volumes. Docker manages these automatically.
> Data persists across container restarts and `docker compose down`.
> **Only `docker compose down -v` deletes volumes** (the `-v` flag) — never use `-v` unless you want to wipe all data.

---

## prometheus.yml — Line by Line

```yaml
global:
  scrape_interval: 15s
```
> Default interval for all scrape jobs. Every 15 seconds, Prometheus fetches metrics.
> 15s is the industry standard — frequent enough for near-real-time, light enough to not burden the app.

```yaml
  evaluation_interval: 15s
```
> How often Prometheus evaluates alerting rules (if any are defined).
> Not currently used but set to match scrape interval.

```yaml
scrape_configs:
  - job_name: clinicos
```
> A scrape job named "clinicos". This name appears in the Prometheus UI under Targets.

```yaml
    metrics_path: /actuator/prometheus
```
> The URL path to scrape. Our Spring Boot app exposes Prometheus metrics here.
> Default would be `/metrics` — we override because Spring Actuator uses `/actuator/prometheus`.

```yaml
    scrape_interval: 15s
```
> Per-job override (same as global here). Can be changed per-job if needed.

```yaml
    static_configs:
      - targets: ['64.227.188.143:8080']
```
> The app server's IP and port. Prometheus will call:
> `GET http://64.227.188.143:8080/actuator/prometheus` every 15 seconds.
>
> **If app server IP changes**, update this line and run:
> `curl -X POST http://localhost:9090/-/reload` (or restart the container).

```yaml
        labels:
          app: clinicos-service
          env: production
```
> Custom labels added to every metric scraped from this target.
> Useful for filtering in Grafana if you later add staging/dev targets.

---

## Grafana Provisioning — datasources/prometheus.yml

This file auto-connects Grafana to Prometheus on first boot. No manual UI setup needed.

```yaml
apiVersion: 1
```
> Grafana provisioning format version.

```yaml
datasources:
  - name: Prometheus
```
> Display name in Grafana UI.

```yaml
    type: prometheus
```
> Data source type. Grafana has built-in Prometheus support.

```yaml
    access: proxy
```
> Grafana's backend proxies requests to Prometheus.
> Alternative `direct` would make the browser call Prometheus directly (not what we want).

```yaml
    url: http://prometheus:9090
```
> Internal Docker network URL. `prometheus` resolves to the Prometheus container
> because Docker Compose creates a shared network for all services.
> **This is NOT the external IP** — it's container-to-container communication.

```yaml
    isDefault: true
```
> Makes this the default data source for all new panels.

```yaml
    editable: false
```
> Prevents accidental changes to the data source via the Grafana UI.

---

## Grafana Provisioning — dashboards/dashboards.yml

Tells Grafana to auto-load dashboard JSON files from disk.

```yaml
apiVersion: 1

providers:
  - name: ClinicOS
```
> Provider name (shown in Grafana UI).

```yaml
    orgId: 1
```
> Default Grafana organization.

```yaml
    folder: ClinicOS
```
> Dashboard folder name in Grafana. All dashboards appear under "ClinicOS" folder.

```yaml
    type: file
```
> Load dashboards from local files (not API).

```yaml
    disableDeletion: false
```
> Allows deleting provisioned dashboards from the UI.

```yaml
    editable: true
```
> Allows editing the dashboard in the UI. Edits persist in `grafana_data` volume.
> The source JSON file is not modified (it's mounted `:ro`).

```yaml
    options:
      path: /var/lib/grafana/dashboards
```
> Container path where dashboard JSONs are mounted (matches the volume in docker-compose.yml).

```yaml
      foldersFromFilesStructure: false
```
> Don't create subfolders based on directory structure. All JSONs go into the "ClinicOS" folder.

---

## Dashboard Panels — clinicos-overview.json

19 panels organized into 6 collapsible sections. Volume panels use `increase()` with stacked bars. Reliability panels use `rate()` ratios with threshold background zones (green >95%, yellow 90-95%, red <90%).

### Health Overview
| Panel | Type | What it shows |
|-------|------|---------------|
| Service Reliability | timeseries | Auth, AI, Sync, API success % over time with threshold zones |
| Queue Sessions | stat | Queues started, ended, and auto-completed counts |

### Authentication & SMS
| Panel | Type | What it shows |
|-------|------|---------------|
| OTP Send | timeseries (bars) | Success vs failure by reason |
| OTP Verify | timeseries (bars) | Success vs failure by reason |
| SMS Delivery | timeseries (bars) | SMS send success vs failure |
| JWT Rejections by Reason | timeseries (bars) | Rejected tokens (malformed, expired, not_access_token) |

### API Performance
| Panel | Type | What it shows |
|-------|------|---------------|
| API Traffic by Endpoint | timeseries (bars) | Request volume per endpoint |
| API Status Codes | timeseries (bars) | 2xx (green) vs 4xx (yellow) vs 5xx (red) |
| API Response Time | timeseries | Average and max latency per endpoint |
| API Reliability by Endpoint | timeseries | Per-endpoint success rate % with threshold zones |

### AI & Sync
| Panel | Type | What it shows |
|-------|------|---------------|
| AI Extraction Volume | timeseries (bars) | Success / Error / Exhausted counts |
| AI Processing Time | timeseries | Average extraction duration |
| Sync Volume by Type | timeseries (bars) | Event volume by type and status |

### Queue Operations
| Panel | Type | What it shows |
|-------|------|---------------|
| Patient Wait vs Consultation | timeseries | Average wait time and consultation time |
| Queue Session Length | timeseries | Average total session duration |
| Patients per Session | timeseries | Average and max patients seen |

### System
| Panel | Type | What it shows |
|-------|------|---------------|
| JVM Heap Memory | timeseries | Heap used vs max (full width) |

---

## Deployment Guide — Step by Step

### Prerequisites
- A DigitalOcean account (or any cloud provider)
- SSH key configured locally (`~/.ssh/id_ed25519.pub` or `~/.ssh/id_rsa.pub`)

### Step 1: Create a DigitalOcean Droplet

1. Go to [cloud.digitalocean.com](https://cloud.digitalocean.com) → **Create > Droplets**
2. **Region**: Bangalore (BLR1) — same region as app server for low latency
3. **Image**: Ubuntu 24.04 LTS
4. **Size**: Basic $6/mo (1 vCPU, 1 GB RAM)
5. **Authentication**: SSH Key
   - If your key isn't listed, click **New SSH Key**
   - On your Mac, run: `cat ~/.ssh/id_ed25519.pub` (or `cat ~/.ssh/id_rsa.pub`)
   - Paste the output, name it, click **Add SSH Key**
6. **Hostname**: `clinicos-monitoring`
7. Click **Create Droplet** and note the IP address

### Step 2: Install Docker

```bash
ssh root@<monitoring-ip>
curl -fsSL https://get.docker.com | sh
docker --version       # verify: Docker version 27.x.x
docker compose version # verify: Docker Compose version v2.x.x
```

### Step 3: Create directory and copy files

From your **local machine**:

```bash
# Create target directory on server
ssh root@<monitoring-ip> "mkdir -p /opt/clinicos/monitoring"

# Copy monitoring files
scp -r ~/ClinicOS-Service/monitoring/ root@<monitoring-ip>:/opt/clinicos/monitoring/
```

**Note:** scp copies `monitoring/` inside `monitoring/`, creating `/opt/clinicos/monitoring/monitoring/`. Fix this:

```bash
ssh root@<monitoring-ip>
mv /opt/clinicos/monitoring/monitoring/* /opt/clinicos/monitoring/
mv /opt/clinicos/monitoring/monitoring/.* /opt/clinicos/monitoring/ 2>/dev/null
rmdir /opt/clinicos/monitoring/monitoring
```

Or use this scp command instead (copies contents, not the folder):

```bash
scp -r ~/ClinicOS-Service/monitoring/* root@<monitoring-ip>:/opt/clinicos/monitoring/
```

### Step 4: Start the stack

```bash
ssh root@<monitoring-ip>
cd /opt/clinicos/monitoring
docker compose up -d
```

Wait ~30 seconds for containers to pull images and start.

### Step 5: Verify

```bash
# Check containers are running
docker ps
# Expected: clinicos-prometheus (Up), clinicos-grafana (Up)

# Check Prometheus can reach the app
curl -s http://64.227.188.143:8080/actuator/prometheus | head -3
# Expected: metric lines starting with "# HELP"

# Check Prometheus targets API
curl -s http://localhost:9090/api/v1/targets | python3 -m json.tool | grep health
# Expected: "health": "up"
```

### Step 6: Open in browser

1. **Prometheus**: `http://<monitoring-ip>:9090/targets`
   - The `clinicos` job should show **UP** in green
   - "Last Scrape" should show a recent timestamp
   - "Error" column should be empty

2. **Grafana**: `http://<monitoring-ip>:3000`
   - Login: `admin` / `clinicos2024`
   - Navigate: **Dashboards > ClinicOS > ClinicOS Overview**
   - Panels should start showing data within 1-2 minutes

---

## Common Operations

### View container logs
```bash
docker logs clinicos-prometheus --tail 50
docker logs clinicos-grafana --tail 50
```

### Restart after config changes
```bash
cd /opt/clinicos/monitoring
docker compose restart
```

### Reload Prometheus config without restart
```bash
# After editing prometheus.yml
curl -X POST http://localhost:9090/-/reload
```

### Update dashboard JSON
```bash
# From local machine — copy updated JSON
scp ~/ClinicOS-Service/monitoring/grafana/dashboards/clinicos-overview.json \
  root@<monitoring-ip>:/opt/clinicos/monitoring/grafana/dashboards/

# Restart Grafana to pick up changes
ssh root@<monitoring-ip> "docker restart clinicos-grafana"
```

### Stop monitoring (preserves data)
```bash
docker compose down
```

### Stop and DELETE all data (destructive)
```bash
docker compose down -v    # -v flag deletes volumes!
```

### Check disk usage
```bash
docker system df -v | grep -E "prometheus|grafana"
```

---

## Changing the App Server IP

If the app server IP changes:

1. Edit `prometheus.yml`:
   ```yaml
   static_configs:
     - targets: ['<new-ip>:8080']
   ```

2. Reload Prometheus:
   ```bash
   curl -X POST http://localhost:9090/-/reload
   ```

3. Verify at `http://<monitoring-ip>:9090/targets` — should show UP.

---

## Changing Grafana Password

After first login:

1. Go to `http://<monitoring-ip>:3000/profile/password`
2. Change password in the UI

Or via CLI:
```bash
docker exec clinicos-grafana grafana-cli admin reset-admin-password <new-password>
```

---

## Storage & Resource Usage

| Resource | Prometheus | Grafana | Total |
|----------|-----------|---------|-------|
| **RAM** | ~100-150 MB | ~50-100 MB | ~200 MB |
| **Disk** | ~75 MB/month | ~10 MB (config only) | ~75 MB/month |
| **CPU** | Minimal (scrape + store) | Minimal (query on request) | <5% of 1 vCPU |
| **Network** | ~5 KB every 15s outbound | None | ~30 KB/min |

1 GB RAM droplet is sufficient. Disk usage stays under 1 GB/year.

---

## Troubleshooting

### Prometheus target shows DOWN
```bash
# Test connectivity from monitoring server to app server
curl -s http://64.227.188.143:8080/actuator/prometheus | head -3

# If connection refused: check app server firewall
# If 401: check app's SecurityConfig has .requestMatchers("/actuator/**").permitAll()
# If timeout: check if port 8080 is open on app server
```

### Grafana dashboard shows "No data"
- Data needs 2+ scrape cycles to appear (30+ seconds after start)
- For rate() queries, data needs at least 5 minutes of history
- Check time range picker (top right) — set to "Last 1 hour"
- Check data source: Settings > Data Sources > Prometheus > Test

### Containers not starting
```bash
# Check logs
docker logs clinicos-prometheus 2>&1 | tail -20
docker logs clinicos-grafana 2>&1 | tail -20

# Common issue: port already in use
# Fix: change port in docker-compose.yml (e.g., "9091:9090")
```

### Running out of disk
```bash
# Check Prometheus data size
du -sh /var/lib/docker/volumes/monitoring_prometheus_data/

# Reduce retention
# Edit docker-compose.yml: --storage.tsdb.retention.time=90d
# Then: docker compose up -d
```
