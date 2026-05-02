# Migration Plan: `CBACT01C.cbl` → Java

**Source program:** `app/cbl/CBACT01C.cbl` — the **account data extractor** batch program of
CardDemo. Driven by `app/jcl/READACCT.jcl`. Reads the account VSAM KSDS file sequentially and
writes each account record into three parallel output datasets.

**Target location (mandatory):** `app/java/batch_processing_workflow/`

**Goal:** A Java port that produces **byte-for-byte identical output** to the original on the same
input fixtures, verified by an automated test that diffs against a GnuCOBOL-runnable reference
build.

This plan is purely a plan. **No code is written until approved.**

---

## 1. Source contract — what `CBACT01C` does

### 1.1 Inputs

| DD name    | COBOL `SELECT`  | Org / Access         | Record (copybook)                          | Notes                                           |
| ---------- | --------------- | -------------------- | ------------------------------------------ | ----------------------------------------------- |
| `ACCTFILE` | `ACCTFILE-FILE` | INDEXED / SEQUENTIAL | 300 bytes (`CVACT01Y` — `ACCOUNT-RECORD`) | Driving file. Walked sequentially in key order. |

### 1.2 Outputs

Three output datasets are written once per input account record, in this order per account:

| DD name    | COBOL `SELECT` | LRECL / RECFM         | COBOL record group | Notes                                                                |
| ---------- | -------------- | --------------------- | ------------------ | -------------------------------------------------------------------- |
| `OUTFILE`  | `OUT-FILE`     | 107 / FB              | `OUT-ACCT-REC`     | Flat sequential extract. `CYC-DEBIT` field is COMP-3 (packed decimal). |
| `ARRYFILE` | `ARRY-FILE`    | 110 / FB              | `ARR-ARRAY-REC`    | Five-element `OCCURS` array; all five `CYC-DEBIT` sub-fields are COMP-3. |
| `VBRCFILE` | `VBRC-FILE`    | 84 / VB (10–80 bytes) | `VBR-REC`          | Two logical sub-records per account: VBR1 (12 bytes) then VBR2 (39 bytes). |

### 1.3 Algorithm (paragraph map)

```
PROCEDURE DIVISION:
    PERFORM 0000-ACCTFILE-OPEN    — OPEN INPUT  ACCTFILE-FILE; ABEND on non-'00'
    PERFORM 2000-OUTFILE-OPEN     — OPEN OUTPUT OUT-FILE;      ABEND on non-'00'
    PERFORM 3000-ARRFILE-OPEN     — OPEN OUTPUT ARRY-FILE;     ABEND on non-'00'
    PERFORM 4000-VBRFILE-OPEN     — OPEN OUTPUT VBRC-FILE;     ABEND on non-'00'

    LOOP UNTIL END-OF-FILE = 'Y':
        PERFORM 1000-ACCTFILE-GET-NEXT:
            READ ACCTFILE-FILE INTO ACCOUNT-RECORD
            status '00':
                INITIALIZE ARR-ARRAY-REC
                PERFORM 1100-DISPLAY-ACCT-RECORD  — DISPLAY all 11 account fields (sysout only)
                PERFORM 1300-POPUL-ACCT-RECORD    — build OUT-ACCT-REC; CALL COBDATFT
                PERFORM 1350-WRITE-ACCT-RECORD    — WRITE OUT-ACCT-REC → OUTFILE
                PERFORM 1400-POPUL-ARRAY-RECORD   — build ARR-ARRAY-REC (OCCURS 5)
                PERFORM 1450-WRITE-ARRY-RECORD    — WRITE ARR-ARRAY-REC → ARRYFILE
                INITIALIZE VBRC-REC1
                PERFORM 1500-POPUL-VBRC-RECORD    — populate VBRC-REC1 and VBRC-REC2
                PERFORM 1550-WRITE-VB1-RECORD     — WRITE 12-byte VBR1 → VBRCFILE
                PERFORM 1575-WRITE-VB2-RECORD     — WRITE 39-byte VBR2 → VBRCFILE
            status '10' (EOF): MOVE 'Y' TO END-OF-FILE
            other status: ABEND (9999-ABEND-PROGRAM)

    PERFORM 9000-ACCTFILE-CLOSE   — CLOSE ACCTFILE-FILE; ABEND on non-'00'
    DISPLAY 'END OF EXECUTION OF PROGRAM CBACT01C'
    GOBACK
    ┌─ Note: OUT-FILE, ARRY-FILE, VBRC-FILE are NOT explicitly CLOSEd. ─┐
    │  They are implicitly flushed and closed by the COBOL runtime at   │
    │  GOBACK. Java must close them explicitly in a finally / try-with. │
    └───────────────────────────────────────────────────────────────────┘
```

### 1.4 Per-paragraph translation notes

#### 1300-POPUL-ACCT-RECORD

