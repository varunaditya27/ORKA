#!/usr/bin/env bash
# Pushes whichever .litertlm file is configured in model_config.yaml (repo root) to the device
# and tells ORKA to use it — the whole "which model am I testing" decision lives in that one
# local file on this PC, not on the phone. To switch models: edit model_path in
# model_config.yaml, re-run this script.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG_FILE="$REPO_ROOT/model_config.yaml"
DEVICE_DIR="/data/local/tmp"
DEVICE_SELECTOR_FILE="orka_model_config.txt"

if [[ ! -f "$CONFIG_FILE" ]]; then
    echo "error: $CONFIG_FILE not found." >&2
    echo "Copy model_config.yaml.example to model_config.yaml and set model_path first." >&2
    exit 1
fi

MODEL_PATH=$(grep '^model_path:' "$CONFIG_FILE" | sed -E 's/^model_path:[[:space:]]*//; s/[[:space:]]+$//; s/^"(.*)"$/\1/')

if [[ -z "$MODEL_PATH" ]]; then
    echo "error: model_path is not set in $CONFIG_FILE" >&2
    exit 1
fi

# Expand a leading ~ since the YAML value isn't shell-evaluated.
MODEL_PATH="${MODEL_PATH/#\~/$HOME}"

if [[ ! -f "$MODEL_PATH" ]]; then
    echo "error: no file at $MODEL_PATH (check model_path in $CONFIG_FILE)" >&2
    exit 1
fi

MODEL_FILENAME="$(basename "$MODEL_PATH")"
LOCAL_SIZE="$(stat -c%s "$MODEL_PATH" 2>/dev/null || stat -f%z "$MODEL_PATH")"
REMOTE_SIZE="$(adb shell "stat -c%s $DEVICE_DIR/$MODEL_FILENAME 2>/dev/null" | tr -d '\r' || true)"

if [[ "$REMOTE_SIZE" == "$LOCAL_SIZE" ]]; then
    echo "$MODEL_FILENAME already on device with matching size — skipping re-push."
else
    echo "Pushing $MODEL_FILENAME ($(( LOCAL_SIZE / 1024 / 1024 )) MB) to $DEVICE_DIR ..."
    adb push "$MODEL_PATH" "$DEVICE_DIR/$MODEL_FILENAME"
fi

echo "Selecting $MODEL_FILENAME as the active model..."
adb shell "echo $MODEL_FILENAME > $DEVICE_DIR/$DEVICE_SELECTOR_FILE"

echo "Done. Relaunch ORKA (or tap \"Retry Model Provisioning\" in Settings) to pick it up."
