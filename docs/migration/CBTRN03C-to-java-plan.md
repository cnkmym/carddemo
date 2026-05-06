# Migration Plan: `CBTRN03C.cbl` → Java

**Source program:** `app/cbl/CBTRN03C.cbl` — the **transaction detail report** of CardDemo. Produces a paginated, banded, formatted detail report of all transactions whose processing date falls inside an inclusive `[WS-START-DATE, WS-END-DATE]` window read from `DATEPARM`. Subtotals at account-break, page-break (every 20 lines), and one grand total at EOF.

**Driving JCL:** `app/jcl/TRANREPT.jcl` (multi-step: REPRO master → DFSORT to filter and sort → `CBTRN03C`).

**Target location (mandatory):** `app/java/batch_processing_workflow/`

**Goal:** A Java port that produces a **byte-for-byte identical** `TRANREPT` output (RECFM=FB, LRECL=133) on the same input fixtures, verified by an automated test that diffs against a GnuCOBOL-runnable reference build.

This plan is purely a plan. **No code is written until approved.**

---

## 1. Source contract — what `CBTRN03C` does

### 1.1 Inputs

| DD name | COBOL `SELECT` | Org / Access | Record (copybook) | Notes |
| ------- | -------------- | ------------ | ----------------- | ----- |
| `TRANFILE` | `TRANSACT-FILE` | SEQUENTIAL | 350 bytes (`CVTRA05Y`) | Daily-filtered, **card-number-sorted** output of the JCL's preceding `SORT` step. Drives the loop. |
| `CARDXREF` | `XREF-FILE` | INDEXED / RANDOM | 50 bytes (`CVACT03Y`) | Lookup by `TRAN-CARD-NUM` for description fields. |
| `TRANTYPE` | `TRANTYPE-FILE` | INDEXED / RANDOM | 60 bytes (`CVTRA03Y`) | Lookup by 2-byte `TRAN-TYPE-CD` for type description. |
| `TRANCATG` | `TRANCATG-FILE` | INDEXED / RANDOM | 60 bytes (`CVTRA04Y`) | Lookup by composite `(TRAN-TYPE-CD, TRAN-CAT-CD)` for category description. |
| `DATEPARM` | `DATE-PARMS-FILE` | SEQUENTIAL | 80 bytes | Single-record window: `YYYY-MM-DD YYYY-MM-DD` (start, end). |

### 1.2 Outputs

| DD name | COBOL `SELECT` | Org / Access | Record | Notes |
| ------- | -------------- | ------------ | ------ | ----- |
| `TRANREPT` | `REPORT-FILE` | SEQUENTIAL | 133 bytes FB | The formatted report. |
| `SYSOUT` | n/a | text | various | Banners; informational status decode. |

### 1.3 Algorithm (paragraph map)

```
PROCEDURE DIVISION:
    DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN03C'
    OPEN all 6 files (paragraphs 0000–0500). ABEND on non-'00'.

    PERFORM 0550-DATEPARM-READ              → load WS-START-DATE, WS-END-DATE

    LOOP UNTIL END-OF-FILE = 'Y':
        PERFORM 1000-TRANFILE-GET-NEXT
        IF END-OF-FILE = 'N':
            IF TRAN-PROC-TS(1:10) NOT IN [WS-START-DATE, WS-END-DATE]: NEXT SENTENCE
            IF TRAN-CARD-NUM has changed since last record:
                PERFORM 1120-WRITE-ACCOUNT-TOTALS  (skip on first record)
                PERFORM 1110-LOOKUP-XREF           (re-fetch card-account info)
                Reset WS-ACCOUNT-TOTAL = 0
            PERFORM 1120-LOOKUP-TRANTYPE   (random read by TRAN-TYPE-CD)
            PERFORM 1130-LOOKUP-TRANCATG   (random read by composite key)
            PERFORM 1100-WRITE-TRANSACTION-REPORT:
                IF FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0:
                    PERFORM 1110-WRITE-PAGE-TOTALS  (skip on first page)
                    Print page header
                PERFORM 1120-WRITE-DETAIL          (single 133-byte data line)
                ADD WS-TRAN-AMT TO WS-ACCOUNT-TOTAL, WS-PAGE-TOTAL, WS-GRAND-TOTAL
                ADD 1 TO WS-LINE-COUNTER

    PERFORM 1120-WRITE-ACCOUNT-TOTALS              (final account)
    PERFORM 1110-WRITE-PAGE-TOTALS                 (final page)
    PERFORM 1130-WRITE-GRAND-TOTAL                 (grand total)
    CLOSE all 6 files (9000–9500). ABEND on non-'00'.
    DISPLAY 'END OF EXECUTION OF PROGRAM CBTRN03C'
    GOBACK
```

