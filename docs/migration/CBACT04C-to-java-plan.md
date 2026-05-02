# Migration Plan: `CBACT04C.cbl` → Java

**Source program:** `app/cbl/CBACT04C.cbl` — the **interest calculator** batch program of CardDemo. Driven by `app/jcl/INTCALC.jcl` as part of the nightly cycle (`scripts/run_full_batch.sh` → `INTCALC.jcl`).

**Target location (mandatory):** `app/java/batch_processing_workflow/`

**Goal:** A Java port that produces **byte-for-byte identical output** to the original on the same input fixtures, verified by an automated test that diffs against a GnuCOBOL-runnable reference build.

This plan is purely a plan. **No code is written until approved.**

---

## 1. Source contract — what `CBACT04C` does

### 1.1 Inputs

| DD name (JCL)       | COBOL `SELECT` | Org / Access     | Record (copybook) | Notes |
| ------------------- | -------------- | ---------------- | ----------------- | ----- |
| `TCATBALF`          | `TCATBAL-FILE` | INDEXED / SEQUENTIAL  | 50 bytes (`CVTRA01Y` — `TRAN-CAT-BAL-RECORD`) | Driving file. Walked sequentially by ascending key. |
| `XREFFILE` + `XREFFIL1` | `XREF-FILE` | INDEXED / RANDOM, AIX on `ACCT-ID` | 50 bytes (`CVACT03Y` — `CARD-XREF-RECORD`) | Looked up by account ID via the alternate index. |
| `ACCTFILE`          | `ACCOUNT-FILE` | INDEXED / RANDOM, OPEN I-O | 300 bytes (`CVACT01Y` — `ACCOUNT-RECORD`) | Random read **and** REWRITE in place. |
| `DISCGRP`           | `DISCGRP-FILE` | INDEXED / RANDOM | 50 bytes (`CVTRA02Y` — `DIS-GROUP-RECORD`) | Lookup by composite key with `'DEFAULT'` fallback. |
| `PARM=…`            | `EXTERNAL-PARMS` | n/a            | `PARM-LENGTH` `S9(4) COMP` + `PARM-DATE` `X(10)` | JCL passes `PARM='2022071800'` (10 chars). Used as transaction-ID prefix. |

### 1.2 Outputs

| DD name (JCL) | COBOL `SELECT` | Org | Record | Notes |
| ------------- | -------------- | --- | ------ | ----- |
| `TRANSACT`    | `TRANSACT-FILE` | SEQUENTIAL | 350 bytes (`CVTRA05Y` — `TRAN-RECORD`) | RECFM=F LRECL=350. Append-only stream of new interest-charge transactions. |
| `ACCTFILE` (REWRITE) | `ACCOUNT-FILE` | INDEXED I-O | 300 bytes | Each affected account's record is REWRITTEN with `ACCT-CURR-BAL += accumulated_interest`, `ACCT-CURR-CYC-CREDIT = 0`, `ACCT-CURR-CYC-DEBIT = 0`. |

### 1.3 Algorithm (paragraph map)

```
PROCEDURE DIVISION USING EXTERNAL-PARMS:
    OPEN all 5 files (paragraphs 0000–0400). ABEND on non-'00'.
    LOOP UNTIL EOF:
        READ next TCATBAL record (1000-TCATBALF-GET-NEXT)
        IF EOF: PERFORM 1050-UPDATE-ACCOUNT one last time. BREAK.
        IF TRANCAT-ACCT-ID changed (or first run):
            IF NOT first time: PERFORM 1050-UPDATE-ACCOUNT (rewrite previous acct)
            Reset WS-TOTAL-INT = 0
            Cache new acct-id; READ ACCOUNT (1100), READ XREF by AIX (1110)
        Build DISCGRP key from (acct.group_id, tcatbal.type_cd, tcatbal.cd)
        READ DISCGRP (1200); on file-status '23' (not found), retry with group_id='DEFAULT' (1200-A)
        IF DIS-INT-RATE != 0:
            COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
            WS-TOTAL-INT += WS-MONTHLY-INT
            WRITE new TRAN-RECORD to TRANSACT (1300-B-WRITE-TX)
            PERFORM 1400-COMPUTE-FEES (no-op currently — "To be implemented")
    CLOSE all files (9000–9400). ABEND on non-'00'.
```

