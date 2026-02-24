#!/bin/bash
# ============================================================================
# Test Script: Patient List Keyset Cursor Pagination
# Requires: MySQL running with clinicos schema, app running on port 8080
# Usage: ./scripts/test-pagination.sh
# ============================================================================

set -e
BASE_URL="http://localhost:8080"
PHONE="9876543210"
DEVICE_ID="test-device-$(date +%s)"

echo "========================================"
echo "  ClinicOS Pagination Test Script"
echo "========================================"

# --- Step 1: Get OTP ---
echo ""
echo "[1/6] Sending OTP to $PHONE..."
OTP_RESPONSE=$(curl -s -X POST "$BASE_URL/v1/auth/otp/send" \
  -H "Content-Type: application/json" \
  -d "{\"phone\": \"$PHONE\", \"countryCode\": \"+91\"}")

echo "Response: $OTP_RESPONSE"
REQUEST_ID=$(echo "$OTP_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['requestId'])" 2>/dev/null)
DEV_OTP=$(echo "$OTP_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['devOtp'])" 2>/dev/null)

if [ -z "$REQUEST_ID" ] || [ -z "$DEV_OTP" ]; then
  echo "ERROR: Failed to get OTP. Is the server running?"
  exit 1
fi
echo "  requestId: $REQUEST_ID"
echo "  devOtp: $DEV_OTP"

# --- Step 2: Verify OTP ---
echo ""
echo "[2/6] Verifying OTP..."
AUTH_RESPONSE=$(curl -s -X POST "$BASE_URL/v1/auth/otp/verify" \
  -H "Content-Type: application/json" \
  -d "{
    \"requestId\": \"$REQUEST_ID\",
    \"otp\": \"$DEV_OTP\",
    \"deviceId\": \"$DEVICE_ID\",
    \"deviceInfo\": {
      \"platform\": \"android\",
      \"osVersion\": \"14\",
      \"appVersion\": \"1.0.0\",
      \"deviceModel\": \"Test\"
    }
  }")

