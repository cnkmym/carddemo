# Migration Plan: `CBSTM03A.CBL` + `CBSTM03B.CBL` → Java

**Source programs:**
- `app/cbl/CBSTM03A.CBL` — the **statement creator**: business logic, formatting, two output streams (FB-80 plain text and FB-100 HTML).
- `app/cbl/CBSTM03B.CBL` — the **file-handler subprogram**: a generic dispatcher that opens / reads (sequential or keyed) / closes one of four KSDS files behind a single `CALL` interface.

The two programs are migrated as a pair because they are operationally inseparable: `CBSTM03A` performs no file I/O directly — every file operation goes through `CALL 'CBSTM03B' USING WS-M03B-AREA`.

**Driving JCL:** `app/jcl/CREASTMT.JCL` step `STEP040`. Earlier steps build a `(card-num, tran-id)`-keyed copy of the transaction master so this program can read it sequentially.

**Target location (mandatory):** `app/java/batch_processing_workflow/`

**Goal:** A Java port that produces **byte-for-byte identical output** to the COBOL run on both the FB-80 plain text statement file (`STMTFILE`) and the FB-100 HTML statement file (`HTMLFILE`), verified by an automated test that diffs against a GnuCOBOL-runnable reference build.

This plan is purely a plan. **No code is written until approved.**

---

## 1. Source contract — what the pair does

### 1.1 Inputs (all accessed via `CBSTM03B`)

| DD name | Org / Access | Record | Notes |
| ------- | ------------ | ------ | ----- |
| `TRNXFILE` | INDEXED / SEQUENTIAL | 350 bytes (`COSTM01` — `TRNX-RECORD`) | Composite key `TRNX-CARD-NUM + TRNX-ID` (32 bytes). Walked sequentially at start to load the in-memory transaction table. |
| `XREFFILE` | INDEXED / SEQUENTIAL | 50 bytes (`CVACT03Y`) | Walked sequentially in the main loop to drive statement generation. |
| `CUSTFILE` | INDEXED / RANDOM | 500 bytes — note: `CUSTREC` variant of `CVCUS01Y` (different field names) | Random read by `XREF-CUST-ID`. |
| `ACCTFILE` | INDEXED / RANDOM | 300 bytes (`CVACT01Y`) | Random read by `XREF-ACCT-ID`. |

### 1.2 Outputs (written directly by `CBSTM03A`)

| DD name | LRECL / RECFM | Notes |
| ------- | ------------- | ----- |
| `STMTFILE` | 80 / FB | Plain-text statement; one statement per account spans many lines |
| `HTMLFILE` | 100 / FB | HTML statement; same content as `STMTFILE` but wrapped in HTML tags |

### 1.3 Algorithm (paragraph map for `CBSTM03A`)

```
PROCEDURE DIVISION:

  Mainframe diagnostic prologue (skip in Java port):
    SET ADDRESS OF PSA-BLOCK TO PSAPTR
    Walk PSA → TCB → TIOT, DISPLAY each active DD name

  OPEN STMTFILE / HTMLFILE directly:
    OPEN OUTPUT STMTFILE
    OPEN OUTPUT HTMLFILE

  0000-START — uses ALTER + GO TO + EVALUATE WS-FL-DD to dispatch into
                    one of:
    8100-FILE-OPEN   (whose target is mutated by ALTER)
    8200-XREFFILE-OPEN
    8300-CUSTFILE-OPEN
    8400-ACCTFILE-OPEN
    8500-READTRNX-READ

  Phase A: load TRNXFILE into memory
    8100-TRNXFILE-OPEN (via CBSTM03B)
    8500-READTRNX-READ loops until EOF, packing rows into
        WS-CARD-TBL OCCURS 51 TIMES of (
            WS-CARDNUM PIC X(16),
            WS-TRAN-CNT PIC 9(02),
            WS-TRAN-TBL OCCURS 10 TIMES of TRNX-ROW)
        — i.e. up to 510 transactions across 51 cards

  Phase B: open the lookup files
    8200-XREFFILE-OPEN, 8300-CUSTFILE-OPEN, 8400-ACCTFILE-OPEN
        (all via CBSTM03B with 'O' op-code)

  Phase C: 1000-MAINLINE
    PERFORM 1000-XREFFILE-GET-NEXT (sequential read via CBSTM03B 'R')
    UNTIL EOF on XREFFILE:
        2000-CUSTFILE-GET (CBSTM03B 'K' keyed read on XREF-CUST-ID)
        3000-ACCTFILE-GET (CBSTM03B 'K' keyed read on XREF-ACCT-ID)
        5000-CREATE-STATEMENT:
            5100-WRITE-PLAINTEXT-HEADERS (multiple WRITE STMTFILE)
            5200-WRITE-HTML-HEADERS      (multiple WRITE HTMLFILE)
        4000-TRNXFILE-GET — walks WS-CARD-TBL for matching CARD-NUM,
                           emits 6000-WRITE-TRANS for each transaction:
            6000 writes one plaintext line + one HTML line per trans
        Footer/totals (text + HTML)

  CLOSE all 4 KSDS files via CBSTM03B 'C'
  CLOSE STMTFILE / HTMLFILE directly
  GOBACK
```

