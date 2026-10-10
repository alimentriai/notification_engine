#!/usr/bin/env bash
# Onboards a project into the notification engine from a seed file:
#   1) creates the project        2) loads its templates        3) issues the project API key (shown once)
# Re-running is safe: an existing project and existing templates are skipped.
#
#   ENGINE_URL=http://localhost:8082 ADMIN_KEY=... ./setup-project.sh [seed.json] [--demo]
#
# Needs: bash, curl, python3. Never commit the printed key.
set -euo pipefail

ENGINE_URL="${ENGINE_URL:-http://localhost:8082}"
: "${ADMIN_KEY:?Set ADMIN_KEY to the platform admin key of the engine}"
SEED="$(dirname "$0")/crusher-erp.seed.json"
DEMO=0
for arg in "$@"; do
  case "$arg" in --demo) DEMO=1 ;; *) SEED="$arg" ;; esac
done
API="$ENGINE_URL/api/v1"
J='Content-Type: application/json'

py() { python3 -c "$@"; }
field() { py "import sys,json; d=json.load(sys.stdin); print(d$1)"; }
CODE_FILE="$(mktemp)"; BODY_FILE="$(mktemp)"
trap 'rm -f "$CODE_FILE" "$BODY_FILE"' EXIT
# call METHOD PATH KEY [BODY]  ->  prints the response body; the HTTP status is left in $CODE_FILE
# (a file, so it also works inside $(...) where shell variables do not propagate)
call() {
  local method="$1" path="$2" key="$3" body="${4:-}"
  if [ -n "$body" ]; then
    curl -sS -o "$BODY_FILE" -w '%{http_code}' -X "$method" "$API$path" -H "X-Api-Key: $key" -H "$J" -d "$body" >"$CODE_FILE"
  else
    curl -sS -o "$BODY_FILE" -w '%{http_code}' -X "$method" "$API$path" -H "X-Api-Key: $key" >"$CODE_FILE"
  fi
  cat "$BODY_FILE"
}
code() { cat "$CODE_FILE"; }

CODE_P=$(py "import json;print(json.load(open('$SEED'))['project']['code'])")

echo "== 1/4 Project '$CODE_P' on $ENGINE_URL"
PROJECT_BODY=$(py "import json;print(json.dumps(json.load(open('$SEED'))['project']))")
RESP=$(call POST /admin/projects "$ADMIN_KEY" "$PROJECT_BODY")
case "$(code)" in
  201) echo "created" ;;
  409) echo "already exists, continuing" ;;
  *)   echo "failed with HTTP $(code): $RESP"; exit 1 ;;
esac

echo "== 2/4 Temporary setup key (scope TEMPLATES, revoked at the end)"
SETUP=$(call POST "/admin/projects/$CODE_P/keys" "$ADMIN_KEY" '{"label":"setup-temp","scopes":["TEMPLATES"]}')
[ "$(code)" = 201 ] || { echo "could not issue setup key (HTTP $(code)): $SETUP"; exit 1; }
SETUP_KEY=$(echo "$SETUP" | field "['key']"); SETUP_ID=$(echo "$SETUP" | field "['id']")
trap 'curl -sS -o /dev/null -X DELETE "$API/admin/projects/$CODE_P/keys/$SETUP_ID" -H "X-Api-Key: $ADMIN_KEY" || true; rm -f "$CODE_FILE" "$BODY_FILE"' EXIT

echo "== 3/4 Templates"
EXISTING=$(call GET /templates "$SETUP_KEY" | py "import sys,json;print('\n'.join(t['code']+'|'+t['channel']+'|'+t['locale'] for t in json.load(sys.stdin)))")
COUNT_NEW=0; COUNT_SKIP=0
while IFS=$'\t' read -r id body; do
  if echo "$EXISTING" | grep -qxF "$id"; then COUNT_SKIP=$((COUNT_SKIP+1)); continue; fi
  RESP=$(call POST /templates "$SETUP_KEY" "$body")
  [ "$(code)" = 201 ] || { echo "template $id failed (HTTP $(code)): $RESP"; exit 1; }
  echo "  + $id"; COUNT_NEW=$((COUNT_NEW+1))
