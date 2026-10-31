#!/usr/bin/env bash
# INGEST-06: Hot-partition detector for the beacon-indexer consumer group.
# Runs kafka-consumer-groups.sh --describe, computes max/avg lag per partition,
# warns if the ratio exceeds 1.5x (configurable via SKEW_THRESHOLD).
#
# Usage: ./scripts/check-partition-skew.sh [--docker]
#   --docker  run inside the compose Kafka container (default: local kafka tools)
set -euo pipefail

THRESHOLD="${SKEW_THRESHOLD:-1.5}"
GROUP="${KAFKA_GROUP:-beacon-indexer}"
BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:9092}"

run_cmd() {
  if [[ "${1:-}" == "--docker" ]]; then
    docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh \
      --bootstrap-server localhost:9092 --describe --group "$GROUP" 2>/dev/null
  else
    kafka-consumer-groups.sh \
      --bootstrap-server "$BOOTSTRAP" --describe --group "$GROUP" 2>/dev/null
  fi
}

output=$(run_cmd "${1:-}")
if [[ -z "$output" ]]; then
  echo "ERROR: no output from consumer-groups describe (group=$GROUP)" >&2
  exit 1
fi

lags=$(echo "$output" | awk 'NR>1 && $6 ~ /^[0-9]+$/ { print $6 }')
if [[ -z "$lags" ]]; then
  echo "OK: no lag data (consumer group may not have committed offsets yet)"
  exit 0
fi

count=0; total=0; max_lag=0
while IFS= read -r lag; do
  count=$((count + 1))
  total=$((total + lag))
  if (( lag > max_lag )); then max_lag=$lag; fi
done <<< "$lags"

if (( count == 0 )); then
  echo "OK: no partitions with lag data"
  exit 0
fi

avg=$(awk "BEGIN { printf \"%.2f\", $total / $count }")

if (( max_lag == 0 )); then
  echo "OK: max_lag=0, avg=$avg across $count partitions"
  exit 0
fi

ratio=$(awk "BEGIN { r = $max_lag / ($avg > 0 ? $avg : 1); printf \"%.2f\", r }")
if awk "BEGIN { exit !($ratio > $THRESHOLD) }"; then
  echo "WARN: hot partition detected — max_lag=$max_lag, avg=$avg, ratio=$ratio (threshold=$THRESHOLD)"
  exit 1
else
  echo "OK: max_lag=$max_lag, avg=$avg, ratio=$ratio (threshold=$THRESHOLD)"
  exit 0
fi