- Copies `ACCT-ID`, `ACCT-ACTIVE-STATUS`, `ACCT-CURR-BAL`, `ACCT-CREDIT-LIMIT`,
  `ACCT-CASH-CREDIT-LIMIT`, `ACCT-OPEN-DATE`, `ACCT-EXPIRAION-DATE` [sic — typo preserved in
  copybook], `ACCT-CURR-CYC-CREDIT`, `ACCT-GROUP-ID` verbatim.
- Calls external assembler program **`COBDATFT`** to reformat `ACCT-REISSUE-DATE` from `YYYY-MM-DD`
  (type code `'2'`) to `YYYYMMDD` (output type `'2'`). Result is 8 date chars followed by 12
  trailing spaces in the 20-byte `CODATECN-0UT-DATE` field; the first 10 bytes are then moved to
  `OUT-ACCT-REISSUE-DATE` (PIC X(10)), producing `YYYYMMDD  ` (8 chars + 2 spaces).
- **Conditional — the sticky-debit bug:**
  ```cobol
  IF  ACCT-CURR-CYC-DEBIT EQUAL TO ZERO
      MOVE 2525.00  TO  OUT-ACCT-CURR-CYC-DEBIT
  END-IF
  ```
  There is **no ELSE branch**. If `ACCT-CURR-CYC-DEBIT` is non-zero, `OUT-ACCT-CURR-CYC-DEBIT`
  retains whatever value it held from the *previous* iteration (or the WORKING-STORAGE initial
  value of zero for the first record). This is almost certainly a COBOL bug, but it must be
  replicated faithfully for byte-for-byte equivalence. The Java port must carry
  `outCycDebit: BigDecimal` as loop state, initialized to `0`.

#### 1400-POPUL-ARRAY-RECORD

`ARR-ARRAY-REC` is `INITIALIZE`d before this paragraph, which zeros all numeric fields and
spaces the filler. Occurrences 4–5 are never written, so they remain at INITIALIZE values.

| Occurrence | `ARR-ACCT-CURR-BAL(n)` | `ARR-ACCT-CURR-CYC-DEBIT(n)` |
| ---------- | ---------------------- | ---------------------------- |
| 1          | `ACCT-CURR-BAL`        | **1005.00** (hard-coded)     |
| 2          | `ACCT-CURR-BAL`        | **1525.00** (hard-coded)     |
| 3          | **-1025.00** (hard-coded) | **-2500.00** (hard-coded) |
| 4          | 0 (from INITIALIZE)    | 0 (from INITIALIZE)          |
| 5          | 0 (from INITIALIZE)    | 0 (from INITIALIZE)          |

#### 1500-POPUL-VBRC-RECORD

- `VB1-ACCT-ID` and `VB2-ACCT-ID` both receive `ACCT-ID`.
- `VB1-ACCT-ACTIVE-STATUS` receives `ACCT-ACTIVE-STATUS`.
- `VB2-ACCT-CURR-BAL` receives `ACCT-CURR-BAL`.
- `VB2-ACCT-CREDIT-LIMIT` receives `ACCT-CREDIT-LIMIT`.
- `VB2-ACCT-REISSUE-YYYY` receives `WS-ACCT-REISSUE-YYYY` — the year substring (first 4 chars) of
  `WS-REISSUE-DATE`, which was set in 1300 by `MOVE ACCT-REISSUE-DATE TO WS-REISSUE-DATE`.

### 1.5 Record layouts

#### `OUT-ACCT-REC` — 107 bytes

| Offset | Len | COBOL name                     | PIC              | Storage              | Java type  |
| ------ | --- | ------------------------------ | ---------------- | -------------------- | ---------- |
| 0      | 11  | `OUT-ACCT-ID`                  | `PIC 9(11)`      | unsigned numeric     | `long`     |
| 11     | 1   | `OUT-ACCT-ACTIVE-STATUS`       | `PIC X(01)`      | text                 | `String`   |
| 12     | 12  | `OUT-ACCT-CURR-BAL`            | `PIC S9(10)V99`  | zoned decimal        | `BigDecimal` |
| 24     | 12  | `OUT-ACCT-CREDIT-LIMIT`        | `PIC S9(10)V99`  | zoned decimal        | `BigDecimal` |
| 36     | 12  | `OUT-ACCT-CASH-CREDIT-LIMIT`   | `PIC S9(10)V99`  | zoned decimal        | `BigDecimal` |
| 48     | 10  | `OUT-ACCT-OPEN-DATE`           | `PIC X(10)`      | text                 | `String`   |
| 58     | 10  | `OUT-ACCT-EXPIRAION-DATE` [sic]| `PIC X(10)`      | text                 | `String`   |
| 68     | 10  | `OUT-ACCT-REISSUE-DATE`        | `PIC X(10)`      | text (COBDATFT out)  | `String`   |
| 78     | 12  | `OUT-ACCT-CURR-CYC-CREDIT`     | `PIC S9(10)V99`  | zoned decimal        | `BigDecimal` |
| 90     | 7   | `OUT-ACCT-CURR-CYC-DEBIT`      | `PIC S9(10)V99 COMP-3` | **packed decimal** | `BigDecimal` |
| 97     | 10  | `OUT-ACCT-GROUP-ID`            | `PIC X(10)`      | text                 | `String`   |

