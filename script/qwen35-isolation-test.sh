#!/usr/bin/env bash
# TEMPORARY DIAGNOSTIC - Qwen 3.5 / FastFlowLM isolation test.
# Bypasses CompanionAI entirely and hits FastFlowLM's OpenAI-compatible API
# with a minimal request. Run on the X1 Pro:  bash script/qwen35-isolation-test.sh
set -u
BASE="${LLM_BASE_URL:-http://127.0.0.1:52625}"
MODEL="${1:-qwen3.5:4b}"

run() {
  local label="$1" think="$2"
  echo "=================================================================="
  echo "TEST: ${label}  (model=${MODEL}, think=${think})"
  echo "------------------------------------------------------------------"
  curl -sS -m 180 "${BASE}/v1/chat/completions" \
    -H "Content-Type: application/json" \
    -d "{
      \"model\": \"${MODEL}\",
      \"stream\": false,
      \"temperature\": 0.6,
      \"max_tokens\": 1024,
      \"think\": ${think},
      \"messages\": [
        {\"role\": \"system\", \"content\": \"You are a friendly assistant. Answer briefly and directly.\"},
        {\"role\": \"user\", \"content\": \"Hi\"}
      ]
    }" | python3 -m json.tool 2>/dev/null || echo "(unparseable response above)"
  echo
}

# Same minimal request but with blank assistant messages in history, replicating
# the poisoned-history state CompanionAI can get into.
run_poisoned() {
  local label="$1" think="$2"
  echo "=================================================================="
  echo "TEST: ${label}  (model=${MODEL}, think=${think}, blank assistant history)"
  echo "------------------------------------------------------------------"
  curl -sS -m 180 "${BASE}/v1/chat/completions" \
    -H "Content-Type: application/json" \
    -d "{
      \"model\": \"${MODEL}\",
      \"stream\": false,
      \"temperature\": 0.6,
      \"max_tokens\": 1024,
      \"think\": ${think},
      \"messages\": [
        {\"role\": \"system\", \"content\": \"You are a friendly assistant. Answer briefly and directly.\"},
        {\"role\": \"user\", \"content\": \"Hello again\"},
        {\"role\": \"assistant\", \"content\": \"\"},
        {\"role\": \"user\", \"content\": \"Hello\"},
        {\"role\": \"assistant\", \"content\": \"\"},
        {\"role\": \"user\", \"content\": \"Hi\"}
      ]
    }" | python3 -m json.tool 2>/dev/null || echo "(unparseable response above)"
  echo
}

run "A: minimal, think=false" false
run "B: minimal, think=true"  true
run_poisoned "C: poisoned history, think=false" false
run_poisoned "D: poisoned history, think=true"  true

echo "Interpretation:"
echo "  - If A answers normally, Qwen3.5/FastFlowLM is fine and the bug is in"
echo "    CompanionAI's request (notably LLM_THINK=true in companionai.env)."
echo "  - If C/D reproduce the giant <think> loop but A/B do not, blank assistant"
echo "    messages in persisted history are the trigger."
echo "  - If even A loops, it is a Qwen 3.5 chat-template/FastFlowLM issue."
