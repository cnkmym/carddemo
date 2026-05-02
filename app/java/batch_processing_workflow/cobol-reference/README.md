# cobol-reference/ — GnuCOBOL oracle for the Java port

This directory holds a **portable variant** of `app/cbl/CBACT04C.cbl` used
solely as a behavioral oracle for the Java port living in
`../src/main/java/`. It exists for testing — **not** for production.

## Why this exists

The original `CBACT04C` requires VSAM **INDEXED** access for 4 of its 5
files. Stock GnuCOBOL ships without indexed-file support unless built
against Berkeley DB and given a loader to convert ASCII text fixtures
into BDB-format files. To keep the test environment lightweight, we
ported `CBACT04C` to use **LINE SEQUENTIAL** files only, with the same
business logic preserved verbatim.

## What changed from the original

| Aspect | Original `CBACT04C` | Portable `CBACT04P` |
| ------ | ------------------- | -------------------- |
| `ACCOUNT-FILE`, `XREF-FILE`, `DISCGRP-FILE` | `INDEXED RANDOM` | Loaded into in-memory `WORKING-STORAGE` tables at startup |
| Lookups | `READ … KEY IS …` | Linear `PERFORM VARYING` over the table |
| `ACCOUNT-FILE` write-back | `REWRITE` in place | Updates the in-memory table; whole table dumped to `acctdata.out` at end |
| `TCATBAL-FILE`, `TRANSACT-FILE` | already sequential | unchanged (just `INDEXED` → `LINE SEQUENTIAL`) |
| `CEE3ABD` ABEND | mainframe LE service | `STOP RUN RETURNING 999` |
| Timestamp | `FUNCTION CURRENT-DATE` | injected from `TEST_FIXED_TS` env var when set, else `CURRENT-DATE` |

All business logic — the main loop, `1050-UPDATE-ACCOUNT`,
`1100-GET-ACCT-DATA`, `1110-GET-XREF-DATA`, `1200-GET-INTEREST-RATE`
(with the `'DEFAULT'` fallback), `1300-COMPUTE-INTEREST`,
`1300-B-WRITE-TX`, `1400-COMPUTE-FEES` (no-op), the first-time guard,
the EOF-final-flush — is preserved byte-for-byte from the original.

## Files in this directory

- `CBACT04P.cbl` — the portable COBOL oracle.
- `tools/build.sh` — compiles via `cobc -x -O2 -I ../../../cpy …`.
- `tools/run.sh <input-dir> <output-dir> [PARM-DATE]` — runs the binary
  against an input-fixtures directory and writes `transact.dat` and
  `acctdata.out` to the output directory. Honors `TEST_FIXED_TS` env var.
- `tools/regen-golden.sh` — convenience wrapper: builds, then runs against
  `src/test/resources/fixtures/input/` and writes outputs into
  `src/test/resources/fixtures/expected/`. Re-run whenever the COBOL
  source or input fixtures change.

## Prerequisites

- **GnuCOBOL ≥ 3.x** (`apt install gnucobol` on Debian/Ubuntu;
  `brew install gnu-cobol` on macOS).

The build script reaches the central copybooks at
`../../../cpy` (i.e. `app/cpy/`) — the same copybooks the original
`CBACT04C` uses, no duplication.

## Do not treat this as a production port

`CBACT04P` is byte-for-byte equivalent to `CBACT04C` only on the
**outputs** for the same inputs. It is **not** wire-compatible with
mainframe VSAM datasets, RECFM=F record formats, or the LE runtime.
The Java port is the production target.