done < <(py "
import json
for t in json.load(open('$SEED'))['templates']:
    print(t['code'] + '|' + t['channel'] + '|' + t['locale'] + '\t' + json.dumps(t))
")
echo "$COUNT_NEW created, $COUNT_SKIP already present"

echo "== 4/4 Project API key"
# The raw key is stored only as a hash, so it cannot be shown again. If an active key with the same label
# already exists we keep it (set FORCE_NEW_KEY=1 to issue another one, then revoke the old one).
ACTIVE_LABELS=$(call GET "/admin/projects/$CODE_P/keys" "$ADMIN_KEY" | py "import sys,json;print('\n'.join(k['label'] for k in json.load(sys.stdin) if k['active']))")
KEYS_JSON=$(py "import json;print(json.dumps(json.load(open('$SEED'))['keys']))")
n=$(echo "$KEYS_JSON" | py "import sys,json;print(len(json.load(sys.stdin)))")
FIRST_KEY=""
for i in $(seq 0 $((n-1))); do
  KBODY=$(echo "$KEYS_JSON" | py "import sys,json;print(json.dumps(json.load(sys.stdin)[$i]))")
  LABEL=$(echo "$KBODY" | field "['label']")
  if [ "${FORCE_NEW_KEY:-0}" != 1 ] && echo "$ACTIVE_LABELS" | grep -qxF "$LABEL"; then
    echo "  Key '$LABEL' already exists and is active; not issuing another."
    echo "  (the raw value cannot be shown again; set FORCE_NEW_KEY=1 to issue a new one, then revoke the old one)"
    continue
  fi
  K=$(call POST "/admin/projects/$CODE_P/keys" "$ADMIN_KEY" "$KBODY")
  [ "$(code)" = 201 ] || { echo "could not issue key (HTTP $(code)): $K"; exit 1; }
  SCOPES=$(echo "$K" | py "import sys,json;print(','.join(json.load(sys.stdin)['scopes']))")
  KEY=$(echo "$K" | field "['key']")
  echo
  echo "  Key '$LABEL' scopes=$SCOPES"
  echo "  NOTIFY_API_KEY=$KEY"
  echo "  ^ shown ONCE. Store it in a secret manager now. Do not commit it or paste it in chat."
  if [ -z "$FIRST_KEY" ]; then FIRST_KEY="$KEY"; fi
done

if [ "$DEMO" = 1 ]; then
  [ -n "$FIRST_KEY" ] || { echo "--demo needs a freshly issued key; rerun with FORCE_NEW_KEY=1"; exit 1; }
  echo; echo "== Demo notifications (using the new key)"
  send() {
    local resp; resp=$(call POST /notifications "$FIRST_KEY" "$1")
    [ "$(code)" = 202 ] || { echo "send failed (HTTP $(code)): $resp"; return 1; }
    echo "$resp" | py "import sys,json;[print('  ',n['channel'],n['status'],n['id']) for n in json.load(sys.stdin)['notifications']]"
  }
  RUN=$(date +%s)
  send '{"type":"WEIGHMENT_APPROVAL_REQUESTED","category":"APPROVAL","channels":["IN_APP","EMAIL"],"templateCode":"weighment-approval-requested","recipient":{"ref":"user-42","email":"'"${DEMO_EMAIL:-ops@example.com}"'"},"variables":{"weighmentId":101,"vehicleNo":"MH12AB1234","material":"20mm aggregate","requestedBy":"Loader operator"},"priority":"HIGH","idempotencyKey":"demo-'$RUN'-weighment-101"}'
  send '{"type":"INVOICE_GENERATED","category":"INFO","channels":["IN_APP","EMAIL"],"templateCode":"invoice-generated","recipient":{"ref":"user-42","email":"'"${DEMO_EMAIL:-ops@example.com}"'"},"variables":{"invoiceNo":"INV-2026-0055","customerName":"Shree Constructions","amount":"INR 1,24,500","dueDate":"2026-11-10"},"idempotencyKey":"demo-'$RUN'-invoice-55"}'
  send '{"type":"CREDIT_LIMIT_WARNING","category":"WARNING","channels":["IN_APP"],"templateCode":"credit-limit-warning","recipient":{"ref":"user-42"},"variables":{"customerName":"Shree Constructions","usedPercent":92,"creditLimit":"INR 5,00,000"},"idempotencyKey":"demo-'$RUN'-credit-92"}'
  echo "  Check status with: GET $API/notifications/{id}  (X-Api-Key: the new key)"
fi
echo; echo "Done."