(Paragraph numbers above are illustrative — verify against the source during step 1 of §4.)

### 1.4 Report layout — 133 bytes per line

The report layouts live in `CVTRA07Y` (`REPORT-NAME-HEADER`,
`TRANSACTION-DETAIL-REPORT`, `TRANSACTION-HEADER-1`,
`TRANSACTION-HEADER-2`, `REPORT-PAGE-TOTALS`,
`REPORT-ACCOUNT-TOTALS`, `REPORT-GRAND-TOTALS`). Each is exactly 133
bytes (RECFM=FB, LRECL=133, the standard mainframe report width).

Each line type is a fixed-position record with:

- Text labels (e.g. `'PAGE TOTAL:'`, `'ACCOUNT'`).
- Edited numeric fields with PIC clauses like `PIC -ZZZ,ZZZ,ZZZ.ZZ`
  for amounts (zero-suppressed, comma-grouped, signed).
- Spaces (`PIC X(N)`) for separators and indents.
- A trailing band of `'-'` characters in the header lines.

Step 1 of §4 dumps every layout from `CVTRA07Y` and tabulates each
line's layout (offsets, lengths, PIC clauses) — this becomes the
contract for the Java `Report*Line` classes.

### 1.5 Pagination

Page size: `WS-PAGE-SIZE = 20` (defined in working storage). Pagination
trigger: `IF FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0`. **The
counter is incremented after each detail line is written.**

Order of operations on a page break:
1. Write page-total line for the prior page.
2. Write page header (3 lines: report name + 2-line column header).
3. Write the detail line for the current transaction.

### 1.6 Date filter

```cobol
IF (TRAN-PROC-TS (1:10) >= WS-START-DATE
AND TRAN-PROC-TS (1:10) <= WS-END-DATE)
    [process]
ELSE
    NEXT SENTENCE
```

The first 10 bytes of `TRAN-PROC-TS` (`YYYY-MM-DD`) are string-compared against the window. Same lexical-vs-chronological argument as `CBTRN02C` §5.5 — string comparison is fine because the format is `YYYY-MM-DD`.

### 1.7 Edited PIC clauses to replicate

| PIC | Width | Java equivalent |
| --- | ----- | --------------- |
| `-ZZZ,ZZZ,ZZZ.ZZ` | 16 (1 sign + 11 digits/seps + period + 2 cents = 15? — verify in source) | `DecimalFormat(" #,##0.00;-#,##0.00")` plus right-justify-pad to 16 |
| `+ZZZ,ZZZ,ZZZ.ZZ` | 16 | `DecimalFormat("+#,##0.00;-#,##0.00")` |
| `ZZ,ZZZ,ZZZ` (record count, e.g.) | 11 | `DecimalFormat("#,##0")` |

Zero-suppressed (`Z`) fields print spaces for leading zeros. Sign
positions print space (or `+`/`-`) depending on the leading character.
Java `DecimalFormat` patterns approximate this but need careful
right-padding/truncation to match the exact COBOL byte layout.

This is the **central new primitive** introduced by this plan:
`EditedNumericFormat` in the shared `io` package.

---

## 2. Equivalence requirements (acceptance criteria)

1. **`TRANREPT`** matches byte-for-byte. All 133-byte records,
   including pagination and totals.

**Acceptance test:** `Files.mismatch(expected, actual) == -1L`.