TOKEN=$(echo "$AUTH_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null)
ORG_ID=$(echo "$AUTH_RESPONSE" | python3 -c "import sys,json; d=json.load(sys.stdin)['data']['user']; print(d.get('orgId',''))" 2>/dev/null)

if [ -z "$TOKEN" ]; then
  echo "ERROR: Failed to get token."
  echo "Response: $AUTH_RESPONSE"
  exit 1
fi
echo "  Token: ${TOKEN:0:30}..."
echo "  OrgId: $ORG_ID"

if [ -z "$ORG_ID" ] || [ "$ORG_ID" = "None" ] || [ "$ORG_ID" = "null" ]; then
  echo ""
  echo "[!] User has no org. Creating one..."
  CREATE_ORG_RESPONSE=$(curl -s -X POST "$BASE_URL/v1/orgs" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{
      \"name\": \"Test Clinic\",
      \"address\": \"123 Test Road\",
      \"city\": \"Mumbai\",
      \"state\": \"Maharashtra\",
      \"pin\": \"400001\",
      \"workingHours\": {
        \"monday\": {\"shifts\": [{\"open\": \"09:00\", \"close\": \"17:00\"}]},
        \"tuesday\": {\"shifts\": [{\"open\": \"09:00\", \"close\": \"17:00\"}]},
        \"wednesday\": {\"shifts\": [{\"open\": \"09:00\", \"close\": \"17:00\"}]},
        \"thursday\": {\"shifts\": [{\"open\": \"09:00\", \"close\": \"17:00\"}]},
        \"friday\": {\"shifts\": [{\"open\": \"09:00\", \"close\": \"17:00\"}]},
        \"saturday\": {\"shifts\": [{\"open\": \"09:00\", \"close\": \"13:00\"}]},
        \"sunday\": {\"shifts\": []}
      },
      \"creator\": {\"name\": \"Dr. Test\"}
    }")

  TOKEN=$(echo "$CREATE_ORG_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null)
  ORG_ID=$(echo "$CREATE_ORG_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['org']['orgId'])" 2>/dev/null)
  USER_ID=$(echo "$CREATE_ORG_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['member']['userId'])" 2>/dev/null)
  echo "  Created org: $ORG_ID"
  echo "  New token: ${TOKEN:0:30}..."

  # Add doctor role to creator so sync push (patient_added) works
  echo "  Adding doctor role to creator..."
  UPDATE_MEMBER_RESPONSE=$(curl -s -X PUT "$BASE_URL/v1/orgs/$ORG_ID/members/$USER_ID" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"roles\": [\"admin\", \"doctor\"]}")
  echo "  Roles updated: $(echo "$UPDATE_MEMBER_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['roles'])" 2>/dev/null || echo "check response")"

  # Refresh token to get updated roles in JWT
  echo "  Refreshing token..."
  REFRESH_TOKEN=$(echo "$CREATE_ORG_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['refreshToken'])" 2>/dev/null)
  REFRESH_RESPONSE=$(curl -s -X POST "$BASE_URL/v1/auth/token/refresh" \
    -H "Content-Type: application/json" \
    -d "{\"refreshToken\": \"$REFRESH_TOKEN\", \"deviceId\": \"$DEVICE_ID\"}")
  TOKEN=$(echo "$REFRESH_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null)
  echo "  Refreshed token: ${TOKEN:0:30}..."
fi

if [ -z "$USER_ID" ] || [ "$USER_ID" = "None" ]; then
  USER_ID=$(echo "$AUTH_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['user']['userId'])" 2>/dev/null)
fi

# --- Seed test data: Create 10 patients via sync push ---
echo ""
echo "========================================"
echo "[SEED] Creating 10 test patients via sync push..."
echo "========================================"

QUEUE_UUID="queue-test-$(date +%s)"
NAMES=("Rahul Sharma" "Priya Patel" "Amit Kumar" "Sunita Devi" "Rajesh Singh" "Meena Gupta" "Vikram Joshi" "Anita Verma" "Suresh Yadav" "Kavita Reddy")
PHONES=("9000000001" "9000000002" "9000000003" "9000000004" "9000000005" "9000000006" "9000000007" "9000000008" "9000000009" "9000000010")

EVENTS="["
for i in $(seq 0 9); do
  PATIENT_UUID="patient-test-$i-$(date +%s)"
  ENTRY_UUID="entry-test-$i-$(date +%s)"
  EVENT_UUID="event-test-$i-$(date +%s)"
  TOKEN_NUM=$((i + 1))
  TIMESTAMP=$(($(date +%s) * 1000 - (9 - i) * 86400000))  # spread across last 10 days

  if [ $i -gt 0 ]; then EVENTS="$EVENTS,"; fi
  EVENTS="$EVENTS{
    \"eventId\": \"$EVENT_UUID\",
    \"deviceId\": \"$DEVICE_ID\",
    \"userId\": \"$USER_ID\",
    \"userRoles\": [\"assistant\"],
    \"eventType\": \"patient_added\",
    \"targetEntity\": \"$ENTRY_UUID\",
    \"targetTable\": \"queue_entries\",
    \"payload\": {
      \"queueId\": \"$QUEUE_UUID\",
      \"patientId\": \"$PATIENT_UUID\",
      \"doctorId\": \"$USER_ID\",
      \"tokenNumber\": $TOKEN_NUM,
      \"patient\": {
        \"phone\": \"${PHONES[$i]}\",
        \"name\": \"${NAMES[$i]}\",
        \"age\": $((25 + i * 3)),
        \"gender\": \"$([ $((i % 2)) -eq 0 ] && echo 'male' || echo 'female')\"
      },
      \"complaintTags\": [\"fever\"],
      \"complaintText\": \"Test complaint $TOKEN_NUM\"
    },
    \"deviceTimestamp\": $TIMESTAMP,
    \"schemaVersion\": 1
  }"
done
EVENTS="$EVENTS]"

SEED_RESPONSE=$(curl -s -X POST "$BASE_URL/v1/sync/push" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "X-Device-Id: $DEVICE_ID" \
  -d "{\"deviceId\": \"$DEVICE_ID\", \"events\": $EVENTS}")

ACCEPTED=$(echo "$SEED_RESPONSE" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['data']['accepted']))" 2>/dev/null)
REJECTED=$(echo "$SEED_RESPONSE" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['data']['rejected']))" 2>/dev/null)
echo "  Accepted: $ACCEPTED, Rejected: $REJECTED"

if [ "$ACCEPTED" = "0" ]; then
  echo "  ERROR: No events accepted. Check server logs."
  echo "  Response: $SEED_RESPONSE"
  exit 1
fi

echo "  Patients seeded. Note: lastVisitDate is null (no visits yet)."
echo "  Pagination test will use created_desc sort for best results."

sleep 1  # brief pause for DB commit

