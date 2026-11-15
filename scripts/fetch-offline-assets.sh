#!/usr/bin/env bash
set -euo pipefail
ASSETS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/app/src/main/assets"
mkdir -p "$ASSETS_DIR"
fetch() {
  local name="$1" url="$2" expected="$3" target="$ASSETS_DIR/$1"
  if [[ -f "$target" ]] && [[ "$(sha256sum "$target" | cut -d' ' -f1)" == "$expected" ]]; then echo "Already verified: $name"; return; fi
  curl --fail --location --retry 3 --continue-at - --output "$target" "$url"
  echo "$expected  $target" | sha256sum --check --status || { echo "Checksum mismatch: $name" >&2; rm -f "$target"; exit 1; }
}
fetch 'library-lite.zip' 'https://github.com/anonnerdfrenzy/survival-llm/releases/download/library-2026-10-28/library-lite.zip.000' '0e9fc46df8288c1aa9a81b87386acd224963b8a1b2fd44d51078089d3dee8686'
fetch 'Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task' 'https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/6c237a59eedeb06a821b21f0a59b03d346ac8bc3/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task?download=true' 'b7e77f73f73c994bc8a0f067a9bee7ba5db446dcb51c9fe0243e5600482e3478'