### 1.4 Numeric formats (verified against copybooks)

| Field             | PIC               | Scale | Storage in source ASCII files |
| ----------------- | ----------------- | ----- | ----------------------------- |
| `TRAN-CAT-BAL`    | `S9(09)V99`       | 2     | 11 chars zoned-decimal w/ trailing sign overpunch |
| `ACCT-CURR-BAL`   | `S9(10)V99`       | 2     | 12 chars zoned-decimal w/ trailing sign overpunch |
| `ACCT-CURR-CYC-CREDIT` / `-DEBIT` | `S9(10)V99` | 2 | 12 chars zoned-decimal |
| `ACCT-CREDIT-LIMIT` / `-CASH-CREDIT-LIMIT` | `S9(10)V99` | 2 | 12 chars |
| `DIS-INT-RATE`    | `S9(04)V99`       | 2     | 6 chars zoned-decimal w/ trailing sign overpunch |
| `WS-MONTHLY-INT`  | `S9(09)V99`       | 2     | working storage (in-memory) |
| `WS-TOTAL-INT`    | `S9(09)V99`       | 2     | working storage |
| `TRAN-AMT`        | `S9(09)V99`       | 2     | 11 chars zoned-decimal in TRANSACT output |

**Critical: zoned decimal sign overpunch.** The ASCII fixture files preserve the EBCDIC overpunch convention by carrying the same character. The trailing digit of a signed numeric field is replaced by:

| Sign | Digit | Overpunch char |
| ---- | ----- | -------------- |
| `+`  | 0–9   | `{ A B C D E F G H I` |
| `-`  | 0–9   | `} J K L M N O P Q R` |

E.g. account `00000000001`'s `ACCT-CURR-BAL` field is `00000001940{` → digits `000000019400`, sign `+` → value `+0000000194.00` = $194.00.

**This must be replicated in the Java I/O layer or byte-for-byte equivalence is impossible.** A round-trip-tested codec is the linchpin of the whole port.

### 1.5 Other patterns to preserve