Total: 11+1+12+12+12+10+10+10+12+7+10 = **107 bytes** ✓ (matches JCL `LRECL=107 RECFM=FB`).

#### `ARR-ARRAY-REC` — 110 bytes

| Offset | Len | COBOL name                      | PIC              | Storage          |
| ------ | --- | ------------------------------- | ---------------- | ---------------- |
| 0      | 11  | `ARR-ACCT-ID`                   | `PIC 9(11)`      | unsigned numeric |
| 11     | 12  | `ARR-ACCT-CURR-BAL(1)`          | `PIC S9(10)V99`  | zoned decimal    |
| 23     | 7   | `ARR-ACCT-CURR-CYC-DEBIT(1)`    | `PIC S9(10)V99 COMP-3` | packed decimal |
| 30     | 12  | `ARR-ACCT-CURR-BAL(2)`          | `PIC S9(10)V99`  | zoned decimal    |
| 42     | 7   | `ARR-ACCT-CURR-CYC-DEBIT(2)`    | `PIC S9(10)V99 COMP-3` | packed decimal |
| 49     | 12  | `ARR-ACCT-CURR-BAL(3)`          | `PIC S9(10)V99`  | zoned decimal    |
| 61     | 7   | `ARR-ACCT-CURR-CYC-DEBIT(3)`    | `PIC S9(10)V99 COMP-3` | packed decimal |
| 68     | 12  | `ARR-ACCT-CURR-BAL(4)`          | `PIC S9(10)V99`  | zoned decimal (always 0) |
| 80     | 7   | `ARR-ACCT-CURR-CYC-DEBIT(4)`    | `PIC S9(10)V99 COMP-3` | packed decimal (always 0) |
| 87     | 12  | `ARR-ACCT-CURR-BAL(5)`          | `PIC S9(10)V99`  | zoned decimal (always 0) |
| 99     | 7   | `ARR-ACCT-CURR-CYC-DEBIT(5)`    | `PIC S9(10)V99 COMP-3` | packed decimal (always 0) |
| 106    | 4   | `ARR-FILLER`                    | `PIC X(04)`      | spaces (from INITIALIZE) |

Total: 11 + 5×(12+7) + 4 = **110 bytes** ✓ (matches JCL `LRECL=110 RECFM=FB`).

#### `VBR-REC` sub-types

| Sub-type | Size   | COBOL group | Fields                                                                             |
| -------- | ------ | ----------- | ---------------------------------------------------------------------------------- |
| VBR1     | 12 bytes | `VBRC-REC1` | `VB1-ACCT-ID` PIC 9(11) + `VB1-ACCT-ACTIVE-STATUS` PIC X(1)                    |
| VBR2     | 39 bytes | `VBRC-REC2` | `VB2-ACCT-ID` PIC 9(11) + `VB2-ACCT-CURR-BAL` S9(10)V99 + `VB2-ACCT-CREDIT-LIMIT` S9(10)V99 + `VB2-ACCT-REISSUE-YYYY` X(4) |

On mainframe, `RECFM=VB` prepends a 4-byte Record Descriptor Word (RDW) to each logical record:
2-byte big-endian total length (payload + 4) + 2-byte segment flag `0x00 0x00`. Maximum payload =
`LRECL` 84 − 4 = 80 bytes (matches `FROM 10 TO 80`). The Java port must write the same RDW format
to the single VBR file, or use the split-file strategy (see §3.2).

### 1.6 Packed decimal (COMP-3) format

COMP-3 stores two BCD digits per byte (nibbles). For `PIC S9(10)V99` (12 digit positions):

| Attribute        | Value                                                                             |
| ---------------- | --------------------------------------------------------------------------------- |
| Bytes on disk    | ceil((12+1)/2) = **7 bytes**                                                      |
| Digit nibbles    | High nibble of byte 0 = leading zero pad; then 12 digit nibbles left-to-right    |
| Sign nibble      | Low nibble of byte 6: `0xC` = positive, `0xD` = negative                         |

Examples:

```
+2525.00  (unscaled 000000252500):
  Byte: 0x00  0x00  0x00  0x02  0x52  0x50  0x0C
         pad  000   000   025   250   000   sign=C(+)

-2500.00  (unscaled 000000250000):
  Byte: 0x00  0x00  0x00  0x02  0x50  0x00  0x0D
                                              sign=D(−)

 0.00     (unscaled 000000000000):
  Byte: 0x00  0x00  0x00  0x00  0x00  0x00  0x0C
```