**Sources of divergence to neutralize:**

- **Report header date** — if the program prints "Report run date:
  YYYY-MM-DD" anywhere, that line uses `FUNCTION CURRENT-DATE`. Inject
  via `Db2Timestamp.useFixedClock(...)` (reuse from `interest/util`).
  Audit the source during step 1 of §4 and add the injection point to
  the COBOL oracle if needed.

---

## 3. Target architecture

### 3.1 Layout

```
app/java/batch_processing_workflow/
└── src/
    ├── main/java/com/carddemo/batch/
    │   ├── trnrept/                                   ← new subpackage for CBTRN03C
    │   │   ├── TransactionDetailReport.java           main(); mirrors PROCEDURE DIVISION
    │   │   ├── domain/
    │   │   │   ├── TranTypeRecord.java                wraps CVTRA03Y (60 bytes)
    │   │   │   └── TranCatRecord.java                 wraps CVTRA04Y (60 bytes)
    │   │   ├── io/
    │   │   │   └── ReportWriter.java                  appends 133-byte lines to TRANREPT.dat
    │   │   ├── format/
    │   │   │   ├── EditedNumericFormat.java           NEW — emulates COBOL PIC -Z,...,Z.ZZ
    │   │   │   ├── DetailLineBuilder.java             builds one TRANSACTION-DETAIL-REPORT line
    │   │   │   ├── HeaderLineBuilder.java             builds the 3-line page header
    │   │   │   ├── PageTotalLineBuilder.java          builds REPORT-PAGE-TOTALS
    │   │   │   ├── AccountTotalLineBuilder.java       builds REPORT-ACCOUNT-TOTALS
    │   │   │   └── GrandTotalLineBuilder.java         builds REPORT-GRAND-TOTALS
    │   │   └── DateParmsReader.java                   parses 80-byte DATEPARM record
    │   ├── interest/                                  ← UNCHANGED
    │   └── …                                          ← other packages unchanged
    └── test/java/com/carddemo/batch/trnrept/
        ├── EditedNumericFormatTest.java               broad-coverage parameterised
        ├── DetailLineBuilderTest.java                 byte-exact for known transaction
        ├── HeaderLineBuilderTest.java                 byte-exact
        ├── PaginationLogicTest.java                   page break at line 21, not 20
        ├── DateFilterTest.java                        boundary cases
        ├── AccountBreakLogicTest.java                 sub-total sequencing
        ├── TransactionDetailReportTest.java           end-to-end smoke
        └── TransactionDetailReportGoldenFileEquivalenceIT.java
```

### 3.2 Decisions

| Decision | Default | Why |
| -------- | ------- | --- |
| Java version | **Java 17** | Same module |
| Report record format | **133-byte fixed; no terminator on disk; LF on `LINE SEQUENTIAL OUTPUT` from oracle** | Matches mainframe FB; the oracle adds an LF (one byte) that becomes part of the byte-equivalence check. **Both sides emit identical bytes including LF.** |
| Edited numeric formatting | **New `EditedNumericFormat` class** in shared `io` | First port that needs PIC-edit emulation; lifts to shared as soon as a second caller emerges |
| Lookup stores | **`IndexedFileStore<K, V>`** for `XREF`, `TRANTYPE`, `TRANCATG` (all read-only) | Promoted in `CBTRN01C` plan |
| Pagination | **Counter approach**: increment line counter, check `% pageSize == 0` after each detail line, fire page break before the *next* detail | Matches the `FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0` pattern |
| Account-break | **Compare current `TRAN-CARD-NUM` to previous; on change emit account-total then re-fetch xref** | First card → no account-total emitted (special-cased on first record) |
| Date filter | **String compare on `YYYY-MM-DD`** | No `LocalDate` parsing |
| Decimal accumulation | **`BigDecimal` with scale 2** | Standard |

### 3.3 Why a sequential-file GnuCOBOL port