### 1.4 The `CBSTM03B` linkage interface — `WS-M03B-AREA`

The single linkage record passed on every `CALL`:

```
05  WS-M03B-DD          PIC X(08).   * which DD name to dispatch on
05  WS-M03B-OPER        PIC X(01).   * op code: O=open, C=close, R=read seq,
                                                K=read keyed, W=write, Z=rewrite
05  WS-M03B-RC          PIC X(02).   * file status returned
05  WS-M03B-KEY         PIC X(25).   * key for keyed read
05  WS-M03B-KEY-LN      PIC S9(4).   * key length
05  WS-M03B-FLDT        PIC X(1000). * record buffer in/out
```

`WS-M03B-DD` values used by `CBSTM03A`:

- `TRNXFILE` (8 chars exact)
- `XREFFILE`
- `CUSTFILE`
- `ACCTFILE`

`WS-M03B-OPER` values: `'O'`, `'C'`, `'R'`, `'K'`, `'W'`, `'Z'`.
The `'W'` and `'Z'` paths are unused by `CBSTM03A` in this codebase.

`CBSTM03B` dispatches on `LK-M03B-DD` (one paragraph per file) and on the `LK-M03B-OPER` 88-levels (`M03B-OPEN`, `M03B-CLOSE`, `M03B-READ`, `M03B-READ-K`, `M03B-WRITE`, `M03B-REWRITE`).

### 1.5 The `ALTER` + `GO TO` jump table

`CBSTM03A` contains the rare COBOL-74 pattern:

```cobol
0000-START.
    EVALUATE WS-FL-DD
        WHEN 'TRNXFILE' ALTER 8100-FILE-OPEN TO PROCEED TO 8100-TRNXFILE-OPEN
        WHEN 'XREFFILE' ALTER 8100-FILE-OPEN TO PROCEED TO 8200-XREFFILE-OPEN
        ... etc
    END-EVALUATE
    GO TO 8100-FILE-OPEN.

8100-FILE-OPEN.
    GO TO 8100-TRNXFILE-OPEN.    * default; mutated by the ALTER above
```

This is a self-modifying jump table — the `GO TO` target on line `8100-FILE-OPEN` is rewritten at run time. Java replaces it with a static `Map<String, Runnable>` dispatch:

```java
Map<String, Runnable> openByDd = Map.of(
    "TRNXFILE", this::openTrnxFile,
    "XREFFILE", this::openXrefFile,
    "CUSTFILE", this::openCustFile,
    "ACCTFILE", this::openAcctFile);
openByDd.get(wsFlDd).run();
```

The pattern recurs for the other dispatch points. Each gets its own
`Map<String, Runnable>`. **The rationale for keeping the dispatch
explicit (instead of inlining everything) is byte-for-byte trace
equivalence in any future SYSOUT-capture test.**

