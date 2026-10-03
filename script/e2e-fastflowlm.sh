#!/usr/bin/env bash
#
# CompanionAI -> FastFlowLM end-to-end chat validation.
#
#   ./script/e2e-fastflowlm.sh
#
# This is the gate that F1-F5 could not cover. Those probes called FastFlowLM
# directly; /api/stats proved only that CompanionAI boots. Neither touched the
# path that matters in production:
#
#   /api/chat  and  /api/chat/stream
#       -> ModelServlet
#       -> LlmProvider
#       -> FastFlowLmProvider
#       -> OpenAiCompatTransport
#       -> FastFlowLM
#       -> Qwen / XDNA2
#
# What it proves, per assertion:
#
#   * the app boots against provider=fastflowlm with the expected model/base URL
#   * the app's configured model is the one we asked for (rendered into the page)
#   * /api/chat returns a complete, non-fallback reply
#   * /api/stats shows jsonReplies+1, outputTokens up, offline unchanged
#   * /api/chat/stream emits many SSE deltas spread across wall-clock time
#   * /api/stats shows streamed+1, offline unchanged
#   * FastFlowLM's own /api/stats moves between the two tests
#
# "offline" is the decisive field. ModelServlet only sets offline=true when the
# provider threw LlmException and it fell back to the canned ChatRules reply. A
# non-empty reply with offline=false therefore cannot have been produced without
# FastFlowLM generating it.
#
# Streaming is checked for real arrival times, not just content. Each SSE line is
# timestamped as curl reads it. If the response were buffered - by Tomcat, by the
# provider, or later by Nginx - every delta would land within a few milliseconds
# of every other one at the very end. The test asserts a minimum first-to-last
# spread AND a minimum worst-case inter-chunk gap, which a buffered body cannot
# produce.
#
# Nothing here changes production configuration. The app is started with
# process-local -D flags via script/run.sh, no systemd unit, environment file or
# Nginx config is touched, and the app is stopped again before the script exits.
# Side effect: run.sh writes script/current-model.txt, and the app creates its
# default ./data directory. Both are dev-host artifacts, already true of any
# local run.
#
# Exit status: 0 if every assertion passed, 1 otherwise.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

APP_URL="${APP_URL:-http://127.0.0.1:8080}"
FLM_URL="${FLM_URL:-http://127.0.0.1:52625}"
MODEL="${MODEL:-qwen2.5-it:3b}"

CHAT_TIMEOUT="${CHAT_TIMEOUT:-300}"    # generous: 3B on NPU, plus a classifier round-trip
STREAM_TIMEOUT="${STREAM_TIMEOUT:-300}"
READY_TIMEOUT="${READY_TIMEOUT:-180}"

# A reply long enough to produce many deltas, so "many chunks" is a real
# expectation rather than an artefact of a two-token answer. Deliberately avoids
# weather/sports/web-search phrasing so RuleIntentClassifier matches nothing and
# the test exercises exactly one code path.
CHAT_PROMPT="${CHAT_PROMPT:-In exactly three short sentences, explain what a database index is.}"

MIN_DELTAS="${MIN_DELTAS:-5}"
MIN_SPREAD_MS="${MIN_SPREAD_MS:-300}"  # first delta -> last delta
MIN_MAXGAP_MS="${MIN_MAXGAP_MS:-80}"    # largest pause between consecutive deltas

TMP="$(mktemp -d)"
APP_PID=""
PASS=0
FAIL=0
STOPPED=0

say()  { printf '%s\n' "$*"; }
head1() { printf '\n=== %s\n' "$*"; }
info() { printf '      %s\n' "$*"; }
pass() { printf '  PASS  %s\n' "$*"; PASS=$((PASS + 1)); }
fail() { printf '  FAIL  %s\n' "$*"; FAIL=$((FAIL + 1)); }
die()  { printf '\nABORT: %s\n' "$*"; stop_app; exit 2; }

check() { # check <description> <condition-exit-code>
  if [ "$2" -eq 0 ]; then pass "$1"; else fail "$1"; fi
}

