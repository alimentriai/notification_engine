#!/bin/sh
# Re-exports the OpenAPI spec from a running engine so the committed file matches the code.
# Usage: ./export-openapi.sh [BASE_URL]   (default http://localhost:8082)
set -e
BASE="${1:-http://localhost:8082}"
OUT="$(dirname "$0")/openapi.yaml"
curl -fsS "$BASE/api/v1/v3/api-docs.yaml" -o "$OUT"
echo "wrote $OUT ($(wc -l < "$OUT") lines)"
