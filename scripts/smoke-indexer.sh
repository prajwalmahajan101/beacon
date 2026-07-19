#!/usr/bin/env bash
#
# smoke-indexer.sh — Phase 5.2 / M3.0c end-to-end smoke test for the Vector indexer.
#
# Proves the roadmap's acceptance gate: a canonical M0 record seeded to the
# `beacon.logs` Kafka topic is returned by an Elasticsearch `_search` within the
# timeout. Seeds Kafka DIRECTLY (via the broker's console producer) — the gateway
# is not needed for an indexer smoke, so we don't build/boot it here.
#
# Usage:
#   ./scripts/smoke-indexer.sh          # up -> seed -> assert -> tear down (-v)
#   SMOKE_KEEP=1 ./scripts/smoke-indexer.sh   # leave the stack up for inspection
#
# Requires a running Docker daemon + docker compose v2. Exits non-zero on failure
# so it is CI-adoptable as-is (the ingest.yml gate is Phase 5.3).

set -euo pipefail

cd "$(dirname "$0")/.."

REPO_ROOT="$(pwd)"
RECORD_FILE="contract/schema/examples/log-valid.json"
TOPIC="beacon.logs"
INDEX="beacon-logs"
ES="http://localhost:9200"
POLL_TIMEOUT_SECS="${SMOKE_POLL_TIMEOUT:-60}"
EXPECTED_BODY="charge declined"

log()  { printf '\033[1;34m[smoke]\033[0m %s\n' "$*"; }
pass() { printf '\033[1;32m[smoke] PASS:\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[smoke] FAIL:\033[0m %s\n' "$*" >&2; exit 1; }

teardown() {
  local code=$?
  if [[ -n "${SMOKE_KEEP:-}" ]]; then
    log "SMOKE_KEEP set — leaving the stack up (docker compose down -v to clean)."
  else
    log "Tearing the stack down (docker compose down -v)…"
    docker compose down -v >/dev/null 2>&1 || true
  fi
  exit "$code"
}
trap teardown EXIT

# 1. Bring up only the indexer path (kafka + es + vector); the existing per-service
#    healthchecks are the readiness gate.
log "Bringing up kafka + elasticsearch + vector (--wait)…"
docker compose up -d --wait kafka elasticsearch vector

# 2. Seed one canonical M0 record (compacted from the contract fixture) onto the topic
#    via the broker's own console producer on the HOST listener (localhost:9092).
log "Seeding a canonical M0 record onto '$TOPIC'…"
RECORD="$(python3 -c "import json,sys; print(json.dumps(json.load(open('$RECORD_FILE'))))")"
printf '%s\n' "$RECORD" \
  | docker compose exec -T kafka \
      /opt/kafka/bin/kafka-console-producer.sh \
        --bootstrap-server localhost:9092 --topic "$TOPIC"
log "Seeded: $RECORD"

# 3. Poll ES until the record is searchable (refresh first each round) or timeout.
log "Polling $ES/$INDEX/_search (up to ${POLL_TIMEOUT_SECS}s)…"
deadline=$(( $(date +%s) + POLL_TIMEOUT_SECS ))
hits=0
while (( $(date +%s) < deadline )); do
  curl -fsS -X POST "$ES/$INDEX/_refresh" >/dev/null 2>&1 || true
  body="$(curl -fsS "$ES/$INDEX/_search?size=1" 2>/dev/null || echo '')"
  if [[ -n "$body" ]]; then
    hits="$(python3 -c "import json,sys; d=json.loads(sys.argv[1]); print(d.get('hits',{}).get('total',{}).get('value',0))" "$body" 2>/dev/null || echo 0)"
    if [[ "$hits" -ge 1 ]]; then
      indexed_body="$(python3 -c "import json,sys; d=json.loads(sys.argv[1]); print(d['hits']['hits'][0]['_source'].get('body',''))" "$body" 2>/dev/null || echo '')"
      [[ "$indexed_body" == "$EXPECTED_BODY" ]] \
        || fail "record indexed but body mismatch: got '$indexed_body', want '$EXPECTED_BODY'"
      pass "record searchable in ES index '$INDEX' (hits=$hits, body='$indexed_body')."
      log "Recent Vector bulk activity:"
      docker compose logs --tail=20 vector 2>/dev/null | grep -iE 'bulk|elasticsearch|error' || true
      exit 0
    fi
  fi
  sleep 3
done

log "Vector logs (last 40 lines) for diagnosis:"
docker compose logs --tail=40 vector 2>/dev/null || true
fail "record not searchable in '$INDEX' within ${POLL_TIMEOUT_SECS}s (hits=$hits)."