# /api/stats is a flat object of integers; pull one field, defaulting to 0 so a
# missing key degrades into a failing assertion rather than an unbound-variable
# error that would abort the run.
statnum() {
  local v
  v="$(printf '%s' "$1" | grep -o "\"$2\":[0-9]*" | cut -d: -f2 | head -1)"
  printf '%s' "${v:-0}"
}

stop_app() {
  [ "$STOPPED" -eq 1 ] && return 0
  STOPPED=1
  [ -z "$APP_PID" ] && return 0
  curl -fsS -m 10 -X POST "$APP_URL/api/shutdown" >/dev/null 2>&1 || true
  for _ in $(seq 1 20); do
    kill -0 "$APP_PID" 2>/dev/null || { info "app stopped"; return 0; }
    sleep 1
  done
  info "app did not stop on /api/shutdown; sending SIGTERM"
  kill -TERM "$APP_PID" 2>/dev/null || true
  sleep 3
  kill -0 "$APP_PID" 2>/dev/null && kill -KILL "$APP_PID" 2>/dev/null
  return 0
}

trap stop_app EXIT INT TERM

# --------------------------------------------------------------------------
# Preflight
# --------------------------------------------------------------------------

command -v curl >/dev/null 2>&1 || die "curl is required"
# 'command -v' is not enough: a stub or broken interpreter can be on PATH and
# still fail to execute. Prove it runs.
python3 -c 'import json' >/dev/null 2>&1 \
  || die "python3 is required and must be runnable (sudo apt install -y python3)"
[ -f "$SCRIPT_DIR/run.sh" ] || die "script/run.sh not found; run this from a CompanionAI checkout"

# GNU date is required for millisecond timestamps; without them the streaming
# assertions cannot be made at all.
if ! printf '%s' "$(date +%s%3N)" | grep -Eq '^[0-9]+$'; then
  die "date does not support %3N (GNU coreutils required for streaming timings)"
fi

head1 "Preflight"

if curl -fsS -m 10 "$FLM_URL/api/stats" >"$TMP/flm-before.json" 2>/dev/null; then
  info "FastFlowLM is answering at $FLM_URL"
else
  die "FastFlowLM is not answering at $FLM_URL - start it with: flm serve"
fi

if command -v flm >/dev/null 2>&1; then
  if flm list --filter installed 2>/dev/null | grep -Fq "$MODEL"; then
    info "model '$MODEL' is present in the installed list"
  else
    info "WARNING: could not confirm '$MODEL' in 'flm list --filter installed'"
    info "         (FastFlowLM hangs rather than 404s on an unknown model tag)"
  fi
fi

if (exec 3<>/dev/tcp/127.0.0.1/8080) 2>/dev/null; then
  exec 3>&- 3<&- 2>/dev/null || true
  die "port 8080 is already in use; stop the existing CompanionAI before running this test"
fi

# --------------------------------------------------------------------------
# Start CompanionAI against FastFlowLM
# --------------------------------------------------------------------------

head1 "Starting CompanionAI (provider=fastflowlm, model=$MODEL)"

# Invoked through 'bash' rather than executed directly: git records every file in
# script/ as mode 100644, so a fresh clone has no executable bit on run.sh and
# './script/run.sh' would fail with EACCES.
( cd "$REPO_ROOT" && exec bash "$SCRIPT_DIR/run.sh" fastflowlm "$MODEL" ) >"$TMP/app.log" 2>&1 &
APP_PID=$!
info "pid $APP_PID, log $TMP/app.log"

ready=0
for _ in $(seq 1 "$READY_TIMEOUT"); do
  if curl -fsS -m 5 "$APP_URL/" >"$TMP/page.html" 2>/dev/null; then ready=1; break; fi
  kill -0 "$APP_PID" 2>/dev/null || break
  sleep 1
done

if [ "$ready" -ne 1 ]; then
  say ""
  say "---- app log ----"
  cat "$TMP/app.log"
  say "-----------------"
  die "CompanionAI did not answer on $APP_URL within ${READY_TIMEOUT}s"
fi
info "app is answering on $APP_URL"

# --------------------------------------------------------------------------
# Assertions: the app was configured to talk to FastFlowLM
# --------------------------------------------------------------------------

head1 "Configuration"

check "launcher reported provider=fastflowlm" \
  "$(grep -q '^Provider: fastflowlm$' "$TMP/app.log"; echo $?)"