### 1.6 The 2D OCCURS table — `WS-CARD-TBL`

```
01 WS-CARD-TBL.
   05 WS-CARD-ROW OCCURS 51 TIMES.
      10 WS-CARDNUM   PIC X(16).
      10 WS-TRAN-CNT  PIC 9(02).
      10 WS-TRAN-TBL  OCCURS 10 TIMES.
         15 WS-TRAN-ROW. (typed copy of TRNX-RECORD fields)
```

Java equivalent: `Map<String, List<TrnxRow>>` where the key is
`CARDNUM` (left-padded to 16 chars). The map handles up to 51
distinct cards × 10 transactions each = 510 max transactions. Java
can technically support more, but the COBOL hard limit must be
mirrored: the Java port should `throw new BatchAbendException(999)`
on overflow to match the VSAM-equivalent ABEND.

### 1.7 Statement layouts — text and HTML

Step 1 of §4 dumps every `STMTFILE` and `HTMLFILE` `WRITE` block from
`CBSTM03A.CBL` and tabulates the byte-exact line composition. Both
formats share identical underlying data; only the framing differs.

The HTML output uses `STRING ... DELIMITED BY '*'` as a no-op
delimiter to make `STRING` behave as concatenation, and uses an
88-level "literal pool" pattern: each fixed HTML fragment is a `VALUE`
of `HTML-FIXED-LN` and is "selected" via `SET HTML-Lxx TO TRUE`
immediately before the `WRITE`. Java replaces this with a
`Map<String, byte[]>` of HTML fragments and string concatenation.

### 1.8 Customer record variant — `CUSTREC` vs `CVCUS01Y`

`CBSTM03A` uses `COPY CUSTREC` whose `CUST-DOB` field is named
`CUST-DOB-YYYYMMDD` (no dashes), with a different downstream slicing
than `CVCUS01Y`'s `CUST-DOB-YYYY-MM-DD`. **Distinct from `CVCUS01Y`** —
do not reuse `CustomerRecord` from the `CBCUS01C` plan.

A separate domain class `Stm03CustomerRecord` is required.

---

## 2. Equivalence requirements (acceptance criteria)

Two artifacts must match byte-for-byte:

1. **`STMTFILE`** — every byte of every 80-byte record.
2. **`HTMLFILE`** — every byte of every 100-byte record.

**Acceptance test:** `Files.mismatch(expected, actual) == -1L` for both.

**Sources of divergence to neutralize:**

- **Statement-date / generation-time fields** — `CBSTM03A` may (verify
  in step 1) call `FUNCTION CURRENT-DATE` for a "Statement generated:
  YYYY-MM-DD HH:MM:SS" line. Inject via
  `Db2Timestamp.useFixedClock(...)`. COBOL oracle gets a compile-time
  switch.

**Mainframe-only features that don't appear in the byte-for-byte test:**

