#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() {
  echo "Usage: switch-model.sh <model>" >&2
  echo "       switch-model.sh gemma4:12b" >&2
  exit 1
}

MODEL="${1:-}"
if [ -z "$MODEL" ]; then
  usage
fi

if ! command -v ollama >/dev/null 2>&1; then
  echo "ERROR: 'ollama' command was not found on PATH." >&2
  exit 1
fi

if ! ollama list >/dev/null 2>&1; then
  echo "ERROR: Ollama is not running. Start it with 'ollama serve'." >&2
  exit 1
fi

FOUND=0
while IFS= read -r name; do
  if [ "$name" = "$MODEL" ]; then
    FOUND=1
    break
  fi
done < <(ollama list | tail -n +2 | awk '{print $1}')

if [ "$FOUND" -ne 1 ]; then
  echo "ERROR: Model '$MODEL' is not installed in Ollama." >&2
  echo "Available models:" >&2
  ollama list >&2
  exit 1
fi

CURRENT_FILE="$SCRIPT_DIR/current-model.txt"
OLD="(none)"
if [ -f "$CURRENT_FILE" ]; then
  OLD="$(cat "$CURRENT_FILE")"
fi

printf '%s\n' "$MODEL" > "$CURRENT_FILE"
echo "Switch model: $OLD  ->  $MODEL"