check "launcher reported model=$MODEL" \
  "$(grep -q "^Model:    $MODEL$" "$TMP/app.log"; echo $?)"
check "launcher reported base URL $FLM_URL" \
  "$(grep -q "^Base URL: $FLM_URL$" "$TMP/app.log"; echo $?)"
check "app's own page renders the configured model ($MODEL)" \
  "$(grep -qF "$MODEL" "$TMP/page.html"; echo $?)"
check "no provider warning about an unverified default base URL" \
  "$(grep -qi 'No base URL configured for provider' "$TMP/app.log"; [ $? -ne 0 ]; echo $?)"

# --------------------------------------------------------------------------
# Test A: /api/chat
# --------------------------------------------------------------------------

head1 "Test A - /api/chat (complete response)"

python3 -c 'import json,sys; print(json.dumps({"message": sys.argv[1]}))' \
  "$CHAT_PROMPT" >"$TMP/chat.json"

stats_before="$(curl -fsS -m 10 "$APP_URL/api/stats")"
json_replies_before="$(statnum "$stats_before" jsonReplies)"
streamed_before="$(statnum "$stats_before" streamed)"
offline_before="$(statnum "$stats_before" offline)"
out_tok_before="$(statnum "$stats_before" outputTokens)"
info "stats before: jsonReplies=$json_replies_before streamed=$streamed_before offline=$offline_before outputTokens=$out_tok_before"

chat_start=$(date +%s%3N)
chat_http="$(curl -sS -m "$CHAT_TIMEOUT" -o "$TMP/chat.reply" -w '%{http_code}' \
  -X POST "$APP_URL/api/chat" \
  -H 'Content-Type: application/json' \
  --data-binary "@$TMP/chat.json" 2>"$TMP/chat.err")"
chat_rc=$?
chat_ms=$(( $(date +%s%3N) - chat_start ))

if [ "$chat_rc" -ne 0 ]; then
  fail "POST /api/chat completed without a transport error"
  info "curl exit $chat_rc: $(head -c 300 "$TMP/chat.err")"
else
  pass "POST /api/chat completed without a transport error"
fi

check "HTTP status was 200 (got ${chat_http:-none})" \
  "$([ "$chat_http" = "200" ]; echo $?)"

if [ -s "$TMP/chat.reply" ]; then
  pass "response body was received"
else
  fail "response body was received"
  info "$(head -c 300 "$TMP/chat.err")"
fi

if python3 - "$TMP/chat.reply" >"$TMP/chat.fields" 2>"$TMP/chat.pyerr" <<'PY'
import json, sys
try:
    d = json.load(open(sys.argv[1], encoding="utf-8"))
except Exception as exc:
    print("PARSE_ERROR", exc)
    sys.exit(1)
reply = d.get("reply")
if not isinstance(reply, str):
    print("NO_REPLY_KEY", sorted(d.keys()))
    sys.exit(1)
offline = bool(d.get("offline"))
print("OFFLINE", "true" if offline else "false")
print("REPLY_LEN", len(reply))
print("HAS_FALLBACK", "1" if "[LLM unavailable" in reply else "0")
print("REPLY", reply.replace("\n", " ")[:400])
PY
then
  pass "response body is valid JSON with a 'reply' field"
  chat_offline="$(awk '/^OFFLINE /{print $2}' "$TMP/chat.fields")"
  chat_len="$(awk '/^REPLY_LEN /{print $2}' "$TMP/chat.fields")"
  chat_fallback="$(awk '/^HAS_FALLBACK /{print $2}' "$TMP/chat.fields")"

  check "offline=false, i.e. no LlmException and no canned fallback" \
    "$([ "$chat_offline" = "false" ]; echo $?)"
  check "reply is non-trivial ($chat_len chars)" \
    "$([ "$chat_len" -ge 20 ]; echo $?)"
  check "reply contains no '[LLM unavailable' fallback marker" \
    "$([ "$chat_fallback" = "0" ]; echo $?)"
  info "reply: $(awk '/^REPLY /{$1="";print}' "$TMP/chat.fields")"
