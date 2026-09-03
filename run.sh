#!/bin/zsh

set -euo pipefail

AVD_NAME="BYD_Multimedia_with_Hardware_Controls"
EMULATOR_BIN="/Users/gabrielcarvalho/Library/Android/sdk/emulator/emulator"
ADB_BIN="/Users/gabrielcarvalho/Library/Android/sdk/platform-tools/adb"

if [[ ! -x "$EMULATOR_BIN" ]]; then
  echo "Android emulator not found at: $EMULATOR_BIN" >&2
  exit 1
fi

while read -r serial; do
  [[ -z "$serial" ]] && continue

  running_avd="$($ADB_BIN -s "$serial" emu avd name 2>/dev/null | head -n 1 | tr -d '\r')"
  if [[ "$running_avd" == "$AVD_NAME" ]]; then
    echo "The BYD test emulator is already running."
    exit 0
  fi
done < <($ADB_BIN devices 2>/dev/null | awk 'NR > 1 && $2 == "device" { print $1 }')

exec "$EMULATOR_BIN" -avd "$AVD_NAME" -no-boot-anim