COBOL `INITIALIZE` sets numeric COMP-3 fields to zero → 7 bytes of `0x00 0x00 0x00 0x00 0x00 0x00 0x0C`.

This format is entirely different from zoned decimal. A new `PackedDecimalCodec` class is required.

### 1.7 COBDATFT assembler call

`COBDATFT` is an external IBM assembler routine not runnable under GnuCOBOL or Java. Both the
portable oracle and the Java port must inline its logic:

| Item         | Value                                                                   |
| ------------ | ----------------------------------------------------------------------- |
| Input field  | `CODATECN-INP-DATE` (20 bytes) — `ACCT-REISSUE-DATE` left-justified    |
| Input type   | `CODATECN-TYPE = '2'` → format is `YYYY-MM-DD`                         |
| Output type  | `CODATECN-OUTTYPE = '2'` → format is `YYYYMMDD`                        |
| Output field | `CODATECN-0UT-DATE` (20 bytes): 8 date chars + 12 trailing spaces      |
| Moved to     | `OUT-ACCT-REISSUE-DATE` PIC X(10) → first 10 bytes → `YYYYMMDD  `     |

Java equivalent (new utility class `DateConverter`):
```java
static String reformatDate(String yyyyMmDd) {
    // Input "YYYY-MM-DD" → "YYYYMMDD  " (8 chars + 2 spaces, right-padded to 10)
    return yyyyMmDd.replace("-", "") + "  ";
}
```

Edge case: if `ACCT-REISSUE-DATE = "0000-00-00"`, output must be `"00000000  "`.

---

## 2. Equivalence requirements (acceptance criteria)

Three output datasets must match byte-for-byte between the GnuCOBOL oracle run and the Java run
on the same input fixture.

**Portable oracle uses the split-VBR strategy** (see §3.3): the single RECFM=VB file is replaced
by two fixed-length sequential files to avoid RDW format ambiguity.

| Artifact          | Format      | Acceptance check                                      |
| ----------------- | ----------- | ----------------------------------------------------- |
| `outfile.bin`     | 107-byte FB | `diff -q outfile.cobol.bin outfile.java.bin` silent   |
| `arryfile.bin`    | 110-byte FB | `diff -q arryfile.cobol.bin arryfile.java.bin` silent |
| `vbr1.dat`        | 12-byte fixed | `diff -q vbr1.cobol.dat vbr1.java.dat` silent       |
| `vbr2.dat`        | 39-byte fixed | `diff -q vbr2.cobol.dat vbr2.java.dat` silent       |

**No timestamp fields exist in `CBACT01C`.** There is no `FUNCTION CURRENT-DATE` call and no
PARM-based transaction ID. Timestamp injection (the primary complication in CBACT04C) is not
needed here — simplifying the test setup considerably.

**One source of divergence to neutralize:**
- **`COBDATFT` behavior** — must be implemented identically in both the portable oracle (inline
  COBOL string manipulation) and Java (`DateConverter`). A dedicated unit test verifies the exact
  10-byte output for known input dates including the zero-date edge case.

---

## 3. Target architecture

### 3.1 Package layout

```
app/java/batch_processing_workflow/
└── src/
    ├── main/java/com/carddemo/batch/
    │   ├── account/                                  ← new subpackage for CBACT01C
    │   │   ├── AccountExtractor.java                 main(); mirrors PROCEDURE DIVISION
    │   │   ├── domain/
    │   │   │   ├── ExtractAccountRecord.java         wraps CVACT01Y (300 bytes); exposes all 11 fields
    │   │   │   ├── OutAccountRecord.java             mirrors OUT-ACCT-REC (107 bytes)
    │   │   │   ├── ArrayRecord.java                  mirrors ARR-ARRAY-REC (110 bytes, OCCURS 5)
    │   │   │   ├── Vbr1Record.java                   mirrors VBRC-REC1 (12 bytes)
    │   │   │   └── Vbr2Record.java                   mirrors VBRC-REC2 (39 bytes)
    │   │   ├── io/
    │   │   │   ├── PackedDecimalCodec.java           NEW — encode/decode COMP-3 packed decimal
    │   │   │   ├── AccountFileReader.java            sequential reader for ACCTFILE (300-byte lines)
    │   │   │   ├── OutAccountWriter.java             sequential writer → outfile.bin (107-byte records)
    │   │   │   ├── ArrayRecordWriter.java            sequential writer → arryfile.bin (110-byte records)
    │   │   │   └── VbrRecordWriter.java              writes two files: vbr1.dat (12B) + vbr2.dat (39B)
    │   │   └── util/
    │   │       └── DateConverter.java                COBDATFT replacement: YYYY-MM-DD → YYYYMMDD__
    │   └── interest/                                 ← existing, UNCHANGED
    │       └── ...
    └── test/java/com/carddemo/batch/account/
        ├── PackedDecimalCodecTest.java
        ├── DateConverterTest.java
        ├── ExtractAccountRecordRoundTripTest.java
        ├── OutAccountRecordTest.java
        ├── ArrayRecordTest.java
        ├── VbrRecordTest.java
        ├── AccountExtractorTest.java                 end-to-end against fixtures
        └── AccountGoldenFileEquivalenceIT.java       diff vs. COBOL oracle outputs
```