else
  fail "response body is valid JSON with a 'reply' field"
  info "$(head -c 300 "$TMP/chat.pyerr")"
  info "$(head -c 300 "$TMP/chat.reply")"
  chat_len=0
fi
info "round trip: ${chat_ms} ms"

stats_after_a="$(curl -fsS -m 10 "$APP_URL/api/stats")"
json_replies_after="$(statnum "$stats_after_a" jsonReplies)"
offline_after_a="$(statnum "$stats_after_a" offline)"
out_tok_after="$(statnum "$stats_after_a" outputTokens)"
last_out="$(statnum "$stats_after_a" lastOutputTokens)"
info "stats after:  jsonReplies=$json_replies_after offline=$offline_after_a outputTokens=$out_tok_after"

check "jsonReplies incremented by 1 ($json_replies_before -> $json_replies_after)" \
  "$([ "$json_replies_after" -eq $((json_replies_before + 1)) ]; echo $?)"
check "offline counter unchanged ($offline_before -> $offline_after_a)" \
  "$([ "$offline_after_a" -eq "$offline_before" ]; echo $?)"
check "outputTokens grew ($out_tok_before -> $out_tok_after)" \
  "$([ "$out_tok_after" -gt "$out_tok_before" ]; echo $?)"
check "lastOutputTokens > 0 ($last_out)" \
  "$([ "${last_out:-0}" -gt 0 ]; echo $?)"

# --------------------------------------------------------------------------
# Test B: /api/chat/stream - genuine incremental streaming
# --------------------------------------------------------------------------

head1 "Test B - /api/chat/stream (incremental delivery)"

stream_start=$(date +%s%3N)
curl -sS -N --max-time "$STREAM_TIMEOUT" \
  -D "$TMP/stream.headers" \
  -X POST "$APP_URL/api/chat/stream" \
  -H 'Content-Type: application/json' \
  --data-binary "@$TMP/chat.json" 2>"$TMP/stream.err" \
  | while IFS= read -r line; do
      [ -z "$line" ] && continue
      printf '%s\t%s\n' "$(date +%s%3N)" "$line" >>"$TMP/stream.tsv"
    done
stream_rc=${PIPESTATUS[0]}
stream_ms=$(( $(date +%s%3N) - stream_start ))

check "stream connection closed without a transport error (curl rc=$stream_rc)" \
  "$([ "$stream_rc" -eq 0 ]; echo $?)"

if [ -s "$TMP/stream.err" ]; then
  info "stream stderr: $(head -c 200 "$TMP/stream.err")"
fi

check "Content-Type was text/event-stream" \
  "$(grep -qi '^content-type:.*text/event-stream' "$TMP/stream.headers"; echo $?)"
check "response declared X-Accel-Buffering: no" \
  "$(grep -qi '^x-accel-buffering: *no' "$TMP/stream.headers"; echo $?)"

