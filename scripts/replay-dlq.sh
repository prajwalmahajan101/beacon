#!/usr/bin/env bash
# INGEST-14: Replay records from the DLQ topic back to the main topic.
# Reads all records from beacon.logs.dlq and re-publishes them to beacon.logs.
#
# Usage: ./scripts/replay-dlq.sh [--docker]
#   --docker  run inside the compose Kafka container (default: local kafka tools)
set -euo pipefail

DLQ_TOPIC="${DLQ_TOPIC:-beacon.logs.dlq}"
MAIN_TOPIC="${MAIN_TOPIC:-beacon.logs}"
BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:9092}"
TIMEOUT_MS="${TIMEOUT_MS:-10000}"

if [[ "${1:-}" == "--docker" ]]; then
  echo "Replaying $DLQ_TOPIC -> $MAIN_TOPIC (docker compose)..."
  docker compose exec -T kafka bash -c \
    "/opt/kafka/bin/kafka-console-consumer.sh \
       --bootstrap-server localhost:9092 \
       --topic $DLQ_TOPIC \
       --from-beginning \
       --timeout-ms $TIMEOUT_MS | \
     /opt/kafka/bin/kafka-console-producer.sh \
       --bootstrap-server localhost:9092 \
       --topic $MAIN_TOPIC"
else
  echo "Replaying $DLQ_TOPIC -> $MAIN_TOPIC (local)..."
  kafka-console-consumer.sh \
    --bootstrap-server "$BOOTSTRAP" \
    --topic "$DLQ_TOPIC" \
    --from-beginning \
    --timeout-ms "$TIMEOUT_MS" | \
  kafka-console-producer.sh \
    --bootstrap-server "$BOOTSTRAP" \
    --topic "$MAIN_TOPIC"
fi

echo "Replay complete."
