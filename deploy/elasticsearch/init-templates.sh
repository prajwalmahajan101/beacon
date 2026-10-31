#!/bin/sh
# Seed Elasticsearch with ILM policy, component template, and index templates
# for the beacon-logs data stream and beacon-logs-rejected plain index.
#
# Runs as a one-shot docker-compose service (es-init) that depends on ES healthy.
# Exits non-zero on any failure so compose detects it.

set -eu

ES="http://elasticsearch:9200"

printf "Waiting for Elasticsearch...\n"
until curl -fsS "$ES/_cluster/health?wait_for_status=yellow&timeout=5s" >/dev/null 2>&1; do
  sleep 2
done
printf "Elasticsearch is ready.\n"

# --- ILM policy ---
printf "Creating ILM policy beacon-logs-policy...\n"
curl -fsS -X PUT "$ES/_ilm/policy/beacon-logs-policy" \
  -H "Content-Type: application/json" \
  -d '{
  "policy": {
    "phases": {
      "hot": {
        "actions": {
          "rollover": {
            "max_age": "1d",
            "max_primary_shard_size": "50gb"
          }
        }
      },
      "warm": {
        "min_age": "7d",
        "actions": {
          "shrink": { "number_of_shards": 1 }
        }
      },
      "delete": {
        "min_age": "90d",
        "actions": {
          "delete": {}
        }
      }
    }
  }
}'
printf "\n"

# --- Component template (mappings + settings) ---
printf "Creating component template beacon-logs-mappings...\n"
curl -fsS -X PUT "$ES/_component_template/beacon-logs-mappings" \
  -H "Content-Type: application/json" \
  -d '{
  "template": {
    "settings": {
      "index": {
        "mapping": {
          "total_fields": { "limit": 2000 },
          "depth": { "limit": 5 }
        }
      }
    },
    "mappings": {
      "dynamic": "strict",
      "properties": {
        "@timestamp":          { "type": "date" },
        "timestamp":           { "type": "date" },
        "observed_timestamp":  { "type": "date" },
        "severity_number":     { "type": "integer" },
        "severity_text":       { "type": "keyword" },
        "body":                { "type": "text" },
        "trace_id":            { "type": "keyword" },
        "span_id":             { "type": "keyword" },
        "trace_flags":         { "type": "integer" },
        "schema_version":      { "type": "integer" },
        "resource": {
          "type": "object",
          "dynamic": "true",
          "properties": {
            "service.name":           { "type": "keyword" },
            "service.version":        { "type": "keyword" },
            "deployment.environment": { "type": "keyword" },
            "host.name":              { "type": "keyword" },
            "telemetry.sdk.language": { "type": "keyword" },
            "telemetry.sdk.name":     { "type": "keyword" }
          }
        },
        "scope": {
          "type": "object",
          "properties": {
            "name": { "type": "keyword" }
          }
        },
        "attributes":  { "type": "flattened" },
        "tenant_id":   { "type": "keyword" }
      }
    }
  }
}'
printf "\n"

# --- Index template (data stream) ---
printf "Creating index template beacon-logs-template...\n"
curl -fsS -X PUT "$ES/_index_template/beacon-logs-template" \
  -H "Content-Type: application/json" \
  -d '{
  "index_patterns": ["beacon-logs"],
  "data_stream": {},
  "composed_of": ["beacon-logs-mappings"],
  "priority": 500,
  "template": {
    "settings": {
      "index": {
        "lifecycle": {
          "name": "beacon-logs-policy"
        }
      }
    }
  }
}'
printf "\n"

# --- Rejected index template (plain index, permissive mapping) ---
printf "Creating index template beacon-logs-rejected-template...\n"
curl -fsS -X PUT "$ES/_index_template/beacon-logs-rejected-template" \
  -H "Content-Type: application/json" \
  -d '{
  "index_patterns": ["beacon-logs-rejected"],
  "priority": 500,
  "template": {
    "mappings": {
      "dynamic": "true"
    }
  }
}'
printf "\n"

printf "ES templates initialized successfully.\n"