**Reused unchanged from `com.carddemo.batch.interest`:**

| Class | Location | Reuse purpose |
| ----- | -------- | ------------- |
| `ZonedDecimalCodec` | `interest.io` | Decode/encode `S9(10)V99` zoned-decimal in input and output records |
| `FixedWidthFormat`  | `interest.io` | `text()`, `unsignedNumeric()`, `writeText()`, `writeUnsignedNumeric()`, `padToLength()` |
| `BatchAbendException` | `interest.util` | ABEND path → `System.exit(999)` |

> **Package debt note:** `ZonedDecimalCodec`, `FixedWidthFormat`, and `BatchAbendException` currently
> live under `com.carddemo.batch.interest.io` / `.util`. The `account` package will import from
> there. When a third batch program is added these should be promoted to a shared
> `com.carddemo.batch.io` / `.util` to remove the cross-concern dependency.

### 3.2 Key design decisions

| Decision | Default | Why |
| -------- | ------- | --- |
| Java version | **Java 17** | Same module as `interest`; no version change needed |
| Build system | **Maven** (existing module) | CBACT01C code lives in the same `batch_processing_workflow` Maven module |
| Decimal type | **`BigDecimal`** | Mandatory for monetary correctness; never `double`/`float` |
| COMP-3 codec | New **`PackedDecimalCodec`** | `ZonedDecimalCodec` handles only zoned decimal; packed decimal is a distinct binary format |
| VBR file strategy | **Split into two fixed-length files** (`vbr1.dat`, `vbr2.dat`) | Avoids RDW format ambiguity between GnuCOBOL and Java; the 12-byte and 39-byte payloads become two `RECORD SEQUENTIAL` outputs, making `diff` straightforward |
| `COBDATFT` replacement | **`DateConverter.reformatDate()`** | Assembler call cannot run outside mainframe; the conversion is pure string: strip dashes, right-pad to 10 bytes with spaces |
| `AccountRecord` strategy | New **`ExtractAccountRecord`** in `account.domain` | Existing `AccountRecord` in `interest.domain` exposes only 5 of the 11 CVACT01Y fields needed here; a dedicated class avoids widening the `interest` API for `account` concerns |

### 3.3 Why a sequential-file GnuCOBOL port

`CBACT01C`'s `ACCTFILE-FILE` is `INDEXED SEQUENTIAL` — it requires a VSAM KSDS in production.
GnuCOBOL supports indexed files via Berkeley DB (`--with-db`), but that requires extra packages and
a loader for the ASCII text fixtures. A **sequential-only portable port** (`CBACT01P.cbl`) changes
`ACCTFILE-FILE` from `INDEXED SEQUENTIAL` to `LINE SEQUENTIAL INPUT`. All three output paths and
all business logic paragraphs are copied verbatim.

For `VBRC-FILE`, the portable oracle replaces `RECORDING MODE IS V` with two `RECORD SEQUENTIAL`
files (`VBRC1-FILE` for VBR1 records, `VBRC2-FILE` for VBR2 records), each with a fixed
`RECORD CONTAINS n CHARACTERS` clause. The Java port writes the same two fixed-length files,
making the `diff` comparison clean.

---

## 4. Step-by-step migration steps

Each step is intended to be a discrete commit. Steps 1–9 are Java; step 10 is the COBOL reference;
steps 11–14 are tests.