- **Compute truncation** — `COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200` has **no `ROUNDED` clause**, so the result truncates toward zero on the destination scale of 2. Java equivalent must use `RoundingMode.DOWN`, not `HALF_UP`.
- **Transaction ID format** — `STRING PARM-DATE, WS-TRANID-SUFFIX DELIMITED BY SIZE INTO TRAN-ID`. PARM-DATE is 10 chars; WS-TRANID-SUFFIX is `PIC 9(06)` zero-padded; produces a 16-byte ID like `2022071800000001`.
- **DB2 timestamp** (`Z-GET-DB2-FORMAT-TIMESTAMP`) — produces `YYYY-MM-DD-HH.MM.SS.MM0000` (26 chars). Note the **subsecond field is only 2 real digits** (centiseconds from `FUNCTION CURRENT-DATE`'s `MIL` field) padded with `0000`.
- **ABEND path** — `9999-ABEND-PROGRAM` calls `CEE3ABD` with code 999. Java equivalent: throw a dedicated `BatchAbendException(999)` and exit non-zero.
- **First-record guard** — `WS-FIRST-TIME = 'Y'` initially; suppresses the spurious `1050-UPDATE-ACCOUNT` on the very first account-id-change check.
- **Final-account flush** — when `1000-TCATBALF-GET-NEXT` reports EOF, the outer loop hits the `ELSE PERFORM 1050-UPDATE-ACCOUNT` branch once more. The Java port must do the same.
- **Disclosure-group fallback chain** — the `'23'` (not found) path moves `'DEFAULT'` into the group-id and re-reads. If that also fails, the program ABENDs (non-`'00'` non-`'23'` is fatal).

---

## 2. Equivalence requirements (acceptance criteria)

Two artifacts must match byte-for-byte between the COBOL run and the Java run on the same input fixture:

1. **`TRANSACT-FILE` output** — every byte of every 350-byte record. This is the primary check.
2. **Final state of `ACCTFILE`** — every byte of every 300-byte record after REWRITEs are applied.

A third artifact, `WS-RECORD-COUNT` (printed via `DISPLAY`), should match too but is informational.

**Acceptance test:** `diff -q transact.cobol.dat transact.java.dat` returns silently AND `diff -q acctdata.cobol.out acctdata.java.out` returns silently.

**Two known sources of divergence to neutralize:**
- **Timestamps** (`TRAN-ORIG-TS`, `TRAN-PROC-TS`) — both pull from `FUNCTION CURRENT-DATE` at runtime, so they will differ between runs. The Java port and the COBOL reference must accept an injected/fixed timestamp for the test (a `--ts=2025-04-29-12.00.00.000000` flag, or env var). For the COBOL reference, replace `Z-GET-DB2-FORMAT-TIMESTAMP` with a constant move during test builds (compile-time `-D` symbol).
- **Date in transaction ID** — `PARM-DATE` is already controlled by JCL. Pass the same value to both runs.

---

## 3. Target architecture

### 3.1 Layout

```
app/java/batch_processing_workflow/
├── pom.xml                                    Maven build (Java 17, JUnit 5)
├── README.md                                  Build / run / test entry point
├── HOW-TO-TEST.md                             Env setup + verification steps
├── src/
│   ├── main/java/com/carddemo/batch/interest/
│   │   ├── InterestCalculator.java            main(), mirrors PROCEDURE DIVISION
│   │   ├── domain/
│   │   │   ├── AccountRecord.java             Java record, mirrors CVACT01Y
│   │   │   ├── CardXrefRecord.java            mirrors CVACT03Y
│   │   │   ├── DisclosureGroupRecord.java     mirrors CVTRA02Y
│   │   │   ├── TransactionCategoryBalanceRecord.java   mirrors CVTRA01Y
│   │   │   └── TransactionRecord.java         mirrors CVTRA05Y
│   │   ├── io/
│   │   │   ├── ZonedDecimalCodec.java         encode/decode signed zoned decimal
│   │   │   ├── FixedWidthFormat.java          generic fixed-width record codec
│   │   │   ├── AccountFile.java               load + lookup + flush back
│   │   │   ├── CardXrefFile.java              load + lookup by acct-id (AIX)
│   │   │   ├── DisclosureGroupFile.java       lookup with DEFAULT fallback
│   │   │   ├── TcatBalReader.java             sequential reader
│   │   │   └── TransactionWriter.java         sequential writer (350-byte records)
│   │   └── util/
│   │       ├── Db2Timestamp.java              format YYYY-MM-DD-HH.MM.SS.MM0000
│   │       └── BatchAbendException.java       maps to exit code 999
│   └── test/
│       ├── java/com/carddemo/batch/interest/
│       │   ├── ZonedDecimalCodecTest.java     round-trip every overpunch char
│       │   ├── FixedWidthFormatTest.java
│       │   ├── DomainRecordRoundTripTest.java parse → format → identical bytes
│       │   ├── DecimalArithmeticTest.java     verifies COBOL truncation rule
│       │   ├── Db2TimestampTest.java          exact byte format
│       │   ├── DisclosureGroupFallbackTest.java
│       │   ├── InterestCalculatorTest.java    end-to-end against fixtures
│       │   └── GoldenFileEquivalenceIT.java   diff vs. COBOL-reference output
│       └── resources/
│           ├── fixtures/
│           │   ├── input/                     copies of app/data/ASCII/* (read-only)
│           │   ├── synthetic/                 hand-crafted edge-case fixtures
│           │   └── expected/                  golden outputs (regenerated by tools/regen-golden.sh)
└── cobol-reference/
    ├── README.md                              How and why this exists
    ├── CBACT04C-portable.cbl                  Sequential-only port of CBACT04C
    └── tools/
        ├── build.sh                           Compiles via `cobc -x -free`
        ├── run.sh                             Runs against fixtures/input
        └── regen-golden.sh                    build → run → copy outputs to fixtures/expected
```

### 3.2 Decisions (defaults — can override)

| Decision                | Default                                     | Why                                                              |
| ----------------------- | ------------------------------------------- | ---------------------------------------------------------------- |
| Java version            | **Java 17** LTS                             | Records, sealed types, pattern matching for switch — all helpful |
| Build system            | **Maven**                                   | Most familiar for batch-style single-module projects             |
| Test framework          | **JUnit 5 + AssertJ**                       | Standard. AssertJ for byte-array fluent diffs                    |
| Decimal type            | **`BigDecimal`**                            | Mandatory for monetary correctness; never `double`/`float`       |
| Char encoding for I/O   | **ISO-8859-1 (single-byte, identity)**      | Treats overpunches as literal bytes; avoids any Unicode mangling |
| Reference COBOL runtime | **GnuCOBOL 3.x** with sequential files only | No Berkeley DB / VSAM dependency for the test environment        |

If you'd prefer Java 21 or Gradle, flag it now — only the `pom.xml` step changes.

### 3.3 Why a sequential-file COBOL port

The original `CBACT04C` requires **VSAM INDEXED** access for 4 of its 5 files. GnuCOBOL can support indexed files via Berkeley DB (`--with-db`), but that requires extra system packages and a loader to convert the ASCII text fixtures into BDB-format files — a meaningful setup burden for a test environment.

A **functionally equivalent sequential port** (`CBACT04C-portable.cbl`) avoids the BDB dependency entirely:

- `ACCOUNT-FILE`, `XREF-FILE`, `DISCGRP-FILE` change from `INDEXED RANDOM` to `LINE SEQUENTIAL INPUT` and are loaded into `WORKING-STORAGE` `OCCURS DEPENDING ON` tables at startup; lookups become `SEARCH ALL` (or linear `SEARCH`) on those tables.
- `ACCOUNT-FILE` REWRITEs become updates to the in-memory account table; the table is dumped back out as `LINE SEQUENTIAL OUTPUT` at end-of-job.
- `TCATBAL-FILE` was already walked sequentially — change is just `INDEXED` → `LINE SEQUENTIAL`.
- `TRANSACT-FILE` was already sequential — no change.
- All business logic (paragraphs `1050`, `1100`, `1110`, `1200`, `1200-A`, `1300`, `1300-B`, `Z-GET-DB2-FORMAT-TIMESTAMP`, `9999`) is **copied verbatim**.

Because the business logic is unchanged, the portable COBOL is a faithful behavioral oracle. The port lives under `cobol-reference/` to make its role obvious — it is **not** for production, only for generating golden output to diff the Java against.

---

## 4. Step-by-step migration steps

Each step is intended to be a discrete commit. Steps 1–8 are Java; step 9 is the COBOL reference; steps 10–11 are tests + docs.

| #  | Step                                                                 | Acceptance |
| -- | -------------------------------------------------------------------- | ---------- |
| 1  | Scaffold Maven project at `app/java/batch_processing_workflow/`       | `mvn -q compile` succeeds on empty `App.java` |
| 2  | Implement `ZonedDecimalCodec` (decode + encode, all overpunch chars)  | Unit test round-trips every char in `{}A–IJ–R0–9` and every signed value at scale 2 |
| 3  | Implement `FixedWidthFormat` helper (typed field-spec → byte-array codec) | Unit test parses & re-emits a synthetic 50-byte record byte-identically |
| 4  | Define 5 domain records (Java `record` types) mirroring copybooks     | Compiled, immutable, with `with*` methods where mutation is needed (account balance) |
| 5  | Implement `TcatBalReader` (sequential 50-byte chunks)                 | Reads `tcatbal.txt` and yields N records identical to `wc -c` / 50 |
| 6  | Implement `TransactionWriter` (sequential 350-byte writer)            | Appends 350 bytes per record, encoded byte-identically to a hand-crafted fixture |
| 7  | Implement `AccountFile` (load → `Map<acctId, AccountRecord>` → mutable; flush back to LSEQ) and `CardXrefFile` (load → `Map<acctId, CardXrefRecord>` via the AIX semantic) | Round-trip load/dump of `acctdata.txt` with no mutation produces identical bytes |
| 8  | Implement `DisclosureGroupFile` with `lookup(group, type, cat)` method that retries with `'DEFAULT'` on miss; ABEND on miss-of-DEFAULT | Unit test covers found / fallback-found / fallback-missing branches |
| 9  | Implement `Db2Timestamp` formatter; allow injection of a fixed `Instant` for tests | Unit test asserts exact 26-byte string for known instants |
| 10 | Implement `InterestCalculator.main()` with explicit pseudocode comments tying back to COBOL paragraph numbers | Wired up, runs end-to-end on the project fixtures, produces non-empty `TRANSACT.dat` and `acctdata.out` |
| 11 | Write `cobol-reference/CBACT04C-portable.cbl` (sequential port) + `tools/build.sh` + `tools/run.sh` + `tools/regen-golden.sh` | `bash regen-golden.sh` produces `fixtures/expected/transact.dat` and `fixtures/expected/acctdata.out` from the same `fixtures/input/*` |
| 12 | Write `GoldenFileEquivalenceIT` integration test that runs `InterestCalculator` against `fixtures/input/`, then `Files.mismatch(expected, actual) == -1L` for both output files | Test passes locally |
| 13 | Write `README.md` (build/run) and `HOW-TO-TEST.md` (env prereqs, regen workflow, troubleshooting) | Both files complete; a fresh dev can follow `HOW-TO-TEST.md` end-to-end |

**Estimated effort:** ~2–3 focused engineering days for steps 1–13. The bulk of the surprise is in step 2 (overpunch codec) and step 11 (COBOL port + correct GnuCOBOL invocation).

---

## 5. Logic equivalency safeguards

These are the spots where an incorrect Java implementation will silently produce divergent output. Each must have a dedicated unit test.

### 5.1 Decimal arithmetic

```java
// CORRECT — matches COBOL truncation
BigDecimal monthly = balance.multiply(rate)
                            .divide(new BigDecimal("1200"), 2, RoundingMode.DOWN);
// WRONG — RoundingMode.HALF_UP differs from COBOL on rates ending in 5
```

Test fixtures must include cases where rounding mode matters (e.g. balance `123.45`, rate `1.50%` → monthly = `123.45 * 1.50 / 1200 = 0.1543125...` → truncated to `0.15` (DOWN), not `0.15` either way; better edge: balance `100.00`, rate `0.07%` → `0.0058...` → `0.00` truncated).

### 5.2 Sign overpunch encoding on output

When a `BigDecimal` value is encoded back to a 12-byte zoned decimal field for the rewritten account, the **last digit must carry the sign overpunch** matching COBOL's standard zoned decimal layout. A round-trip test (`parse → format`) on every input account record must emit exactly the input bytes.

### 5.3 Transaction-ID composition

```
PARM-DATE (10 bytes) + WS-TRANID-SUFFIX (6-digit zero-padded) = 16 bytes
```

Suffix increments on every `1300-B-WRITE-TX`, never resets across accounts. A test must verify suffix `000001`, `000002`, … and that the byte length is exactly 16 (not 15, not 17).

### 5.4 First-time guard

If TCATBAL has 1 record only, exactly one `UPDATE-ACCOUNT` must fire (the EOF flush), not zero, not two. Synthetic fixture: 1 TCATBAL record, 1 account.

### 5.5 EOF-final-flush

If TCATBAL has N records all for distinct accounts, exactly N `UPDATE-ACCOUNT` calls must fire. Synthetic fixture covers this.

### 5.6 Disclosure-group fallback

Three branches:
- `(group, type, cat)` found → use that rate
- `(group, type, cat)` not found → retry with `(DEFAULT, type, cat)` → use that rate
- `(DEFAULT, type, cat)` also not found → ABEND

All three need fixtures.

### 5.7 Zero-rate skip

If `DIS-INT-RATE = 0`, no transaction is written and `WS-TOTAL-INT` is unchanged. Synthetic fixture must cover this — the project's `discgrp.txt` does have `00000{` (zero) entries (e.g. group `A000000002`, type `01`, cat `0001`).

---

## 6. Test strategy

### 6.1 Unit tests (fast, hermetic, no I/O)

Listed in section 3.1 under `src/test/java/`. Each test class targets one concern. Aim: 100% branch coverage for `ZonedDecimalCodec`, `Db2Timestamp`, `DisclosureGroupFile.lookup()`, and the decimal arithmetic in `InterestCalculator`.

### 6.2 Round-trip integration test

For every fixture input file:

```
parse(fixture/input/foo.txt) → record[]
record[].forEach(format) → bytes[]
assert bytes equals original file bytes (byte-for-byte)
```

This catches encoding bugs in the I/O layer **without** running any business logic.

### 6.3 Golden-file integration test (the byte-for-byte guarantee)

```
1. cd cobol-reference && bash tools/regen-golden.sh
   → builds CBACT04C-portable.cbl with GnuCOBOL
   → runs it against fixtures/input/
   → copies its output into fixtures/expected/
   (Run once when the COBOL changes, or when fixtures change.)

2. mvn test  (in app/java/batch_processing_workflow/)
   → runs InterestCalculatorTest which:
     • Copies fixtures/input/ to a temp dir (so we don't mutate the originals)
     • Sets a fixed timestamp via Db2Timestamp.setFixedClock(...)
     • Sets PARM-DATE to a fixed test value
     • Runs InterestCalculator.main(...)
     • Asserts Files.mismatch(expected/transact.dat, temp/transact.dat) == -1L
     • Asserts Files.mismatch(expected/acctdata.out, temp/acctdata.out) == -1L
```

This is the central acceptance test. It is the only test that proves byte-for-byte parity with COBOL.

### 6.4 Synthetic edge-case fixtures

Hand-crafted small fixtures under `src/test/resources/fixtures/synthetic/`:

| Fixture          | Purpose                                                    |
| ---------------- | ---------------------------------------------------------- |
| `single-account/` | 1 account, 1 tcatbal record — verifies first-time guard + EOF flush |
| `multi-account/`  | 3 accounts, ~10 tcatbal records, mixed type/cat codes      |
| `default-group/`  | An account whose specific (group,type,cat) is missing → must fall back to DEFAULT |
| `zero-rate/`      | An account with a zero rate row → no transaction written for that row |
| `negative-balance/` | Balance with a `}` overpunch (negative zero) and `J–R` overpunches → exercises full sign-overpunch matrix |

Each synthetic fixture has its own expected outputs (regenerated from `CBACT04C-portable.cbl`).

### 6.5 What the user instructed: do not run the tests

All of the above is described in `HOW-TO-TEST.md` (deliverable, see section 7). The plan is to **write** the tests, not run them. The HOW-TO walks the user through the prerequisites (GnuCOBOL install, JDK 17, `mvn`) and the commands.

---

## 7. Documents to be produced during implementation

These get written as part of the migration work, **not** as part of this plan:

1. **`app/java/batch_processing_workflow/README.md`** — quick-start for a developer landing in the directory: what's here, how to build, how to run, where the tests live.
2. **`app/java/batch_processing_workflow/HOW-TO-TEST.md`** — full test workflow:
   - Prerequisites (`apt install gnucobol`, `sdkman install java 17.0.12-tem`, `mvn`)
   - One-time: `cd cobol-reference && bash tools/regen-golden.sh` (regenerates golden files)
   - Routine: `mvn test`
   - Interpreting failures: byte-offset of first diff, common causes (overpunch encoding, decimal rounding, timestamp injection)
   - Updating fixtures: when input data changes, regen the golden files
3. **`app/java/batch_processing_workflow/cobol-reference/README.md`** — explains why the portable COBOL exists, what was changed from the original, and warns against treating it as a production replacement.

After implementation, I'll also add a short pointer from `docs/cobol/batch-programs.md` (`CBACT04C` section) to this Java port and to `docs/migration/CBACT04C-to-java-plan.md` so the COBOL doc surfaces the migration.

---

## 8. Risks and open decisions

### 8.1 Risks

| Risk                                                                    | Mitigation                                                  |
| ----------------------------------------------------------------------- | ----------------------------------------------------------- |
| Sign-overpunch round-trip wrong on a handful of values → silent diff    | Codec test enumerates all 22 overpunch chars and ±0 / ±9999999.99 boundaries |
| `RoundingMode.HALF_UP` vs `DOWN` mismatch                               | Dedicated `DecimalArithmeticTest` with a value where they differ |
| `LINE SEQUENTIAL` strips trailing spaces in GnuCOBOL                    | Use `RECORD SEQUENTIAL` with explicit fixed `RECORD CONTAINS 50 CHARACTERS` clauses, OR accept LINE SEQUENTIAL with a configured terminator and re-pad in `regen-golden.sh` |
| Timestamps differ between COBOL run and Java run                        | Inject fixed timestamp on both sides. COBOL gets a compile-time switch (`-DTEST_FIXED_TS`) that replaces `Z-GET-DB2-FORMAT-TIMESTAMP` with a constant `MOVE` |
| Extension dirs already contain DB2/IMS COBOL — does Java port need to handle those? | **No.** Scope is `CBACT04C` only. Other batch programs are out of scope unless explicitly added |
| GnuCOBOL behavior for `READ ... INVALID KEY` differs subtly             | The portable port doesn't use `INVALID KEY` (no INDEXED files); the in-memory `SEARCH ALL` returns "not found" via `WHEN OTHER`, which we map to file-status `'23'` ourselves |

### 8.2 Open decisions to confirm before implementation

- **Java version** — defaulting to 17 LTS; OK with you?
- **Build system** — defaulting to Maven; OK with you?
- **Java package** — defaulting to `com.carddemo.batch.interest`; any house style to follow?
- **Whether to also produce a Berkeley-DB-indexed COBOL build** as Approach A for users who'd prefer to test the *original* `CBACT04C` unchanged — or stick with sequential-only port (Approach B) as planned. **Recommend B** unless you say otherwise.

---

## 9. Out of scope for this migration

- Other batch programs (`CBACT01–03C`, `CBTRN0xC`, `CBSTM03A/B`, `CBEXPORT`, `CBIMPORT`, `CBCUS01C`, `CSUTLDTC`).
- The online (`CO*`) programs.
- The three extension modules (`app-transaction-type-db2`, `app-vsam-mq`, `app-authorization-ims-db2-mq`).
- A general-purpose COBOL→Java framework. This migration is single-program; if a second batch program follows, the codec / format / timestamp utilities will be lifted into a shared module, but **only when we have two callers** to validate the abstraction.
- Production deployment / CI integration. The test plan is local-developer-runnable; CI hookup is a separate task.

---

## 10. Approval gate

Confirm the following before I start writing code:

1. **Approach B** (sequential-port COBOL reference) is the right path for the test oracle — yes/no.
2. Defaults in §3.2 (Java 17, Maven, JUnit 5 + AssertJ, ISO-8859-1) — accept or override.
3. Scope locked to `CBACT04C` only — yes/no.
4. Output location `app/java/batch_processing_workflow/` confirmed (yes per your request).

Once confirmed, I'll work through steps 1–13 in section 4, in order, one commit per step.