# --- Step 3: Patient List — Page 1 ---
echo ""
echo "========================================"
echo "[3/6] Patient List - Page 1 (limit=3, sort=created_desc)"
echo "========================================"
PAGE1=$(curl -s -X GET "$BASE_URL/v1/orgs/$ORG_ID/patients?limit=3&sort=created_desc" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Device-Id: $DEVICE_ID")

echo "$PAGE1" | python3 -c "
import sys, json
d = json.load(sys.stdin)
patients = d.get('data', d).get('patients', [])
meta = d.get('data', d).get('meta', {}).get('pagination', {})
print(f'  Patients returned: {len(patients)}')
for p in patients:
    print(f'    - {p[\"name\"]} (phone: {p[\"phone\"]}, lastVisit: {p.get(\"lastVisitDate\",\"never\")})')
print(f'  hasMore: {meta.get(\"hasMore\", False)}')
print(f'  nextCursor: {meta.get(\"nextCursor\", \"null\")}')
" 2>/dev/null || echo "  Raw: $PAGE1"

CURSOR1=$(echo "$PAGE1" | python3 -c "
import sys, json
d = json.load(sys.stdin)
meta = d.get('data', d).get('meta', {}).get('pagination', {})
c = meta.get('nextCursor')
print(c if c else '')
" 2>/dev/null)

# --- Step 4: Patient List — Page 2 (using cursor) ---
if [ -n "$CURSOR1" ]; then
  echo ""
  echo "========================================"
  echo "[4/6] Patient List - Page 2 (cursor from page 1)"
  echo "========================================"
  PAGE2=$(curl -s -X GET "$BASE_URL/v1/orgs/$ORG_ID/patients?limit=3&sort=created_desc&afterCursor=$CURSOR1" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-Device-Id: $DEVICE_ID")

  echo "$PAGE2" | python3 -c "
import sys, json
d = json.load(sys.stdin)
patients = d.get('data', d).get('patients', [])
meta = d.get('data', d).get('meta', {}).get('pagination', {})
print(f'  Patients returned: {len(patients)}')
for p in patients:
    print(f'    - {p[\"name\"]} (phone: {p[\"phone\"]}, lastVisit: {p.get(\"lastVisitDate\",\"never\")})')
print(f'  hasMore: {meta.get(\"hasMore\", False)}')
print(f'  nextCursor: {meta.get(\"nextCursor\", \"null\")}')
" 2>/dev/null || echo "  Raw: $PAGE2"

  # Check page 2 patients are different from page 1
  echo ""
  echo "  [Validation] Checking no overlap between pages..."
  python3 -c "
import sys, json
p1 = json.loads('''$PAGE1''')
p2 = json.loads('''$PAGE2''')
ids1 = {p['patientId'] for p in p1.get('data', p1).get('patients', [])}
ids2 = {p['patientId'] for p in p2.get('data', p2).get('patients', [])}
overlap = ids1 & ids2
if overlap:
    print(f'  FAIL: Overlapping patients: {overlap}')
else:
    print(f'  PASS: No overlap. Page 1 has {len(ids1)} unique, Page 2 has {len(ids2)} unique.')
" 2>/dev/null
else
  echo ""
  echo "[4/6] SKIPPED — no more pages (hasMore=false or < 3 patients)"
fi

# --- Step 5: Search test ---
echo ""
echo "========================================"
echo "[5/6] Patient Search (name or phone)"
echo "========================================"
SEARCH_RESULT=$(curl -s -X GET "$BASE_URL/v1/orgs/$ORG_ID/patients/search?q=Ra&limit=5" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Device-Id: $DEVICE_ID")

echo "$SEARCH_RESULT" | python3 -c "
import sys, json
d = json.load(sys.stdin)
results = d.get('data', d).get('results', [])
print(f'  Results for \"Ra\": {len(results)}')
for r in results:
    print(f'    - {r[\"name\"]} ({r[\"phone\"]})')
" 2>/dev/null || echo "  Raw: $SEARCH_RESULT"

# --- Step 6: Visit Thread test ---
echo ""
echo "========================================"
echo "[6/6] Patient Thread (visit history)"
echo "========================================"
# Get first patient ID for thread test
PATIENT_ID=$(echo "$PAGE1" | python3 -c "
import sys, json
d = json.load(sys.stdin)
patients = d.get('data', d).get('patients', [])
print(patients[0]['patientId'] if patients else '')
" 2>/dev/null)

if [ -n "$PATIENT_ID" ]; then
  THREAD=$(curl -s -X GET "$BASE_URL/v1/orgs/$ORG_ID/patients/$PATIENT_ID/thread?limit=3" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-Device-Id: $DEVICE_ID")

  echo "$THREAD" | python3 -c "
import sys, json
d = json.load(sys.stdin)
visits = d.get('data', d).get('visits', [])
meta = d.get('data', d).get('meta', {}).get('pagination', {})
print(f'  Visits for patient $PATIENT_ID: {len(visits)}')
for v in visits:
    print(f'    - {v[\"date\"]} by {v[\"createdBy\"][\"name\"]} ({v[\"createdBy\"][\"role\"]})')
print(f'  hasMore: {meta.get(\"hasMore\", False)}')
print(f'  nextCursor: {meta.get(\"nextCursor\", \"null\")}')
" 2>/dev/null || echo "  Raw: $THREAD"
else
  echo "  SKIPPED — no patients to test thread"
fi

echo ""
echo "========================================"
echo "  Test Complete"
echo "========================================"