| #  | Step | Acceptance |
| -- | ---- | ---------- |
| 1  | Implement `PackedDecimalCodec` — encode/decode `S9(n)Vp` COMP-3 for arbitrary `n` and scale, signed | Unit test round-trips all hard-coded constants (`1005.00`, `1525.00`, `-1025.00`, `-2500.00`, `2525.00`) and zero; coverage 100% of nibble paths |
| 2  | Implement `DateConverter.reformatDate()` | Unit test: `"2022-07-18"` → `"20220718  "`; `"0000-00-00"` → `"00000000  "` |
| 3  | Define `ExtractAccountRecord` (300-byte wrapper; all 11 CVACT01Y field accessors + `toBytes()`) | Parses `acctdata.txt` fixture; all accessors return expected values; `toBytes()` is byte-identical to input |
| 4  | Define `OutAccountRecord` (107-byte builder — takes 11 Java-typed fields + `outCycDebit` loop state; encodes via `ZonedDecimalCodec` for zoned fields and `PackedDecimalCodec` for offset-90 debit) | Builds record from known values; byte 90–96 is correct packed decimal |
| 5  | Define `ArrayRecord` (110-byte encoder from account ID + 5-element value lists; INITIALIZE semantics for occurrences 4–5) | Entries 4–5 produce correct zero-bytes for both zoned and packed fields |
| 6  | Define `Vbr1Record` (12 bytes) and `Vbr2Record` (39 bytes) | Encode round-trips for known account data; byte counts are exact |
| 7  | Implement `AccountFileReader` (sequential 300-byte-per-line reader, ISO-8859-1, pads short lines) | Reads `acctdata.txt`; yields N records matching line count |
| 8  | Implement `OutAccountWriter`, `ArrayRecordWriter`, `VbrRecordWriter` (each writes raw bytes per record; VBR writer writes two `Path` targets) | Each writer appends correct-length records; `VbrRecordWriter` produces `vbr1.dat` and `vbr2.dat` |
| 9  | Implement `AccountExtractor.main()` — wires all paragraphs with comments naming COBOL paragraph; carries `outCycDebit` loop state | Runs end-to-end on fixtures; all four output files are non-empty |
| 10 | Write `cobol-reference/CBACT01P.cbl` (sequential-only portable oracle) + `tools/build.sh` + `tools/run.sh` + `tools/regen-golden.sh` | `bash regen-golden.sh` produces `fixtures/expected/{outfile.bin,arryfile.bin,vbr1.dat,vbr2.dat}` |
| 11 | Write unit tests for all new classes (§6.1) | All pass; `PackedDecimalCodecTest` and `DateConverterTest` achieve 100% branch coverage |
| 12 | Write `AccountExtractorTest` (end-to-end against project fixtures; asserts record counts and spot-check field values) | Passes; output record counts match input record count |
| 13 | Write `AccountGoldenFileEquivalenceIT` — runs `AccountExtractor`, then `Files.mismatch(expected, actual) == -1L` for all four files | Passes; zero-byte divergence |
| 14 | Update `docs/cobol/batch-programs.md` CBACT01C entry with pointer to this plan and Java port location | Pointer added |

**Estimated effort:** ~1.5–2 focused engineering days. Simpler than CBACT04C (no inter-file
lookups, no running total, no timestamp injection). The main new work is in step 1 (`PackedDecimalCodec`)
and step 10 (portable oracle with COBDATFT inline).

---

## 5. Logic equivalency safeguards

These are the spots where an incorrect Java implementation will silently produce divergent output.
Each must have a dedicated unit test.

### 5.1 Packed decimal encoding

```java
// CORRECT — 7 bytes for PIC S9(10)V99 COMP-3, positive value
byte[] packed = PackedDecimalCodec.encode(new BigDecimal("2525.00"), 12, 2);
// expected: [0x00, 0x00, 0x00, 0x02, 0x52, 0x50, 0x0C]

// CORRECT — negative value
byte[] packed = PackedDecimalCodec.encode(new BigDecimal("-2500.00"), 12, 2);
// expected: [0x00, 0x00, 0x00, 0x02, 0x50, 0x00, 0x0D]

// CORRECT — INITIALIZE zero
byte[] packed = PackedDecimalCodec.encode(BigDecimal.ZERO.setScale(2), 12, 2);
// expected: [0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0C]
```

The test must cover all five hard-coded constants and zero. A round-trip test (`encode` then
`decode`) must hold for any `BigDecimal` within the 12-digit, scale-2 range.

### 5.2 INITIALIZE semantics for `ARR-ARRAY-REC`

`INITIALIZE` sets:
- Numeric display/zoned fields (`ARR-ACCT-CURR-BAL`) → `0` → 12-byte zoned encoding with trailing
  overpunch `{` (e.g. `"000000000000{"` right-justified — wait, the COBOL stores the digits zero-padded
  with trailing sign, so zero = `"00000000000{"`, 11 digit chars + `{`).
- Numeric COMP-3 fields (`ARR-ACCT-CURR-CYC-DEBIT`) → `0` → packed-decimal zero:
  `[0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0C]`.
- Alphanumeric filler (`ARR-FILLER`) → 4 spaces `[0x20, 0x20, 0x20, 0x20]`.

A test must assert the exact bytes for occurrences 4 and 5 after an `ArrayRecord` constructed with
only the first three occurrences populated.

### 5.3 Sticky-debit loop state

`OUT-ACCT-CURR-CYC-DEBIT` is only written if `ACCT-CURR-CYC-DEBIT == 0`; otherwise the field
retains its value from the previous iteration. Java must replicate this with explicit state:

```java
BigDecimal outCycDebit = BigDecimal.ZERO.setScale(2); // WS initial value
for (ExtractAccountRecord acct : reader) {
    if (acct.currCycDebit().signum() == 0) {
        outCycDebit = new BigDecimal("2525.00");
    }
    // outCycDebit is used as-is when currCycDebit != 0 (carry-over from previous iteration)
    writer.write(buildOutRecord(acct, outCycDebit));
}
```