`CBTRN03P.cbl`:
- `XREF-FILE`, `TRANTYPE-FILE`, `TRANCATG-FILE` change from `INDEXED RANDOM` to in-memory tables loaded via `LINE SEQUENTIAL INPUT` at OPEN; `READ … KEY … INVALID KEY` becomes `SEARCH ALL` on the in-memory table with `WHEN OTHER` → `INVALID KEY` body.
- `TRANSACT-FILE` (input) was already sequential — `LINE SEQUENTIAL INPUT`. **Critically, the JCL pre-sorts this file** before invoking the program; the portable oracle assumes the input fixture is already sorted on `TRAN-CARD-NUM`.
- `REPORT-FILE` was already sequential — `LINE SEQUENTIAL OUTPUT`. The 133-byte fixed-record assumption requires `COB_LS_FIXED=Y`.
- `DATE-PARMS-FILE` was already sequential — `LINE SEQUENTIAL INPUT`.
- All business-logic paragraphs and `CVTRA07Y` layouts copied verbatim.

---

## 4. Step-by-step migration steps

| # | Step | Acceptance |
| - | ---- | ---------- |
| 1 | Tabulate `CVTRA07Y` layouts (offsets, lengths, PIC clauses) into a Markdown table inside this plan. Verify each line type is exactly 133 bytes. | Table complete; total 133 bytes per type |
| 2 | Implement `EditedNumericFormat` for `-ZZZ,ZZZ,ZZZ.ZZ`, `+ZZZ,ZZZ,ZZZ.ZZ`, and `ZZ,ZZZ,ZZZ` patterns. Right-justified, zero-suppressed, sign-padded. | Parameterised test covers: 0, 1, 1234567.89, -1234567.89, max value, edge cases (sign character placement) |
| 3 | Define `TranTypeRecord` (60 bytes) and `TranCatRecord` (60 bytes) with their accessors. | Round-trip tests pass |
| 4 | Implement the 6 line-builder classes (`DetailLineBuilder`, `HeaderLineBuilder`, `PageTotalLineBuilder`, `AccountTotalLineBuilder`, `GrandTotalLineBuilder`, plus the report-name banner). Each produces exactly 133 bytes. | Byte-exact tests for known inputs |
| 5 | Implement `DateParmsReader` — parses the 80-byte DATEPARM record into `(LocalDate startInclusive, LocalDate endInclusive)` (or as `String` if format is fixed). | Unit test |
| 6 | Implement `TransactionDetailReport.main()` — wires the readers, the lookup stores, the line builders, and the writer. | Runs end-to-end on fixtures |
| 7 | Write `cobol-reference/CBTRN03P.cbl` (sequential-only oracle as in §3.3) plus the build/run/regen scripts. The fixture for `TRANSACT-FILE` is the sorted output of `tools/sort-trans.sh` (mimics the JCL `SORT` step). | `bash regen-golden.sh` produces `fixtures/expected/tranrept.dat` |
| 8 | Write the unit tests in §6.1 plus pagination and account-break logic tests. | All pass |
| 9 | Write `TransactionDetailReportGoldenFileEquivalenceIT`. | Passes |
| 10 | Update `docs/cobol/batch-programs.md` `CBTRN03C` section pointer. | Pointer added |

**Estimated effort:** ~2–3 engineering days. The bulk of the surprise is in step 2 (`EditedNumericFormat` correctness — COBOL edit pictures have 30+ corner cases) and step 4 (every line builder has to match exact byte offsets from `CVTRA07Y`).

---

## 5. Logic equivalency safeguards

### 5.1 `EditedNumericFormat` corner cases

The hardest primitive in this plan. Test cases that must pass:

| Input value | Pattern | Expected output (visible chars; trailing pad if any) |
| ----------- | ------- | ----------------------------------------------------- |
| `0.00` | `-ZZZ,ZZZ,ZZZ.ZZ` | `(15 spaces)` (full zero-suppression) |
| `1.00` | `-ZZZ,ZZZ,ZZZ.ZZ` | `             1.00` (padded) |
| `-1.00` | `-ZZZ,ZZZ,ZZZ.ZZ` | `            -1.00` |
| `1234567.89` | `-ZZZ,ZZZ,ZZZ.ZZ` | `     1,234,567.89` |
| `-1234567.89` | `-ZZZ,ZZZ,ZZZ.ZZ` | `    -1,234,567.89` |
| `9999999999.99` | `-ZZZ,ZZZ,ZZZ.ZZ` | overflow → `*` fill in COBOL? **Verify against source** |

