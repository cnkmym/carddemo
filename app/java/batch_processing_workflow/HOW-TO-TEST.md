# HOW-TO-TEST — Verifying the CBACT04C Java port

This is the operator's guide to running the test suite for this port. The
goal of the suite is to demonstrate **byte-for-byte output equivalence**
between the Java port and a portable GnuCOBOL build of the same program
on the same input fixtures.

## What's tested

| Layer                         | Test class                              | Needs GnuCOBOL? |
| ----------------------------- | --------------------------------------- | --------------- |
| Sign-overpunch codec          | `ZonedDecimalCodecTest`                 | No              |
| Fixed-width text/numeric I/O  | `FixedWidthFormatTest`                  | No              |
| Domain record round-trips     | `DomainRecordRoundTripTest`             | No              |
| Decimal arithmetic truncation | `DecimalArithmeticTest`                 | No              |
| DB2 timestamp formatting      | `Db2TimestampTest`                      | No              |
| Disclosure-group fallback     | `DisclosureGroupFallbackTest`           | No              |
| End-to-end on synthetic data  | `InterestCalculatorTest`                | No              |
| Golden-file vs COBOL oracle   | `GoldenFileEquivalenceIT`               | **Yes**         |

The unit tests run with just a JDK + Maven. The IT additionally needs
GnuCOBOL to build the oracle. The IT auto-skips when the golden files
are absent, so missing GnuCOBOL never makes the build fail — it just
skips the equivalence check.

## Prerequisites

| Tool               | Version | Install (Debian/Ubuntu)         | Install (macOS)            |
| ------------------ | ------- | ------------------------------- | -------------------------- |
| JDK                | 17 (LTS) | `apt-get install openjdk-17-jdk` | `brew install openjdk@17` |
| Maven              | ≥ 3.9   | `apt-get install maven`         | `brew install maven`       |
| GnuCOBOL (for IT)  | ≥ 3.x   | `apt-get install gnucobol`      | `brew install gnu-cobol`   |
| `diff`, `bash`     | any     | preinstalled                    | preinstalled               |

Verify:

```bash
java -version    # 17.x
mvn  -version
cobc --version   # 3.x (only needed for the IT)
```

## Workflow A — Unit tests only (no COBOL)

```bash
cd app/java/batch_processing_workflow
mvn -q test
```

Expected: all tests pass; the `GoldenFileEquivalenceIT` is in the
`failsafe` (`*IT`) phase and is not invoked by `test`. To make sure
nothing is silently skipped here, look for a "Tests run" line per test
class.

## Workflow B — Full byte-for-byte equivalence (one-time setup)

This is the workflow that actually proves the port matches COBOL. Run
it once after every change to `CBACT04P.cbl`, the input fixtures, or
the `InterestCalculator` Java code.

### Step 1 — Generate the golden output files

```bash
cd app/java/batch_processing_workflow
bash cobol-reference/tools/regen-golden.sh
```

What this does:

1. Runs `cobol-reference/tools/build.sh`, which calls
   `cobc -x -O2 -I ../../../cpy -o build/cbact04p CBACT04P.cbl`.
   Output: `cobol-reference/build/cbact04p`.
2. Runs `cobol-reference/tools/run.sh` against
   `src/test/resources/fixtures/input/`. Inside a temp working
   directory, the script copies inputs to the names CBACT04P expects
   (`acctdata.dat`, `tcatbal.dat`, `cardxref.dat`, `discgrp.dat`),
   runs `./cbact04p 2025-04-29` with `TEST_FIXED_TS=2025-04-29-12.00.00.000000`,
   then copies the produced `transact.dat` and `acctdata.out` into
   `src/test/resources/fixtures/expected/`.

After success, `ls src/test/resources/fixtures/expected/` should show:

```
acctdata.out
transact.dat
```

### Step 2 — Run the IT

```bash
mvn -q verify
```

This runs all unit tests (`*Test`) plus the integration test
(`*IT`). The `GoldenFileEquivalenceIT`:

1. Freezes `Db2Timestamp` to the same fixed instant the COBOL run used.
2. Runs `InterestCalculator` against the same input fixtures, with
   `PARM-DATE = "2025-04-29"`.
3. Asserts `Files.mismatch(expected/transact.dat, java/transact.dat) == -1L`.
4. Asserts `Files.mismatch(expected/acctdata.out, java/acctdata.out) == -1L`.

A passing run looks like:

```
[INFO] Running com.carddemo.batch.interest.GoldenFileEquivalenceIT
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

### Workflow B (no GnuCOBOL): IT is skipped

If you don't have GnuCOBOL installed and skip Step 1, the IT skips:

```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 1
```

`mvn -q verify` still succeeds — the unit tests carry the build, and
the IT is gated by `@EnabledIf("goldenFilesPresent")`.

## Triaging a failure

### `GoldenFileEquivalenceIT` fails with a byte mismatch

The assertion message includes the byte offset and a snippet of the
mismatched region from each file. Common causes, in decreasing order
of likelihood:

1. **Sign-overpunch encoding regression** — likely if the offset lands
   inside a `S9(...)V99` field. Re-check `ZonedDecimalCodec.encode()`
   and run `mvn -q test -Dtest=ZonedDecimalCodecTest`.
2. **Decimal-rounding regression** — `RoundingMode.DOWN` got changed
   to `HALF_UP` somewhere. Run `DecimalArithmeticTest` first, then
   grep for `RoundingMode.HALF_UP` in `src/main`.
3. **Timestamp drift** — both sides should use
   `2025-04-29-12.00.00.000000`. If the offset is at byte 278 (TRAN-ORIG-TS)
   or 304 (TRAN-PROC-TS) of a transaction record, check that the IT
   actually called `Db2Timestamp.useFixedClock(...)` and that
   `TEST_FIXED_TS` env var was honored when regenerating the golden.
4. **Account REWRITE order** — `acctdata.out` order should match input
   order. If the offset is at the start of a record, check that
   `AccountFile.flush()` iterates `insertionOrder`, not `records.keySet()`.
5. **Trailing newline** — both writers must emit exactly one `\n` per
   record. Off-by-one here produces a length mismatch flagged by the
   assertion message ("Expected length=X, actual length=Y").

### Mid-text COBOL diagnostics in `transact.dat`

If `transact.dat` contains stray `'START OF EXECUTION...'` strings, the
COBOL `DISPLAY` output got mixed into the data file. Check `run.sh`
isn't redirecting stdout to the data file by accident.

## Updating the inputs

When the input fixtures change (new test cases, hand-crafted edge cases,
or copies refreshed from `app/data/ASCII/`):

1. Replace files in `src/test/resources/fixtures/input/`.
2. Re-run `bash cobol-reference/tools/regen-golden.sh` to refresh the
   expected outputs.
3. `mvn -q verify`.
4. Commit both the new inputs and the new expected outputs together.

## What is NOT being tested here

- **Mainframe-VSAM-vs-Java equivalence.** The oracle is GnuCOBOL with
  sequential files. Mainframe RECFM=F output uses no terminators, so
  byte-equivalence to mainframe output requires a separate strip-newlines
  step that's out of scope for this port.
- **Concurrency.** The original is single-threaded; the port is too.
  No concurrency test.
- **Recovery / restart.** No checkpointing in either the original or
  the port — a mid-run failure restarts from scratch.
