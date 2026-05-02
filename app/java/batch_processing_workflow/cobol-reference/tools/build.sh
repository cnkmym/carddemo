#!/usr/bin/env bash
# Builds the portable COBOL oracle (CBACT04P) using GnuCOBOL.
# Output binary lands in cobol-reference/build/cbact04p.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REF_DIR="$(cd "$HERE/.." && pwd)"
REPO_ROOT="$(cd "$REF_DIR/../../../.." && pwd)"
CPY_DIR="$REPO_ROOT/app/cpy"

if ! command -v cobc >/dev/null 2>&1; then
  echo "ERROR: cobc (GnuCOBOL) is not installed."
  echo "Install with: sudo apt-get install -y gnucobol  (Debian/Ubuntu)"
  echo "          or: brew install gnu-cobol            (macOS)"
  exit 2
fi

if [ ! -d "$CPY_DIR" ]; then
  echo "ERROR: Copybook directory not found: $CPY_DIR"
  exit 2
fi

mkdir -p "$REF_DIR/build"

echo "Compiling CBACT04P with GnuCOBOL..."
# -fsign=EBCDIC forces mainframe-style sign-overpunch encoding
# ({, A-I for positive 0-9; }, J-R for negative) on signed zoned-decimal
# fields, matching what real IBM Enterprise COBOL would produce. Without
# this, GnuCOBOL omits the overpunch on positive zero, which would break
# byte-for-byte equivalence with the Java port.
cobc -x -O2 \
     -fsign=EBCDIC \
     -I "$CPY_DIR" \
     -o "$REF_DIR/build/cbact04p" \
     "$REF_DIR/CBACT04P.cbl"

echo "Built: $REF_DIR/build/cbact04p"
