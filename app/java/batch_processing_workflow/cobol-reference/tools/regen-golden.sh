#!/usr/bin/env bash
# Regenerates golden outputs in src/test/resources/fixtures/expected/
# by building and running the COBOL oracle against
# src/test/resources/fixtures/input/.
#
# Run this whenever:
#   - CBACT04P.cbl changes
#   - the input fixtures change

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REF_DIR="$(cd "$HERE/.." && pwd)"
PROJECT_DIR="$(cd "$REF_DIR/.." && pwd)"
INPUT_DIR="$PROJECT_DIR/src/test/resources/fixtures/input"
EXPECTED_DIR="$PROJECT_DIR/src/test/resources/fixtures/expected"

bash "$HERE/build.sh"
bash "$HERE/run.sh" "$INPUT_DIR" "$EXPECTED_DIR" "2025-04-29"

echo "Golden files refreshed:"
ls -la "$EXPECTED_DIR"
