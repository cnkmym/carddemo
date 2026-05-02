#!/usr/bin/env bash
# Runs CBACT04P against a fixture directory and writes outputs to a
# work directory.
#
# Usage:
#   tools/run.sh <input-dir> <output-dir> [PARM-DATE]
#
# <input-dir>  must contain: acctdata.txt, tcatbal.txt, cardxref.txt, discgrp.txt
# <output-dir> will receive: transact.dat, acctdata.out

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REF_DIR="$(cd "$HERE/.." && pwd)"
BIN="$REF_DIR/build/cbact04p"
DEFAULT_FIXED_TS="2025-04-29-12.00.00.000000"

if [ ! -x "$BIN" ]; then
  echo "ERROR: $BIN not found. Run tools/build.sh first."
  exit 2
fi

if [ "$#" -lt 2 ]; then
  echo "Usage: $(basename "$0") <input-dir> <output-dir> [PARM-DATE]"
  exit 1
fi

INPUT_DIR="$(cd "$1" && pwd)"
OUTPUT_DIR="$2"
PARM_DATE="${3:-2025-04-29}"

mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR="$(cd "$OUTPUT_DIR" && pwd)"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

cp "$INPUT_DIR/acctdata.txt" "$WORK_DIR/acctdata.dat"
cp "$INPUT_DIR/tcatbal.txt"  "$WORK_DIR/tcatbal.dat"
cp "$INPUT_DIR/cardxref.txt" "$WORK_DIR/cardxref.dat"
cp "$INPUT_DIR/discgrp.txt"  "$WORK_DIR/discgrp.dat"

(
  cd "$WORK_DIR"
  # COB_LS_FIXED=Y preserves trailing spaces on LINE SEQUENTIAL output
  # so each record is written at its full FD record length (rather than
  # stripped at the last non-space byte). Required for byte-for-byte
  # match against the Java port, which always emits full-length records.
  COB_LS_FIXED=Y \
  TEST_FIXED_TS="${TEST_FIXED_TS:-$DEFAULT_FIXED_TS}" \
    "$BIN" "$PARM_DATE"
)

cp "$WORK_DIR/transact.dat"  "$OUTPUT_DIR/transact.dat"
cp "$WORK_DIR/acctdata.out"  "$OUTPUT_DIR/acctdata.out"

echo "Outputs written to: $OUTPUT_DIR"