Test fixtures must include at least:

| Scenario | Account debit | Expected `OUT-ACCT-CURR-CYC-DEBIT` |
| -------- | ------------- | ------------------------------------ |
| First record, debit = 0 | 0 | 2525.00 |
| First record, debit ≠ 0 | e.g. 300.00 | 0.00 (WS initial) |
| Record 2 after record 1 had debit ≠ 0; record 2 debit = 0 | 0 | 2525.00 |
| Record 3 after record 2 had debit = 0; record 3 debit ≠ 0 | e.g. 500.00 | 2525.00 (carried from record 2) |

### 5.4 COBDATFT output byte layout

`OUT-ACCT-REISSUE-DATE` is PIC X(10), filled from the first 10 bytes of a 20-byte date-conversion
output buffer. The result is always exactly `"YYYYMMDD  "` (8 ASCII digit chars + 2 spaces). The
Java codec must never produce 9 chars or 11 chars.

### 5.5 VBR2 reissue year — source is `WS-ACCT-REISSUE-DATE`, not COBDATFT output

`VB2-ACCT-REISSUE-YYYY` = `WS-ACCT-REISSUE-YYYY` = first 4 bytes of `ACCT-REISSUE-DATE`
(set by `MOVE ACCT-REISSUE-DATE TO WS-REISSUE-DATE` in paragraph 1300, **before** the COBDATFT
call). It is the raw `YYYY` from the source record, not the reformatted output. This is a
subtle distinction: `OUT-ACCT-REISSUE-DATE` uses the COBDATFT result; `VBR2` uses the raw year.

### 5.6 Explicit file close order

All four files must be closed and fully flushed before the process exits. The JVM may not flush
`OutputStream` buffers on `System.exit()`. Use try-with-resources for all four writers to
guarantee close even on ABEND paths.

---

## 6. Test strategy

### 6.1 Unit tests (fast, hermetic, no I/O)

| Test class | Concern |
| ---------- | ------- |
| `PackedDecimalCodecTest` | Encode/decode: all five hard-coded constants, zero, max 12-digit value, negative |
| `DateConverterTest` | Normal date, zero date (`"0000-00-00"`), output is exactly 10 chars |
| `ExtractAccountRecordRoundTripTest` | parse → `toBytes()` is byte-identical; all 11 accessors return expected values |
| `OutAccountRecordTest` | Build from known values; encode 107 bytes; COMP-3 bytes at offset 90 are correct |
| `ArrayRecordTest` | Occurrences 1–3 populated; 4–5 zero bytes; FILLER = 4 spaces |
| `VbrRecordTest` | VBR1 = 12 bytes exact; VBR2 = 39 bytes exact; field accessors correct |
| `StickyDebitTest` | Multi-account carry-over sequences (§5.3 table above) |

### 6.2 Round-trip integration test

```
parse(fixtures/input/acctdata.txt) → ExtractAccountRecord[]
records.forEach(r -> r.toBytes()) → assert bytes == original file bytes
```

Validates `ExtractAccountRecord` encoding without executing any business logic.

### 6.3 Golden-file integration test (the byte-for-byte guarantee)

```
1. cd cobol-reference && bash tools/regen-golden.sh
      → cobc -x -free CBACT01P.cbl
      → run with fixtures/input/acctdata.txt
      → copy outfile.bin, arryfile.bin, vbr1.dat, vbr2.dat → fixtures/expected/

2. mvn verify  (or mvn test -Pit)
      → AccountGoldenFileEquivalenceIT:
          • copies fixtures/input/ to temp dir
          • runs AccountExtractor.main(...)
          • Files.mismatch(expected/outfile.bin,  temp/outfile.bin)  == -1L
          • Files.mismatch(expected/arryfile.bin, temp/arryfile.bin) == -1L
          • Files.mismatch(expected/vbr1.dat,     temp/vbr1.dat)     == -1L
          • Files.mismatch(expected/vbr2.dat,     temp/vbr2.dat)     == -1L
```

### 6.4 Synthetic edge-case fixtures

| Fixture | Purpose |
| ------- | ------- |
| `single-account/` | 1 account; all four output files have exactly 1 record each (VBR: 1 VBR1 + 1 VBR2) |
| `zero-debit/` | Debit = 0 → OUT `CYC-DEBIT` field = COMP-3 encoding of `2525.00` |
| `nonzero-debit-first/` | First account has non-zero debit → OUT field = COMP-3 encoding of `0.00` (WS initial) |
| `sticky-debit/` | Two accounts: first debit=`300.00`, second debit=`0` → second OUT carries `2525.00`, not `300.00` |
| `zero-reissue-date/` | `ACCT-REISSUE-DATE = "0000-00-00"` → `OUT-ACCT-REISSUE-DATE = "00000000  "` |
| `negative-balance/` | Negative `ACCT-CURR-BAL` → zoned overpunch in VBR2; signed COMP-3 in ARRYFILE occurrence 3 |

