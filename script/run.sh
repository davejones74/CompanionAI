#!/usr/bin/env bash
#
# Unified CompanionAI launcher.
#
#   ./script/run.sh <provider> <model> [extra gradle/java args]
#
# Replaces the nine hard-coded script/run-<model>.sh files. Those wrappers still
# work, but each one only ever configured Ollama, so none of them could start
# CompanionAI against FastFlowLM without editing the source.
#
# Providers:
#   ollama      Base URL http://localhost:11434, per-request num_ctx honoured.
#   fastflowlm  Base URL http://127.0.0.1:52625. FastFlowLM fixes the context
#               length at `flm serve` time and has no per-request equivalent,
#               so this launcher never passes numCtx.
#
# The base URL is always passed explicitly, even where it matches the provider's
# default. LlmProviderFactory warns when a non-Ollama provider runs on a default
# URL the operator never set, and that warning is correct: the value is only
# known to be right on one host. Set COMPANIONAI_LLM_BASE_URL to override.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

usage() {
  cat >&2 <<'EOF'
Usage: script/run.sh <provider> <model> [extra args]

  script/run.sh ollama gemma4:12b
  script/run.sh fastflowlm qwen2.5-it:3b

Providers:
  ollama      Ollama native endpoint (also serves /v1, but the native path is
              what has been used throughout development).
  fastflowlm  FastFlowLM OpenAI-compatible endpoint on loopback.

Environment:
  COMPANIONAI_LLM_BASE_URL  Override the base URL for the chosen provider.
  COMPANIONAI_LLM_NUM_CTX   Context window. Ollama only; ignored with a warning
                            on providers that cannot honour it per request.

EOF
  exit 1
}

PROVIDER="${1:-}"
if [ -z "$PROVIDER" ]; then
  usage
fi

MODEL="${2:-}"
if [ -z "$MODEL" ]; then
  usage
fi
shift 2

case "$PROVIDER" in
  ollama)     DEFAULT_BASE_URL="http://localhost:11434" ;;
  fastflowlm) DEFAULT_BASE_URL="http://127.0.0.1:52625" ;;
  -h|--help)  usage ;;
  *)
    echo "ERROR: unknown provider '$PROVIDER'." >&2
    echo "       Expected 'ollama' or 'fastflowlm'." >&2
    exit 1
    ;;
esac

BASE_URL="${COMPANIONAI_LLM_BASE_URL:-$DEFAULT_BASE_URL}"

# Pre-flight: is this model actually installed on the chosen runtime?
#
# This check earns its place because FastFlowLM does not reject an unknown model
# tag - the request just hangs (see docs/X1Pro-FastFlowLM-Validation.md R7/R8).
# A typo therefore fails silently and slowly unless it is caught here.
#
# It never blocks the launch. An unreachable runtime is the normal state of a
# machine where Ollama is not installed, and refusing to start on that basis
# would be wrong; the check only fails when the runtime is present and
# definitively does not have the model.
check_model_installed() {
  local runtime="$1" model="$2"

  case "$runtime" in
    ollama)
      if ! command -v ollama >/dev/null 2>&1; then
        echo "NOTE: 'ollama' is not on PATH; skipping the installed-model check." >&2
        return 0
      fi
      if ! ollama list >/dev/null 2>&1; then
        echo "NOTE: Ollama is not responding; skipping the installed-model check." >&2
        return 0
      fi
      if ollama list | tail -n +2 | awk '{print $1}' | grep -Fxq -- "$model"; then
        return 0
      fi
      echo "ERROR: model '$model' is not installed in Ollama." >&2
      echo "       Available models:" >&2
      ollama list >&2
      return 1
      ;;
    fastflowlm)
      if ! command -v flm >/dev/null 2>&1; then
        echo "NOTE: 'flm' is not on PATH; skipping the installed-model check." >&2
        return 0
      fi
      if [ "${SKIP_MODEL_CHECK:-0}" = "1" ]; then
        echo "NOTE: SKIP_MODEL_CHECK=1; skipping the installed-model check." >&2
        return 0
      fi
      # Substring match on the raw listing, never a column parse. 'flm list'
      # decorates each row (status glyphs, dashes) and the tag is not reliably
      # in any particular field, so awk/grep-pipeline parsing silently yields
      # junk like '-' and then reports a perfectly good model as missing.
      local raw
      raw="$(flm list --filter installed 2>/dev/null || true)"
      if [ -z "${raw//[[:space:]]/}" ]; then
        echo "NOTE: 'flm list --filter installed' produced no output;" >&2
        echo "      skipping the installed-model check." >&2
        return 0
      fi
      if printf '%s\n' "$raw" | grep -Fq -- "$model"; then
        return 0
      fi
      echo "ERROR: model '$model' does not appear in the installed-model list." >&2
      printf '%s\n' "$raw" | sed 's/^/         | /' >&2
      echo >&2
      echo "       FastFlowLM does not reject an unknown tag - it hangs until" >&2
      echo "       timeout, so this check exists to catch exactly that." >&2
      echo "       If your model IS shown above, this check is misreading the" >&2
      echo "       output format: fix check_model_installed in script/run.sh, or" >&2
      echo "       re-run with SKIP_MODEL_CHECK=1." >&2
      return 1
      ;;
  esac
}

check_model_installed "$PROVIDER" "$MODEL"

# Record the active model for the dev-host tooling.
if [ "$PROVIDER" = "ollama" ]; then
  "$SCRIPT_DIR/switch-model.sh" "$MODEL"
else
  printf '%s\n' "$MODEL" > "$SCRIPT_DIR/current-model.txt"
fi

if [ ! -x build/install/CompanionAI/bin/CompanionAI ]; then
  echo "Building CompanionAI distribution (first run)..."
  ./gradlew installDist
fi

if (exec 3<>/dev/tcp/127.0.0.1/8080) 2>/dev/null; then
  exec 3>&- 3<&-
  echo "WARNING: something is already listening on http://127.0.0.1:8080" >&2
  echo "         CompanionAI may already be running - stop it before starting another." >&2
fi

OPTS="-Dcompanionai.llm.provider=$PROVIDER"
OPTS="$OPTS -Dcompanionai.llm.model=$MODEL"
OPTS="$OPTS -Dcompanionai.llm.baseUrl=$BASE_URL"
if [ -n "${COMPANIONAI_LLM_NUM_CTX:-}" ]; then
  # Passed through deliberately even for FastFlowLM, where the factory will warn
  # and ignore it. Silently dropping it would hide the operator's mistake.
  OPTS="$OPTS -Dcompanionai.llm.numCtx=$COMPANIONAI_LLM_NUM_CTX"
fi

export COMPANION_AI_OPTS="$OPTS"

echo "Provider: $PROVIDER"
echo "Model:    $MODEL"
echo "Base URL: $BASE_URL"
echo "Launching CompanionAI..."
exec build/install/CompanionAI/bin/CompanionAI "$@"