#!/usr/bin/env bash
# Downloads the bundled model once and verifies its SHA-256.
# Usage: scripts/fetch_model.sh <destination.gguf>
set -euo pipefail

DEST="${1:?usage: fetch_model.sh <destination.gguf>}"
URL="${MORROW_MODEL_URL:-https://huggingface.co/ggml-org/functiongemma-270m-it-GGUF/resolve/main/functiongemma-270m-it-q8_0.gguf}"
SHA="${MORROW_MODEL_SHA:-83940d4dd9676710856f43523bed096164a595a96f6b34771610a03937de5270}"
SIZE="${MORROW_MODEL_BYTES:-291557792}"

STAMP="${MORROW_MODEL_STAMP:-$DEST.sha256.ok}"
# Fast path: verified once, untouched since.
if [[ -f "$DEST" && -f "$STAMP" && "$(stat -c %s "$DEST")" == "$SIZE" ]] &&
   [[ "$(stat -c %Y "$STAMP")" -ge "$(stat -c %Y "$DEST")" ]]; then
  exit 0
fi

if [[ -f "$DEST" ]] && [[ "$(stat -c %s "$DEST")" == "$SIZE" ]]; then
  if [[ "$(sha256sum "$DEST" | cut -d' ' -f1)" == "$SHA" ]]; then
    touch "$STAMP"
    echo "model already present: $DEST"
    exit 0
  fi
  echo "model checksum mismatch, re-downloading" >&2
  rm -f "$DEST"
fi

mkdir -p "$(dirname "$DEST")"
echo "downloading $(basename "$DEST") (278 MB, one time)"
curl -L --fail --progress-bar -o "$DEST.part" "$URL"
mv "$DEST.part" "$DEST"

ACTUAL="$(sha256sum "$DEST" | cut -d' ' -f1)"
if [[ "$ACTUAL" != "$SHA" ]]; then
  echo "checksum mismatch: got $ACTUAL, expected $SHA" >&2
  rm -f "$DEST"
  exit 1
fi
touch "$STAMP"
echo "verified $DEST"