Each synthetic fixture has its own expected outputs regenerated from `CBACT01P.cbl`.

---

## 7. Documents to be produced during implementation

1. **`app/java/batch_processing_workflow/README.md`** — add a section for `AccountExtractor`:
   what it does, build command, run command, where the tests live.
2. **`app/java/batch_processing_workflow/HOW-TO-TEST.md`** — add CBACT01C section alongside
   existing CBACT04C instructions. Prerequisites, `regen-golden.sh` command, `mvn verify`, and
   troubleshooting (`xxd` hex dump for COMP-3 mismatches, correct byte offset arithmetic).
3. **`app/java/batch_processing_workflow/cobol-reference/README.md`** — add `CBACT01P.cbl`
   section: what was changed from `CBACT01C` (INDEXED → LINE SEQUENTIAL, COBDATFT inline, VBR
   split to two files), and the warning that this is a test oracle only.

---

## 8. Risks and open decisions

### 8.1 Risks

| Risk | Mitigation |
| ---- | ---------- |
| COMP-3 nibble order wrong → silent binary diff failure | `PackedDecimalCodecTest` tests every hard-coded value and both sign nibbles; mismatches surface in the golden-file IT at a known byte offset |
| `COBDATFT` behavior differs from simple dash-strip | Verify against any existing mainframe output samples in `app/data/`; if uncertain, treat as open decision and flag before implementation |
| GnuCOBOL `INITIALIZE` of COMP-3 fields produces different zero representation than `0x0C` | Add an assertion in `regen-golden.sh` that checks the known zero bytes for occurrence 4 (offset 80+4 = byte 84 of ARRYFILE record) |
| `LINE SEQUENTIAL` in GnuCOBOL strips trailing spaces from 300-byte account records | Use `COB_LS_FIXED=Y` runtime variable (same fix as CBACT04P) or switch to `RECORD SEQUENTIAL` with `RECORD CONTAINS 300 CHARACTERS` |
| Split-VBR strategy doesn't test the RDW format of the real `VBRCFILE` | Document as out-of-scope for the portable oracle; if production RDW fidelity is required, extend `VbrRecordWriter` to also emit a third `vbr-rdw.dat` with RDW-prefixed records |
| `OUT-ACCT-CURR-CYC-DEBIT` carry-over bug means fixture acctdata.txt with mostly-nonzero debits produces mostly-zero COMP-3 bytes | This is the faithful replication of the original. The golden-file test will capture it automatically; no special handling needed beyond correct Java loop state. |
| Package cross-dependency: `account` imports from `interest.io` and `interest.util` | Acceptable for now; tracked as tech debt. Refactor to `com.carddemo.batch.io` / `.util` when a third program is added. |

### 8.2 Open decisions to confirm before implementation

- **VBR strategy** — defaulting to split-file (Approach B). If production byte-identical RDW
  output is required for the integration test, say so and we'll use Approach A (single RECFM=VB
  file with RDW handling in both GnuCOBOL and Java).
- **`COBDATFT` specification** — defaulting to "strip dashes, pad to 10 chars with spaces". If a
  COBDATFT manual or sample mainframe output is available, confirm before implementation.
- **`ExtractAccountRecord` vs. extending `AccountRecord`** — defaulting to a new class in
  `account.domain`. If you prefer to add the missing accessors to the existing
  `interest.domain.AccountRecord`, flag it; the change is confined to 6 new getters.
- **Java version** — defaulting to 17 (same as existing module). OK?

---

## 9. Out of scope for this migration

- Other batch programs (`CBACT02C`, `CBACT03C`, `CBACT04C` [already done], `CBTRN0xC`, etc.).
- The online (`CO*`) programs.
- The three extension modules (`app-transaction-type-db2`, `app-vsam-mq`,
  `app-authorization-ims-db2-mq`).
- Production RDW/VBR binary format fidelity (the integration test uses the split-file oracle).
- CI/CD integration. The test plan is local-developer-runnable.
- Promotion of shared utilities (`ZonedDecimalCodec`, `FixedWidthFormat`, `BatchAbendException`)
  to a top-level `com.carddemo.batch.io` / `.util` package — deferred until a third batch program
  is added to validate the abstraction.

---

## 10. Approval gate

Confirm the following before implementation starts:

1. **VBR strategy** — split-file (Approach B) is acceptable, not single-RECFM=VB.
2. **`COBDATFT`** — dash-strip logic is the correct Java replacement.
3. **Defaults in §3.2** (Java 17, Maven, JUnit 5 + AssertJ, ISO-8859-1) — accept or override.
4. **`ExtractAccountRecord` in `account.domain`** rather than extending `interest.domain.AccountRecord` — accept or override.
5. **Scope locked to `CBACT01C`** — yes/no.

Once confirmed, I'll work through steps 1–14 in §4, in order, one commit per step.
