#!/usr/bin/env bash
set -euo pipefail
ASSETS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/app/src/main/assets"
mkdir -p "$ASSETS_DIR"
fetch() {
  local name="$1" url="$2" expected="$3" target="$ASSETS_DIR/$1"
  if [[ -f "$target" ]] && [[ "$(sha256sum "$target" | cut -d' ' -f1)" == "$expected" ]]; then echo "Already verified: $name"; return; fi
  rm -f "$target"
  curl --fail --location --retry 3 --output "$target" "$url"
  echo "$expected  $target" | sha256sum --check --status || { echo "Checksum mismatch: $name" >&2; rm -f "$target"; exit 1; }
}
fetch 'library-lite.zip' 'https://github.com/anonnerdfrenzy/survival-llm/releases/download/library-2026-10-28/library-lite.zip.000' '0e9fc46df8288c1aa9a81b87386acd224963b8a1b2fd44d51078089d3dee8686'
fetch 'Qwen2_0.5B_Instruct.litertlm' 'https://huggingface.co/litert-community/Qwen2-0.5B-Instruct/resolve/0e209e1/Qwen2_0.5B_Instruct.litertlm?download=true' '0f01cc004b8eb62b92ba6be85ed05a248ba0d2f78af94c4949b313eccfb4c157'
