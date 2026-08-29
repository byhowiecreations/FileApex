#!/usr/bin/env bash
# Re-apply +x in Git's index and on disk for wrapper/scripts.
# Run after git pull on macOS/Linux if a Windows commit dropped 100755 → 100644.
# On Windows (Git Bash), this fixes the index so the next push keeps +x for Mac clones.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

EXECUTABLE_PATHS=(
  gradlew
  scripts/build_complete.sh
  scripts/build_intel.sh
  scripts/build_silicon.sh
  macos/scripts/build_extensions.sh
  macos/scripts/build_tray_bridge.sh
  macos/scripts/embed_extensions.sh
  macos/scripts/register_extensions.sh
  macos/scripts/unique_main_uuid.sh
  scripts/ensure-git-executable-bits.sh
)

if ! git rev-parse --git-dir >/dev/null 2>&1; then
  echo "Not a git repository: $ROOT"
  exit 1
fi

fixed_index=0
fixed_disk=0
for path in "${EXECUTABLE_PATHS[@]}"; do
  [[ -f "$path" ]] || continue
  if git ls-files --error-unmatch "$path" >/dev/null 2>&1; then
    mode="$(git ls-files -s -- "$path" | awk '{print $1}')"
    if [[ "$mode" != "100755" ]]; then
      git update-index --chmod=+x -- "$path"
      fixed_index=$((fixed_index + 1))
      echo "index +x: $path (was $mode)"
    fi
  fi
  if [[ ! -x "$path" ]]; then
    chmod +x "$path"
    fixed_disk=$((fixed_disk + 1))
    echo "disk +x: $path"
  fi
done

if [[ "$fixed_index" -eq 0 && "$fixed_disk" -eq 0 ]]; then
  echo "All tracked scripts already executable in index and on disk."
else
  echo "Fixed index=$fixed_index disk=$fixed_disk — commit if index changed (git status)."
fi