The **exact width** must be verified — `-ZZZ,ZZZ,ZZZ.ZZ` is 1 (sign) + 11 (digits/commas) + 3 (period + 2 cents) = 15? Or 16? The literal byte count of the COBOL `PIC` clause string is the source of truth. Verify in step 1.

### 5.2 First-account suppression

On the very first transaction, `TRAN-CARD-NUM` has no previous value to compare against, so the account-total branch fires spuriously. The COBOL source guards with a `WS-FIRST-TIME` flag (or a similar idiom; verify in source). Java must replicate.

### 5.3 EOF flush of last account-total, page-total, grand-total

When the loop ends, the program emits one final account-total, one final page-total, then the grand total. The Java port must do all three after the loop exits.

### 5.4 Pagination edge cases

- Exactly 20 detail lines on the first page → page-total fires after line 20, then header for page 2, then no more details → page 2 emits *only* the header? **Verify in source** — the program may suppress empty-page headers.
- 0 detail lines (no transactions in date window) → no headers, no totals (?), only banners?

### 5.5 Account-total reset

`WS-ACCOUNT-TOTAL` resets to zero on account break. `WS-PAGE-TOTAL` resets to zero on page break. `WS-GRAND-TOTAL` never resets. Java needs three separate `BigDecimal` accumulators.

### 5.6 LRECL=133 enforcement

Every output line is exactly 133 bytes. If a builder emits 132 or 134, the IT fails. Each `*LineBuilder` test asserts `output.length == 133`.

### 5.7 GnuCOBOL `LINE SEQUENTIAL OUTPUT` adds LF

The portable oracle's report writer appends `\n` (1 byte) to each 133-byte line. Total per record on disk: 134 bytes. `COB_LS_FIXED=Y` does *not* suppress the LF on output. The Java port writes 133 bytes + `\n`. Document this in `HOW-TO-TEST.md` so it's not a surprise.

### 5.8 Date-window inclusivity

The `(1:10) >= WS-START-DATE AND (1:10) <= WS-END-DATE` form is **inclusive** on both ends. Java: `procDate.compareTo(startDate) >= 0 && procDate.compareTo(endDate) <= 0`.

### 5.9 Qualified-name lookup

```cobol
MOVE TRAN-TYPE-CD OF TRAN-RECORD TO FD-TRAN-TYPE-CD OF FD-TRAN-CAT-KEY
```

This `OF`-qualified `MOVE` disambiguates a field name that exists in two records. Java needs no special handling — the source field is on the input record class, the destination is on the lookup-key class. Plain getter/setter call.

---

## 6. Test strategy

### 6.1 Unit tests

| Test class | Concern |
| ---------- | ------- |
| `EditedNumericFormatTest` | Parameterised; the 30+ corner cases above |
| `DetailLineBuilderTest` | Byte-exact for one canonical detail line |
| `HeaderLineBuilderTest` | Byte-exact for the 3-line header |
| `PageTotalLineBuilderTest` | Byte-exact for known total |
| `AccountTotalLineBuilderTest` | Byte-exact |
| `GrandTotalLineBuilderTest` | Byte-exact |
| `PaginationLogicTest` | After 20 detail lines, the 21st write triggers page break |
| `DateFilterTest` | Boundary: date == start, date == end, date one day before/after |
| `AccountBreakLogicTest` | First record never fires account-total; subsequent card change does |

### 6.2 Smoke test

`TransactionDetailReportTest` against a 5-record synthetic fixture (3 cards × dates spanning the window): asserts the line count and the order of emit operations (detail/account-total/page-header/grand-total).

### 6.3 Golden-file integration test