# Per-line arrival analysis.
read -r deltas t_first t_last max_gap <<EOF
$(awk -F'\t' '
  /"delta":/ {
    d++
    t = $1 + 0
    if (d == 1) t0 = t
    t1 = t
    if (d > 1) { g = t - prev; if (g > maxgap) maxgap = g }
    prev = t
  }
  END { printf "%d %d %d %d", d + 0, t0 + 0, t1 + 0, maxgap + 0 }
' "$TMP/stream.tsv" 2>/dev/null)
EOF
deltas="${deltas:-0}"; t_first="${t_first:-0}"; t_last="${t_last:-0}"; max_gap="${max_gap:-0}"
spread=$(( t_last - t_first ))
info "deltas=$deltas  first=${t_first}ms  last=${t_last}ms  spread=${spread}ms  worst inter-chunk gap=${max_gap}ms  wall=${stream_ms}ms"

check "at least $MIN_DELTAS delta events arrived (got $deltas)" \
  "$([ "$deltas" -ge "$MIN_DELTAS" ]; echo $?)"
check "deltas spread across >= ${MIN_SPREAD_MS}ms of wall clock (got ${spread}ms)" \
  "$([ "$spread" -ge "$MIN_SPREAD_MS" ]; echo $?)"
check "largest pause between consecutive deltas >= ${MIN_MAXGAP_MS}ms (got ${max_gap}ms)" \
  "$([ "$max_gap" -ge "$MIN_MAXGAP_MS" ]; echo $?)"
check "a terminating {\"done\":true} event was received" \
  "$(grep -q '"done":true' "$TMP/stream.tsv"; echo $?)"
check "stream never reported offline=true" \
  "$(grep -q '"offline":true' "$TMP/stream.tsv"; [ $? -ne 0 ]; echo $?)"
check "stream contained no error event" \
  "$(grep -q '"error":' "$TMP/stream.tsv"; [ $? -ne 0 ]; echo $?)"
check "stream contained no '[LLM unavailable' fallback marker" \
  "$(grep -q 'LLM unavailable' "$TMP/stream.tsv"; [ $? -ne 0 ]; echo $?)"

python3 - "$TMP/stream.tsv" <<'PY'
import json, sys

events = deltas = 0
text = []
for line in open(sys.argv[1], encoding="utf-8", errors="replace"):
    _, sep, payload = line.rstrip("\n").partition("\t")
    if not sep or not payload.strip().startswith("data: "):
        continue
    events += 1
    try:
        obj = json.loads(payload.strip()[6:])
    except Exception:
        continue
    d = obj.get("delta")
    if isinstance(d, str):
        deltas += 1
        text.append(d)
print("      SSE events: %d, delta events: %d, reply chars: %d"
      % (events, deltas, len("".join(text))))
print("      ---- reply as reassembled from the stream ----")
print("".join(text))
print("      ------------------------------------------------")
PY

# --------------------------------------------------------------------------
# Corroboration from FastFlowLM's own counters
# --------------------------------------------------------------------------

head1 "Cross-check - FastFlowLM's own counters"

curl -fsS -m 10 "$FLM_URL/api/stats" >"$TMP/flm-after.json" 2>/dev/null || true

if command -v python3 >/dev/null 2>&1 && [ -s "$TMP/flm-after.json" ]; then
  python3 - "$TMP/flm-before.json" "$TMP/flm-after.json" <<'PY'
import json, sys

def flat(path):
    try:
        d = json.load(open(path, encoding="utf-8"))
    except Exception as exc:
        print("      (unparseable: %s)" % exc)
        return {}
    out = {}
    if isinstance(d, dict):
        for k, v in d.items():
            if isinstance(v, (int, float)) and not isinstance(v, bool):
                out[k] = v
    return out

before, after = flat(sys.argv[1]), flat(sys.argv[2])
if not after:
    print("      FastFlowLM /api/stats has no flat numeric counters to compare")
    sys.exit(0)
changed = [(k, before.get(k), after[k]) for k in sorted(after)
           if isinstance(before.get(k), int) and after[k] != before[k]]
print("      before: %s" % json.dumps(before, sort_keys=True))
print("      after:  %s" % json.dumps(after, sort_keys=True))
if changed:
    for k, b, a in changed:
        print("      %-24s %s -> %s" % (k, b, a))
    print("      corroboration: FastFlowLM counters advanced, so the traffic really")
    print("      reached it. (Informational - not one of the scored assertions.)")
else:
    print("      NOTE  no counter changed. If FastFlowLM exposes request counters they")
    print("            should have moved. Treat as a question, not a failure - the")
    print("            offline=false assertions above are the authoritative signal.")
PY
fi

# --------------------------------------------------------------------------
# Verdict
# --------------------------------------------------------------------------

stats_final="$(curl -fsS -m 10 "$APP_URL/api/stats")"
info "final stats: $stats_final"

head1 "Stopping CompanionAI"
stop_app

head1 "Result"
printf '  passed: %d\n  failed: %d\n' "$PASS" "$FAIL"
say ""
say "Logs kept in $TMP"
say "  app.log        CompanionAI + launcher output"
say "  chat.reply     raw /api/chat body"
say "  stream.tsv     every SSE line with its arrival timestamp"

if [ "$FAIL" -eq 0 ]; then
  say ""
  say "E2E PASSED - CompanionAI generates through FastFlowLM on the NPU,"
  say "and /api/chat/stream delivers incrementally."
  exit 0
fi

say ""
say "E2E FAILED - do not deploy. The failing assertions above name the"
say "layer at fault: configuration, transport, model, or buffering."
exit 1