- The PSA → TCB → TIOT walk (`SET ADDRESS OF PSA-BLOCK TO PSAPTR`) —
  this code emits diagnostics to `SYSOUT` but writes nothing to the
  two output files. The Java port stubs it out (logs "DD walk: not
  applicable on Linux") and the IT does not check SYSOUT.

---

## 3. Target architecture

### 3.1 Layout

```
app/java/batch_processing_workflow/
└── src/
    ├── main/java/com/carddemo/batch/
    │   ├── statement/                                  ← new subpackage for the pair
    │   │   ├── StatementCreator.java                   main(); mirrors CBSTM03A PROCEDURE DIVISION
    │   │   ├── domain/
    │   │   │   ├── TrnxRecord.java                     wraps COSTM01 (350 bytes)
    │   │   │   └── Stm03CustomerRecord.java            wraps CUSTREC variant (500 bytes)
    │   │   ├── store/
    │   │   │   ├── TransactionTable.java               2D OCCURS table → Map<String, List<TrnxRow>>
    │   │   │   └── FileGateway.java                    replaces CBSTM03B linkage
    │   │   ├── format/
    │   │   │   ├── PlainTextStatementBuilder.java      80-byte FB writer
    │   │   │   └── HtmlStatementBuilder.java           100-byte FB writer
    │   │   └── dispatch/
    │   │       └── DispatchTable.java                  Map<String, Runnable> replacement for ALTER+GO TO
    │   ├── account/                                    ← UNCHANGED (provides ExtractAccountRecord)
    │   ├── interest/                                   ← UNCHANGED (provides Db2Timestamp)
    │   └── …
    └── test/java/com/carddemo/batch/statement/
        ├── TrnxRecordRoundTripTest.java
        ├── Stm03CustomerRecordRoundTripTest.java
        ├── TransactionTableTest.java                   51 × 10 capacity, ABEND on overflow
        ├── FileGatewayTest.java                        every (DD, op) combination
        ├── PlainTextStatementBuilderTest.java          byte-exact for known statement
        ├── HtmlStatementBuilderTest.java               byte-exact for known statement
        ├── DispatchTableTest.java                      open / read / close per DD
        ├── StatementCreatorTest.java                   end-to-end smoke
        └── StatementGoldenFileEquivalenceIT.java       diff vs. GnuCOBOL oracle for STMT and HTML
```

### 3.2 Decisions

| Decision | Default | Why |
| -------- | ------- | --- |
| Java version | **Java 17** | Same module |
| `CBSTM03B` translation | **`FileGateway` class** with `Map<String, FileChannel>` keyed on DD name + a switch on operation char | Replaces the linkage-area pattern with a clean façade. Not exposed publicly — internal to `statement/` |
| `WS-M03B-AREA` translation | **`GatewayRequest` record** `{ String dd; char op; String key; int keyLen; byte[] buffer; }` | Structural mirror without exposing internals |
| `WS-CARD-TBL` translation | **`Map<String, List<TrnxRow>>`** with insertion-order semantics | Matches the COBOL "first card seen → first row" ordering |
| Capacity limit | **Hard 51 × 10 enforced** with `BatchAbendException(999)` on overflow | Faithful to COBOL OCCURS limits |
| `ALTER` + `GO TO` translation | **`DispatchTable` (`Map<String, Runnable>`)** | Idiomatic Java; same observable behaviour |
| Diagnostic PSA/TCB/TIOT walk | **Stubbed**, logs "DD walk skipped on Linux" | Mainframe-only; no portable analog |
| HTML literal pool | **`Map<String, byte[]>` of HTML fragments**, indexed by the same identifiers `CBSTM03A` uses | Direct byte equivalence; no template engine |
| Statement-date injection | **`Db2Timestamp.useFixedClock(...)`** in tests; `--ts=` CLI flag for manual runs | Reuse from `interest/util` |
| Output writers | **Direct `Files.newOutputStream(...)`** with explicit byte writes | No buffered text I/O; ISO-8859-1 byte identity |

### 3.3 Why a sequential-file GnuCOBOL port

`CBSTM03P-A.cbl` and `CBSTM03P-B.cbl`:

- All four KSDS files in `CBSTM03B` change from INDEXED to LINE
  SEQUENTIAL with in-memory tables loaded at OPEN time. `READ` and
  `READ … KEY IS` become `SEARCH ALL` over the table.
- `STMTFILE` and `HTMLFILE` were already sequential — no change beyond
  ensuring `COB_LS_FIXED=Y` is exported at run time.
- `ALTER … GO TO` is preserved verbatim — GnuCOBOL accepts the syntax.
- The PSA/TCB/TIOT walk is replaced by a `DISPLAY 'DD walk skipped'` so
  SYSOUT doesn't ABEND; the IT doesn't check SYSOUT for this program.

The `CBSTM03B` portable oracle keeps the linkage interface intact —
the Java `FileGateway` mirrors the same DD names and op codes for
trace-equivalence reasons (so a future SYSOUT-capture test can
verify call sequencing).

---

## 4. Step-by-step migration steps

| # | Step | Acceptance |
| - | ---- | ---------- |
| 1 | Tabulate every `WRITE STMTFILE FROM …` and `WRITE HTMLFILE FROM …` in `CBSTM03A.CBL`. Record byte offsets, fixed literals, dynamic fields. Append the table as an appendix to this plan. | Table complete; STMT lines = 80 bytes each; HTML lines = 100 bytes each |
| 2 | Define `TrnxRecord` (350 bytes) and `Stm03CustomerRecord` (500 bytes, variant). | Round-trip tests |
| 3 | Implement `TransactionTable` with 51 × 10 capacity and ABEND-on-overflow. | Capacity test passes |
| 4 | Implement `FileGateway` mirroring `CBSTM03B`'s DD × op dispatch table. Loads each KSDS as `IndexedFileStore<K, V>` (read-only) on `'O'`; lookup on `'R'` (sequential next) or `'K'` (keyed). | `FileGatewayTest` covers the 4 × 4 combinations actually used |
| 5 | Implement `DispatchTable` for `ALTER` + `GO TO` replacement. | Smoke test |
| 6 | Implement `PlainTextStatementBuilder` — produces the FB-80 stream for one account given customer + account + transaction list. | Byte-exact test for one canonical statement |
| 7 | Implement `HtmlStatementBuilder` — produces the FB-100 stream for one account. | Byte-exact test |
| 8 | Implement `StatementCreator.main()` — wires phases A, B, C above; uses `FileGateway`, `TransactionTable`, `DispatchTable`, two builders. | End-to-end on fixtures |
| 9 | Write `cobol-reference/CBSTM03P-A.cbl` and `CBSTM03P-B.cbl` (sequential-only oracles as in §3.3) plus build/run/regen scripts. | `bash regen-golden.sh` produces `fixtures/expected/{stmtfile.dat, htmlfile.dat}` |
| 10 | Write `StatementGoldenFileEquivalenceIT`. | Passes |
| 11 | Update `docs/cobol/batch-programs.md` `CBSTM03A` and `CBSTM03B` section pointers. | Pointers added |

**Estimated effort:** ~3–4 engineering days. The hardest steps are 1 (tabulating every byte of the two output formats) and 9 (porting a 924-line + 230-line program pair). Steps 4 and 5 are mechanical.

---

## 5. Logic equivalency safeguards

### 5.1 `ALTER`-pattern fidelity

The Java `DispatchTable.run(ddName)` is observably equivalent to `ALTER 8100-FILE-OPEN TO PROCEED TO …; GO TO 8100-FILE-OPEN`. The order in which entries are added to the dispatch table doesn't matter — the dispatch is by-key, not by-insertion. The COBOL `ALTER` semantics are also by-key (the latest `ALTER` wins). Equivalence holds.

### 5.2 51 × 10 capacity enforcement

```java
if (tranList.size() >= 10) throw new BatchAbendException(999);
if (table.size() >= 51 && !table.containsKey(cardNum)) throw new BatchAbendException(999);
```

Without these checks, Java silently grows past the COBOL limit. A test fixture with 52 cards (or 11 transactions on one card) must trigger the ABEND.

### 5.3 `STRING ... DELIMITED BY '*'` HTML concatenation

```cobol
STRING 'Hello' '*' WS-NAME '*' '<br>' DELIMITED BY '*' INTO HTML-LINE
```

`'*'` is treated as a delimiter — when the source field encounters it, the move stops. Since none of the literal HTML fragments contain `'*'`, the `STRING` verb effectively concatenates them all, then `'<br>'`. Java equivalent: plain string concatenation (or `StringBuilder.append`). **The `'*'` character must not appear in any source field** — verify by inspection.

### 5.4 88-level HTML literal pool

```cobol
01  HTML-FIXED-LN PIC X(100).
    88  HTML-L01 VALUE '<html><body>'.
    88  HTML-L02 VALUE '<table>'.
    ...
SET HTML-L01 TO TRUE.        * loads HTML-FIXED-LN with the L01 value
WRITE HTMLFILE FROM HTML-FIXED-LN.
SET HTML-L02 TO TRUE.
WRITE HTMLFILE FROM HTML-FIXED-LN.
```

Each `SET HTML-Lxx TO TRUE` rewrites `HTML-FIXED-LN` with the
corresponding `VALUE`. Java equivalent: a `Map<String, byte[]>` of the
fixed fragments; the writer writes the looked-up bytes directly.
Important: the COBOL `VALUE` is right-padded with spaces to 100 bytes
(the field's PIC width). Java must do the same: each fragment is
exactly 100 bytes, padded.

### 5.5 `SET ADDRESS OF PSA-BLOCK TO PSAPTR` skip

The mainframe diagnostic prologue walks z/OS control blocks to print active DD names. On Linux this isn't possible. The Java port:

```java
log.info("DD walk skipped on Linux (z/OS control-block walk not portable)");
```

The portable COBOL oracle replaces the walk with the same `DISPLAY` line. Both sides produce no `STMTFILE`/`HTMLFILE` content from this step → invisible to the byte-equivalence check.

### 5.6 First-card guard / final-flush semantics

Walking `XREFFILE` in `1000-MAINLINE` → for each card, write a complete statement (header → details → footer). There's no "current card" carry-over between iterations like `CBACT04C`'s `1050-UPDATE-ACCOUNT`. Each iteration is self-contained.

### 5.7 `TRNX-CARD-NUM` matching

The `4000-TRNXFILE-GET` paragraph walks `WS-CARD-TBL` to find the row whose `WS-CARDNUM` matches the current `XREF-CARD-NUM`. Java: `Map<String, List<TrnxRow>>` lookup. **Exact-string match** — including any leading zeros and trailing spaces. Don't trim.

### 5.8 LRECL=80 / LRECL=100 enforcement

Every line in `STMTFILE` is exactly 80 bytes; every line in `HTMLFILE` is exactly 100 bytes. Each builder method asserts the output length before returning. Mismatches surface immediately, before any IT runs.

### 5.9 Trailing-FILLER on output

COBOL `WRITE` of a level-01 group writes the entire 80 (or 100) bytes including any trailing spaces from PIC-shorter-than-VALUE expansion. Java must preserve every byte; don't `trim()` lines.

---

## 6. Test strategy

### 6.1 Unit tests

| Test class | Concern |
| ---------- | ------- |
| `TrnxRecordRoundTripTest` | parse → `rawBytes()` byte-identical |
| `Stm03CustomerRecordRoundTripTest` | parse → bytes |
| `TransactionTableTest` | up to 51 × 10 OK; 11th transaction triggers ABEND; 52nd card triggers ABEND |
| `FileGatewayTest` | All used (DD, op) combinations |
| `PlainTextStatementBuilderTest` | Byte-exact for canonical input → 80-byte lines |
| `HtmlStatementBuilderTest` | Byte-exact for canonical input → 100-byte lines |
| `DispatchTableTest` | Each of the 5 dispatch points (open, close, read, etc.) |

### 6.2 Smoke test

`StatementCreatorTest` against a synthetic fixture: 2 customers × 2 cards × 3 transactions per card → 4 statements (or however the per-card grouping works); asserts file lengths and line counts.

### 6.3 Golden-file integration test

```
1. cd cobol-reference && bash tools/regen-golden.sh
   → cobc -x -free -fsign=EBCDIC CBSTM03P-B.cbl -o cbstm03p-b.so   # subprogram first
   → cobc -x -free -fsign=EBCDIC CBSTM03P-A.cbl -L. -lcbstm03p-b
   → COB_LS_FIXED=Y ./CBSTM03P-A
   → cp {stmtfile.dat, htmlfile.dat} → fixtures/expected/

2. mvn verify
   → StatementGoldenFileEquivalenceIT
       Files.mismatch(expected/stmtfile.dat, temp/stmtfile.dat) == -1L
       Files.mismatch(expected/htmlfile.dat, temp/htmlfile.dat) == -1L
```

### 6.4 Synthetic edge-case fixtures

| Fixture | Purpose |
| ------- | ------- |
| `single-card-single-tran/` | 1 card, 1 transaction → 1 statement, 1 HTML page |
| `single-card-max-trans/` | 1 card, 10 transactions (TRAN-TBL fills exactly) |
| `multi-card/` | 3 cards × 2 trans each → 3 statements |
| `overflow-trans/` | 11 transactions on 1 card → ABEND |
| `overflow-cards/` | 52 cards → ABEND |
| `customer-with-special-chars/` | Customer name with `&`, `<`, `>` → byte-faithful in HTML (no escaping by COBOL — Java mirrors) |

---

## 7. Documents to be produced during implementation

1. Append a "CBSTM03A + CBSTM03B — Statement Printer" section to
   `app/java/batch_processing_workflow/README.md`.
2. Append a CBSTM03A/B section to `HOW-TO-TEST.md` (note that GnuCOBOL
   subprogram linkage requires building the subprogram with `-x -free`
   and linking the main program against it).
3. Add `docs/migration/CBSTM03A-CBSTM03B-java-architecture.md`.
4. Update `docs/cobol/batch-programs.md` `CBSTM03A` and `CBSTM03B`
   sections with pointers.
5. The full STMTFILE and HTMLFILE byte-offset tables (from step 1 of
   §4) land as an appendix to this document.

---

## 8. Risks and open decisions

### 8.1 Risks

| Risk | Mitigation |
| ---- | ---------- |
| `ALTER` + `GO TO` accidentally introduces unintended behaviour in the Java port | `DispatchTableTest` covers every dispatch entry; the IT catches drift |
| HTML special characters in customer names (`&`, `<`, `>`) — COBOL does not escape; we must not escape either | Test fixture exercises this; documented as faithful behaviour |
| 2D OCCURS overflow silently misbehaves in Java | Explicit capacity check in `TransactionTable.put(...)` with `BatchAbendException(999)` |
| `CBSTM03B` mock in tests doesn't model file-status `'10'` (EOF) correctly | `FileGatewayTest` exercises the EOF path on each DD |
| Statement date / generation timestamp drift | `Db2Timestamp.useFixedClock(...)` injection in IT |
| Mainframe-only PSA/TCB/TIOT walk produces SYSOUT lines that don't match Linux | IT does not check SYSOUT; only `STMTFILE` + `HTMLFILE` are byte-checked |
| COBOL subprogram link in GnuCOBOL fails with `LD_LIBRARY_PATH` issues | Document `LD_LIBRARY_PATH=.` in `tools/run.sh` |

### 8.2 Open decisions to confirm

1. **`Stm03CustomerRecord` separate from `CustomerRecord`** —
   defaulting yes (different copybook). Accept.
2. **PSA/TCB/TIOT walk** stub-out — accept the Linux skip.
3. **`FileGateway` API mirrors `CBSTM03B` 1:1** — accept (preserves
   trace equivalence) or simplify to direct method calls (loses
   trace equivalence, gains code clarity). Recommend mirror.
4. **HTML escaping policy** — faithful to COBOL (no escaping). Accept.
5. **Defaults in §3.2** — accept or override.
6. **Scope locked** to `CBSTM03A` + `CBSTM03B` paired only — yes/no.

---

## 9. Out of scope for this migration

- The `CREASTMT.JCL` upstream sort step that prepares
  `(card-num, tran-id)`-keyed `TRNXFILE`. The portable oracle / Java
  port assume the input fixture is already sorted.
- Any production HTML rendering (browser display, CSS). The HTML
  output is verbatim — the program does not emit a stylesheet or a
  full `<head>`.
- Online statement display. Only the batch FB-80 / FB-100 outputs.

---

## 10. Approval gate

Confirm before implementation:

1. **Per-program separation** — the pair is migrated together as one
   commit chain. Accept or split.
2. **`FileGateway` 1:1 mirror** of `CBSTM03B` — accept or simplify.
3. **Defaults in §3.2** — accept or override.
4. **Scope locked** to the pair only — yes/no.

Once confirmed, work proceeds through steps 1–11 in §4.
