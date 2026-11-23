#!/usr/bin/env bash
# AetherGate live demo: five scenes that each prove one feature against a running gateway.
#
#   ./scripts/live-demo.sh                          # against the live Oracle Cloud API
#   API=http://localhost:8088 ./scripts/live-demo.sh  # against IntelliJ / local run
#   ./scripts/live-demo.sh traffic 40               # only generate 40 requests (dashboard moves)
#
# Uses only the PUBLIC sandbox key and the public demo login: no secrets in this file.
set -euo pipefail
export PYTHONIOENCODING=utf-8

API="${API:-https://138-2-169-195.sslip.io}"
KEY="ag_live_demo_vortex_5c1e8a2f9b7d4630"
B=$'\e[1m'; D=$'\e[2m'; G=$'\e[32m'; Y=$'\e[33m'; R=$'\e[31m'; C=$'\e[36m'; N=$'\e[0m'

scene() { echo; echo "${B}${C}━━ $1 ━━${N}"; echo "${D}$2${N}"; [ -n "${FAST:-}" ] || read -r -p "  [Enter] " _; }
json()  { python -c "import sys,json; d=json.load(sys.stdin); print($1)"; }

# POST a chat completion (non-stream); prints "HTTP X-Cache cost provider"
ask() {
  local prompt="$1"; shift
  curl -s -m 60 -D /tmp/ag_h -o /tmp/ag_b "$API/v1/chat/completions" \
    -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" "$@" \
    -d "{\"model\":\"auto\",\"max_tokens\":300,\"messages\":[{\"role\":\"user\",\"content\":\"$prompt\"}]}" \
    -w "%{http_code}"
}
hdr() { grep -i "^$1:" /tmp/ag_h | head -1 | cut -d' ' -f2- | tr -d '\r'; }

traffic() {
  local n="${1:-30}" topics=("How does the reserve-settle ledger work?" "Explain pgvector HNSW indexes"
    "What is a circuit breaker?" "Summarise row level security" "Why BigDecimal for money?")
  echo "${B}Generating $n requests against $API ...${N}"
  for i in $(seq 1 "$n"); do
    local p="${topics[$((RANDOM % ${#topics[@]}))]}"
    [ $((RANDOM % 2)) -eq 0 ] && p="$p (ticket $RANDOM)"     # half unique -> MISS, half repeated -> HIT
    code=$(ask "$p"); printf "  %3d  %s  %-4s  %s\n" "$i" "$code" "$(hdr x-cache)" "$p"
    sleep 1.1                                               # stay under the demo key's 60 RPM
  done
}

if [ "${1:-}" = "traffic" ]; then traffic "${2:-30}"; exit 0; fi

echo "${B}AetherGate live demo → $API${N}"
curl -s -m 20 "$API/actuator/health"; echo

# ── 1 ─────────────────────────────────────────────────────────────────────────────────
STAMP=$(date +%H%M%S)
PROMPT="Explain two-phase budget metering in one paragraph (demo $STAMP)"
scene "1 · Streaming + two-phase metering" "New prompt → RESERVE hold, tokens stream live, then SETTLE exact cost + RELEASE the rest."
curl -sN -m 60 "$API/v1/chat/completions" -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
  -d "{\"model\":\"auto\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"$PROMPT\"}]}" \
  | while IFS= read -r line; do
      case "$line" in
        "data: [DONE]") echo; echo "  ${G}[DONE]${N}";;
        "event: aethergate.telemetry") TEL=1; echo;;
        data:*) if [ -n "${TEL:-}" ]; then
                  echo "${line#data: }" | json "'  model=%s  RESERVE \$%s = SETTLE \$%s + RELEASE \$%s  ·  TTFT %sms' % (d['model'], d['reserved_usd'], d['settled_usd'], d['released_usd'], d['ttft_ms'])"
                else
                  printf "%s" "$(echo "${line#data: }" | json "d['choices'][0]['delta'].get('content','')" 2>/dev/null)"
                fi;;
      esac
    done

# ── 2 ─────────────────────────────────────────────────────────────────────────────────
scene "2 · Semantic cache" "Same question again → answered from pgvector in milliseconds, cost \$0."
t0=$(date +%s%N); code=$(ask "$PROMPT"); ms=$(( ($(date +%s%N) - t0) / 1000000 ))
echo "  HTTP $code · X-Cache: ${G}$(hdr x-cache)${N} · ${ms} ms round trip"

# ── 3 ─────────────────────────────────────────────────────────────────────────────────
scene "3 · Chaos: primary provider down" "X-Simulate-Provider-Failure: true → retry, breaker, failover to the next provider before the first token."
code=$(ask "Failover demo $STAMP" -H "X-Simulate-Provider-Failure: true")
echo "  HTTP $code · served by: ${Y}$(json "d['aethergate']['model']" < /tmp/ag_b)${N} (primary is gpt-4o)"
echo "  attempts: $(json "'; '.join(d['aethergate']['attempts'])" < /tmp/ag_b)"

# ── 4 ─────────────────────────────────────────────────────────────────────────────────
scene "4 · Rate limit" "Create a key limited to 3 requests/min, fire 5 → the 4th gets 429 + Retry-After. Key is revoked afterwards."
JWT=$(curl -s -m 30 -X POST "$API/api/v1/auth/demo-session" | json "d['access_token']")
NEW=$(curl -s -m 20 -X POST "$API/api/v1/tenant/api-keys" -H "Authorization: Bearer $JWT" -H "Content-Type: application/json" \
  -d "{\"name\":\"live-demo-$STAMP\",\"rate_limit_rpm\":3,\"rate_limit_tpm\":100000}")
KID=$(echo "$NEW" | json "d['key']['id']"); RAW=$(echo "$NEW" | json "d['raw_key']")
for i in 1 2 3 4 5; do
  code=$(KEY="$RAW" ask "rate limit probe $i $STAMP")
  [ "$code" = 429 ] && echo "  #$i → ${R}429${N}  Retry-After: $(hdr retry-after)s" || echo "  #$i → ${G}$code${N}"
done
curl -s -m 20 -o /dev/null -X DELETE "$API/api/v1/tenant/api-keys/$KID" -H "Authorization: Bearer $JWT"
echo "  ${D}temporary key revoked${N}"

# ── 5 ─────────────────────────────────────────────────────────────────────────────────
scene "5 · Proof in the numbers" "Balance, spend, cache savings and tenant isolation, straight from the dashboard API."
curl -s -m 30 "$API/api/v1/dashboard/overview?range=24h" -H "Authorization: Bearer $JWT" | json "'\n'.join([
  '  tenant            : ' + d['tenant']['name'],
  '  available balance : \$' + str(d['kpis']['available_balance_usd']),
  '  reserved now      : \$' + str(d['kpis']['reserved_balance_usd']),
  '  isolation         : %s foreign rows in DB · %s visible' % (d['isolation']['other_tenant_rows_in_database'], d['isolation']['other_tenant_rows_visible'])])" \
  2>/dev/null || echo "  (open the console Overview page)"
echo; echo "${B}${G}Demo complete.${N}"
