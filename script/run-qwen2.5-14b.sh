#!/usr/bin/env bash
set -euo pipefail

MODEL="qwen2.5:14b"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

"$SCRIPT_DIR/switch-model.sh" "$MODEL"

if [ ! -x "build/install/CompanionAI/bin/CompanionAI" ]; then
  echo "Building CompanionAI distribution (first run)..."
  ./gradlew installDist
fi

if (exec 3<>/dev/tcp/127.0.0.1/8080) 2>/dev/null; then
  exec 3>&- 3<&-
  echo "WARNING: something is already listening on http://127.0.0.1:8080" >&2
  echo "         CompanionAI may already be running - stop it before starting another." >&2
fi

export COMPANION_AI_OPTS="-Dcampanionai.model=$MODEL"
echo "Launching CompanionAI with model: $MODEL"
exec build/install/CompanionAI/bin/CompanionAI "$@"