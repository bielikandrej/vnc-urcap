#!/usr/bin/env bash
# STIMBA VNC URCap — public-API regression wrapper
# =================================================
#
# Unpacks a .urcap artifact and runs regress_signatures.py against the wiki
# baseline. Fails (exit 2) if any public-API signature under sk.stimba.*
# disappeared or changed — the symptom pattern behind the 3.0.0→3.0.4
# hotfix storm.
#
# Usage:
#   ./regress.sh [--write] [path/to/file.urcap]
#
#   --write   regenerate baseline from the given .urcap (after intentional
#             API change). Commit the resulting wiki/public-api-baseline.txt.
#
# Without arguments, auto-detects the newest target/*.urcap. Runs in diff
# mode by default.
#
# Exit codes:
#   0  OK, no drift
#   1  usage / environment error (no python, no .urcap)
#   2  drift detected — blocking regression

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
BASELINE="${REPO_DIR}/wiki/public-api-baseline.txt"
PARSER="${SCRIPT_DIR}/regress_signatures.py"

mode="diff"
artifact=""
for arg in "$@"; do
    case "$arg" in
        --write) mode="write" ;;
        --help|-h)
            sed -n '2,20p' "$0"
            exit 0
            ;;
        -*) echo "unknown flag: $arg" >&2; exit 1 ;;
        *)  artifact="$arg" ;;
    esac
done

if [[ -z "$artifact" ]]; then
    # pick the most recently modified .urcap in target/
    artifact="$(ls -1t "${REPO_DIR}"/target/*.urcap 2>/dev/null | head -1 || true)"
    if [[ -z "$artifact" ]]; then
        echo "regress: no .urcap supplied and none found in target/" >&2
        echo "regress: pass a path or run \`mvn package\` first" >&2
        exit 1
    fi
fi

if [[ ! -f "$artifact" ]]; then
    echo "regress: artifact not found: $artifact" >&2
    exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
    echo "regress: python3 not on PATH" >&2
    exit 1
fi

# Unpack into a disposable tmpdir — we just want the class files
workdir="$(mktemp -d -t urcap-regress-XXXXXX)"
trap 'rm -rf "$workdir"' EXIT

echo "[regress] unpacking $(basename "$artifact") -> $workdir"
# .urcap is a zip — use python zipfile to avoid unzip dependency (macOS minimal)
python3 - "$artifact" "$workdir" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    z.extractall(sys.argv[2])
PY

# Find the .jar inside the .urcap (OSGi bundle), unpack that too
bundle_jar="$(find "$workdir" -maxdepth 2 -name '*.jar' | head -1 || true)"
classes_root="$workdir"
if [[ -n "$bundle_jar" ]]; then
    jar_dir="${workdir}/_bundle"
    mkdir -p "$jar_dir"
    echo "[regress] unpacking bundle jar $(basename "$bundle_jar")"
    python3 - "$bundle_jar" "$jar_dir" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    z.extractall(sys.argv[2])
PY
    classes_root="$jar_dir"
fi

if [[ "$mode" == "write" ]]; then
    python3 "$PARSER" --write "$classes_root" > "$BASELINE"
    echo "[regress] wrote baseline: $BASELINE ($(wc -l < "$BASELINE") lines)"
    echo "[regress] commit this file as part of the intentional API change"
    exit 0
fi

python3 "$PARSER" --diff "$BASELINE" "$classes_root"