```
1. cd cobol-reference && bash tools/regen-golden.sh
   → tools/sort-trans.sh fixtures/input/transact.dat → fixtures/input/transact.sorted.dat
   → cobc -x -free -fsign=EBCDIC CBTRN03P.cbl
   → COB_LS_FIXED=Y ./CBTRN03P
   → cp tranrept.dat → fixtures/expected/

2. mvn verify
   → TransactionDetailReportGoldenFileEquivalenceIT
       Files.mismatch(expected/tranrept.dat, temp/tranrept.dat) == -1L
```

### 6.4 Synthetic edge-case fixtures

| Fixture | Purpose |
| ------- | ------- |
| `single-account-single-page/` | 5 transactions, all same card, fits on one page → 1 page header, 5 details, 1 account-total, 1 page-total, 1 grand-total |
| `single-account-multi-page/` | 25 transactions, all same card → 2 page headers, 25 details, 1 account-total at end, 2 page-totals, 1 grand-total |
| `multi-account-single-page/` | 5 transactions across 3 cards → 1 page header, 5 details, 3 account-totals, 1 page-total, 1 grand-total |
| `outside-window/` | All transactions outside `[start, end]` → 0 details, no headers/totals (just banners) |
| `boundary-window/` | One transaction with `date == start`, one with `date == end` |
| `all-zero-amounts/` | All amounts are 0.00 → `EditedNumericFormat` emits all-spaces fields |

---

## 7. Documents to be produced during implementation

1. Append a "CBTRN03C — Transaction Detail Report" section to
   `app/java/batch_processing_workflow/README.md`.
2. Append a CBTRN03C section to `HOW-TO-TEST.md` with the
   `tools/sort-trans.sh` step.
3. Add `docs/migration/CBTRN03C-java-architecture.md`.
4. Update `docs/cobol/batch-programs.md` `CBTRN03C` section pointer.
5. Document the new `EditedNumericFormat` API in the shared `io`
   package's javadoc.
6. The full `CVTRA07Y` layout table (from step 1 of §4) lands in this
   document as an appendix.

---

## 8. Risks and open decisions

### 8.1 Risks

| Risk | Mitigation |
| ---- | ---------- |
| `EditedNumericFormat` byte width mismatches by 1 → entire report shifted | Step 4 byte-exact builder tests catch on first run |
| Pagination off-by-one (page break before line 20 vs after) | `PaginationLogicTest` exercises both cases |
| `LINE SEQUENTIAL OUTPUT` adds an LF that the Java port omits → IT fails on byte 134 | Java port writes `\n` after each 133-byte record; documented in `HOW-TO-TEST.md` |
| Sort-step approximation (`tools/sort-trans.sh`) doesn't replicate DFSORT exactly | `sort -t … -k …` on the relevant byte range; document the exact sort key in `tools/sort-trans.sh` |
| Empty page header on a fixture that ends exactly at line 20 | Verify in source whether the program suppresses empty-page headers; mirror in Java |

### 8.2 Open decisions to confirm

1. **`EditedNumericFormat` placement** — defaulting to shared
   `com.carddemo.batch.io.format`. Accept?
2. **`tools/sort-trans.sh`** as a replacement for the JCL DFSORT step
   — accept the approximation or specify the exact sort algorithm.
3. **Defaults in §3.2** — accept or override.
4. **Scope locked** to `CBTRN03C` only — yes/no.

---

## 9. Out of scope for this migration

- The DFSORT JCL step that pre-filters/sorts `TRANFILE`. The portable
  oracle and the IT use a `tools/sort-trans.sh` shell script instead.
- Multiple report formats. Only the `TRANREPT` 133-byte FB report is
  produced.
- Localisation of dates / numbers. The COBOL source is en-US-only;
  the port matches.

---

## 10. Approval gate

Confirm before implementation:

1. **`EditedNumericFormat`** in shared `io.format` — accept.
2. **`tools/sort-trans.sh`** as the DFSORT replacement — accept.
3. **Defaults in §3.2** — accept or override.
4. **Scope locked** to `CBTRN03C` only — yes/no.

Once confirmed, work proceeds through steps 1–10 in §4.
