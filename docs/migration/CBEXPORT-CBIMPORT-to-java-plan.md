# Migration Plan: `CBEXPORT.cbl` + `CBIMPORT.cbl` → Java

**Source programs:**
- `app/cbl/CBEXPORT.cbl` — the **branch-migration export**: reads every record from each of the five master VSAM files and emits a single multi-record export VSAM file (KSDS keyed on a sequence counter). One COBOL record type per source file, distinguished by a single-byte `EXPORT-REC-TYPE` discriminator: `'C'`ustomer, `'A'`ccount, `'X'`ref, `'T'`ransaction, `'D'`-card.
- `app/cbl/CBIMPORT.cbl` — the **inverse**: reads the multi-record `EXPFILE` and fans it out to five normalized **sequential** output files (customer / account / xref / transaction / card), one record-type per file, with an additional error file for unknown record types.

The pair shares the central `CVEXPORT` 500-byte record layout (a header + a 460-byte payload `REDEFINES`-ed into five alternative shapes). They are migrated together to share the layout class and binary-numeric codecs.

**Driving JCL:** `app/jcl/CBEXPORT.jcl`, `app/jcl/CBIMPORT.jcl` (separate jobs).

**Target location (mandatory):** `app/java/batch_processing_workflow/`

**Goal:** A Java port that produces, for both directions:

- `CBEXPORT` Java run → `EXPFILE` byte-for-byte identical to the GnuCOBOL oracle's `EXPFILE`.
- `CBIMPORT` Java run on the same `EXPFILE` → five output files plus the optional error file, each byte-for-byte identical to the GnuCOBOL oracle's outputs.

This plan is purely a plan. **No code is written until approved.**

---

## 1. Source contract — what `CBEXPORT` and `CBIMPORT` do

### 1.1 `CBEXPORT` inputs

| DD name | Org / Access | Record (copybook) |
| ------- | ------------ | ----------------- |
| `CUSTFILE` | INDEXED / SEQUENTIAL | 500 bytes (`CVCUS01Y`) |
| `ACCTFILE` | INDEXED / SEQUENTIAL | 300 bytes (`CVACT01Y`) |
| `XREFFILE` | INDEXED / SEQUENTIAL | 50 bytes (`CVACT03Y`) |
| `TRANSACT` | INDEXED / SEQUENTIAL | 350 bytes (`CVTRA05Y`) |
| `CARDFILE` | INDEXED / SEQUENTIAL | 150 bytes (`CVACT02Y`) |

### 1.2 `CBEXPORT` output

| DD name | Org / Access | Record |
| ------- | ------------ | ------ |
| `EXPFILE` | INDEXED, OPENED `OUTPUT`, `RECORDING MODE F`, `RECORD CONTAINS 500` | 500 bytes (`CVEXPORT` — `EXPORT-RECORD`); KSDS keyed on `EXPORT-SEQUENCE-NUM PIC 9(9) COMP` (synthetic 4-byte big-endian) |

### 1.3 `CBIMPORT` inputs

| DD name | Org / Access | Record |
| ------- | ------------ | ------ |
| `EXPFILE` | KSDS or PS input; FD declares `RECORDING MODE F`, `RECORD CONTAINS 500` | 500 bytes (`CVEXPORT`) |

### 1.4 `CBIMPORT` outputs

| DD name | LRECL / RECFM | Source record |
| ------- | ------------- | ------------- |
| `CUSTOUT` | 500 / FB | `CVCUS01Y` shape (mapped from `EXP-CUSTOMER`) |
| `ACCTOUT` | 300 / FB | `CVACT01Y` shape (from `EXP-ACCOUNT`) |
| `XREFOUT` | 50 / FB | `CVACT03Y` shape (from `EXP-XREF`) |
| `TRNXOUT` | 350 / FB | `CVTRA05Y` shape (from `EXP-TRANSACTION`) |
| `CARDOUT` | 150 / FB | `CVACT02Y` shape (from `EXP-CARD`) |
| `ERROUT` | 132 / FB | Pipe-delimited error log line |

### 1.5 `CVEXPORT` layout — the central artefact (500 bytes)

```
01  EXPORT-RECORD.
    05  EXPORT-HEADER.
        10 EXPORT-REC-TYPE        PIC X(01).
        10 EXPORT-TIMESTAMP       PIC X(26).
        10 EXPORT-SEQUENCE-NUM    PIC 9(09) COMP.       (4-byte big-endian)
        10 EXPORT-BRANCH          PIC X(04).
        10 EXPORT-REGION          PIC X(05).
    05  EXPORT-PAYLOAD            PIC X(455).
        OR
        EXP-CUSTOMER       — REDEFINES EXPORT-PAYLOAD with CUSTOMER fields
        EXP-ACCOUNT        — REDEFINES with ACCOUNT fields (mixed COMP / COMP-3)
        EXP-XREF           — REDEFINES with XREF fields
        EXP-TRANSACTION    — REDEFINES with TRANSACTION fields (COMP-3 amounts)
        EXP-CARD           — REDEFINES with CARD fields
```

Header is 1 + 26 + 4 + 4 + 5 = **40 bytes**. Payload is **460 bytes**. Total **500 bytes**. The exact field layout of each `EXP-...` redefinition needs to be tabulated from `app/cpy/CVEXPORT.cpy` — step 1 of §4.

**Storage variants used in the payload:**

- `PIC 9(n) COMP` — binary integer, `n ≤ 4` → 2 bytes; `5 ≤ n ≤ 9` → 4 bytes; `n ≥ 10` → 8 bytes (see IBM RM).
- `PIC S9(p)V(s) COMP-3` — packed decimal, `ceil((p+s+1)/2)` bytes.
- `PIC X(n)` — fixed-width text.

**This is the only batch program in the catalog that uses `COMP` integers.** A new `BinaryIntegerCodec` is required.

### 1.6 `CBEXPORT` algorithm

```
0000-MAIN-PROCESSING:
    1000-INITIALIZE
        1050-GENERATE-TIMESTAMP   build EXPORT-TIMESTAMP
        OPEN INPUT  CUSTFILE, ACCTFILE, XREFFILE, TRANSACT, CARDFILE
        OPEN OUTPUT EXPFILE
        WS-SEQUENCE-COUNTER = 0
    2000-EXPORT-CUSTOMERS
        prime-read CUSTFILE; loop until EOF (status '10'):
            2200-CREATE-CUST-EXP-REC:  populate header + EXP-CUSTOMER
            ADD 1 TO WS-SEQUENCE-COUNTER
            MOVE WS-SEQUENCE-COUNTER TO EXPORT-SEQUENCE-NUM
            WRITE EXPORT-OUTPUT-RECORD FROM EXPORT-RECORD
            ADD 1 TO WS-CUSTOMER-COUNT
    3000-EXPORT-ACCOUNTS         (same pattern, EXP-ACCOUNT)
    4000-EXPORT-XREFS            (same pattern, EXP-XREF)
    5000-EXPORT-TRANSACTIONS     (same pattern, EXP-TRANSACTION)
    5500-EXPORT-CARDS            (same pattern, EXP-CARD)
    6000-FINALIZE
        CLOSE all 6 files
        DISPLAY total counts (one DISPLAY per record type)
GOBACK
```

Output records are written in the order:
**all customers → all accounts → all xrefs → all transactions → all cards**

Each gets a monotonically increasing `EXPORT-SEQUENCE-NUM` starting from 1.

### 1.7 `CBIMPORT` algorithm

```
0000-MAIN-PROCESSING:
    1000-INITIALIZE
        Build current date/time strings via FUNCTION CURRENT-DATE(1:4) etc.
        OPEN all 6 input/output files
    2000-PROCESS-EXPORT-FILE
        prime-read EXPFILE; loop until EOF:
            2200-PROCESS-RECORD-BY-TYPE:
                EVALUATE EXPORT-REC-TYPE
                    WHEN 'C': 2300-PROCESS-CUSTOMER-RECORD  (INITIALIZE; map fields; WRITE CUSTOUT)
                    WHEN 'A': 2400-PROCESS-ACCOUNT-RECORD
                    WHEN 'X': 2500-PROCESS-XREF-RECORD
                    WHEN 'T': 2600-PROCESS-TRANSACTION-RECORD
                    WHEN 'D': 2650-PROCESS-CARD-RECORD
                    WHEN OTHER: 2700-PROCESS-UNKNOWN-RECORD → 2750-WRITE-ERROR (132-byte pipe-delimited)
                ADD 1 TO appropriate counter
    3000-VALIDATE-IMPORT (placeholder; always reports success)
    4000-FINALIZE
        CLOSE all files
        DISPLAY counts per record type
GOBACK
```

### 1.8 Numeric formats

| Storage | Where it appears | Java codec |
| ------- | ---------------- | ---------- |
| Zoned decimal `S9(n)V(s)` | All source files; output PS files | `ZonedDecimalCodec` (existing) |
| Packed decimal `S9(n)V(s) COMP-3` | `EXP-ACCOUNT`, `EXP-TRANSACTION` payloads | `PackedDecimalCodec` (from `CBACT01C` plan) |
| Binary integer `9(n) COMP` | `EXP-CUST-ID PIC 9(09) COMP`, `EXPORT-SEQUENCE-NUM`, etc. | NEW `BinaryIntegerCodec` |

### 1.9 Timestamps

`1050-GENERATE-TIMESTAMP` in `CBEXPORT` builds a 26-byte timestamp via `ACCEPT FROM DATE YYYYMMDD` and `ACCEPT FROM TIME` plus `STRING`-based formatting. **This is** non-deterministic — must be injected.

`CBIMPORT`'s `1000-INITIALIZE` similarly does `MOVE FUNCTION CURRENT-DATE(1:4) TO …` — non-deterministic. Must be injected.

Both tap into a shared `Db2Timestamp.useFixedClock(...)` for tests.

---

## 2. Equivalence requirements (acceptance criteria)

### 2.1 `CBEXPORT` direction

1. **`EXPFILE`** matches byte-for-byte (500-byte records).

`Files.mismatch(expected/expfile.dat, actual/expfile.dat) == -1L`

### 2.2 `CBIMPORT` direction

1. **`CUSTOUT`** (500-byte records) byte-for-byte.
2. **`ACCTOUT`** (300-byte records) byte-for-byte.
3. **`XREFOUT`** (50-byte records) byte-for-byte.
4. **`TRNXOUT`** (350-byte records) byte-for-byte.
5. **`CARDOUT`** (150-byte records) byte-for-byte.
6. **`ERROUT`** (132-byte pipe-delimited records) byte-for-byte. Empty file if no unknown records.

### 2.3 Round-trip property

Beyond Java≡COBOL byte-equivalence, the pair has a useful round-trip property:

- `original master files → CBEXPORT (Java) → CBIMPORT (Java) → reconstructed master files`

The reconstructed files should equal the originals **for the fields the export carries**. (Some shape-dependent fields like FILLER may differ, since `CBIMPORT` `INITIALIZE`s the destination record before the field-by-field copy.) This is captured as a non-blocking `RoundTripPropertyTest` for confidence; the hard acceptance is the GnuCOBOL byte-equivalence.

### 2.4 Sources of divergence to neutralize

- **`EXPORT-TIMESTAMP`** in every record. Inject via
  `Db2Timestamp.useFixedClock(...)` and a COBOL compile-time switch.
- **`CBIMPORT`'s current-date string** (used in `ERROUT` lines). Same
  treatment.

---

## 3. Target architecture

### 3.1 Layout

```
app/java/batch_processing_workflow/
└── src/
    ├── main/java/com/carddemo/batch/
    │   ├── exportimport/                              ← new subpackage for the pair
    │   │   ├── DataExporter.java                      main(); mirrors CBEXPORT
    │   │   ├── DataImporter.java                      main(); mirrors CBIMPORT
    │   │   ├── domain/
    │   │   │   ├── ExportRecord.java                  500-byte multi-shape record
    │   │   │   ├── ExportHeader.java                  40-byte header
    │   │   │   ├── CustomerPayload.java               460-byte EXP-CUSTOMER shape
    │   │   │   ├── AccountPayload.java                460-byte EXP-ACCOUNT shape (mixed COMP/COMP-3)
    │   │   │   ├── XrefPayload.java                   460-byte EXP-XREF shape
    │   │   │   ├── TransactionPayload.java            460-byte EXP-TRANSACTION shape (COMP-3)
    │   │   │   └── CardPayload.java                   460-byte EXP-CARD shape
    │   │   ├── io/
    │   │   │   ├── BinaryIntegerCodec.java            NEW — emulates COMP big-endian integers
    │   │   │   ├── ExportFileWriter.java              500-byte append; preserves source-file order
    │   │   │   ├── ExportFileReader.java              prime-read + sequential next
    │   │   │   ├── (per-output) CustomerOutputWriter, AccountOutputWriter, … (5 writers, 1 per type)
    │   │   │   └── ErrorOutputWriter.java             132-byte pipe-delimited
    │   │   └── format/
    │   │       └── ErrorLineBuilder.java              builds 132-byte pipe-delimited ERROUT line
    │   ├── account/                                   ← UNCHANGED (provides PackedDecimalCodec)
    │   ├── interest/                                  ← UNCHANGED (provides ZonedDecimalCodec, Db2Timestamp)
    │   └── …
    └── test/java/com/carddemo/batch/exportimport/
        ├── BinaryIntegerCodecTest.java
        ├── ExportRecordRoundTripTest.java             encode → decode for all 5 shapes
        ├── DataExporterTest.java
        ├── DataImporterTest.java
        ├── DataExportImportRoundTripTest.java         CBEXPORT→CBIMPORT pipeline; reconstructs originals
        ├── ExportGoldenFileEquivalenceIT.java
        └── ImportGoldenFileEquivalenceIT.java
```

### 3.2 Decisions

| Decision | Default | Why |
| -------- | ------- | --- |
| Java version | **Java 17** | Same module |
| Codecs | **Reuse `ZonedDecimalCodec` + `PackedDecimalCodec`; add new `BinaryIntegerCodec`** | The pair is the only catalog entry using all three at once |
| Multi-shape `ExportRecord` | **One Java class with five typed views** (`asCustomer()`, `asAccount()`, …) backed by a 500-byte buffer | Mirrors the `REDEFINES` semantics; preserves byte-equivalence on round-trip |
| `EXPORT-SEQUENCE-NUM` storage | **`BinaryIntegerCodec.writeInt32BE(buf, 31, value)`** | 4-byte big-endian; matches z/OS `COMP` |
| `EXPFILE` ordering | **Strict source-order: customers (by KSDS key), accounts, xrefs, transactions, cards** | Matches the `EXPORT-CUSTOMERS` → `EXPORT-CARDS` paragraph chain in `CBEXPORT` |
| Sequence counter init | **1, monotonic** | First customer record has sequence 1 |
| `CBIMPORT` `INITIALIZE` semantics | **Java equivalent: zero/space-fill the destination record before field-by-field copy** | Matches COBOL `INITIALIZE` behaviour; FILLER bytes become spaces, numerics become zeros |
| Unknown record handler | **Pipe-delimited 132-byte error line** | Matches `2700-PROCESS-UNKNOWN-RECORD` |
| `3000-VALIDATE-IMPORT` | **Empty stub with a `// TODO: validation per CBIMPORT.cbl`** | Faithful to the source's placeholder |
| Timestamp injection | **`Db2Timestamp.useFixedClock(...)`** | Reuse |

### 3.3 New shared utility — `BinaryIntegerCodec`

```java
public final class BinaryIntegerCodec {
    public static int  readUnsignedInt16BE(byte[] buf, int offset);
    public static int  readUnsignedInt32BE(byte[] buf, int offset);
    public static long readUnsignedInt64BE(byte[] buf, int offset);

    public static void writeUnsignedInt16BE(byte[] buf, int offset, int value);
    public static void writeUnsignedInt32BE(byte[] buf, int offset, int value);
    public static void writeUnsignedInt64BE(byte[] buf, int offset, long value);

    public static int byteWidthForDigits(int digits);
    // 1–4 digits → 2 bytes; 5–9 → 4 bytes; 10–18 → 8 bytes
}
```

Promoted to shared `com.carddemo.batch.io` package as part of this plan (no other batch programs use COMP integers — the pair is the first and only consumer right now, but a future `EXEC SQL` host-variable port would reuse).

### 3.4 Why a sequential-file GnuCOBOL port

`CBEXPORT-P.cbl`:
- All five INDEXED inputs change from `INDEXED SEQUENTIAL` to `LINE
  SEQUENTIAL INPUT` (already sequential access; `INDEXED` is a no-op
  for the read path).
- `EXPFILE` (output, INDEXED) → `RECORD SEQUENTIAL` with explicit
  fixed `RECORD CONTAINS 500 CHARACTERS` clause. **Cannot use `LINE
  SEQUENTIAL`** because the binary `COMP` and `COMP-3` bytes contain
  newline-equivalent values (`0x0A`, `0x0D`) that `LINE SEQUENTIAL`
  would interpret as record terminators.
- All business-logic paragraphs verbatim.
- `1050-GENERATE-TIMESTAMP` replaced with a `MOVE 'fixed-string'`
  under a `-DTEST_FIXED_TS` switch.

`CBIMPORT-P.cbl`:
- `EXPFILE` (input, INDEXED) → `RECORD SEQUENTIAL` with `RECORD
  CONTAINS 500 CHARACTERS`.
- All five outputs were already PS — `LINE SEQUENTIAL OUTPUT`. **But**
  the source records contain no embedded binary, so `LINE SEQUENTIAL`
  is safe for the outputs.
- `ERROUT` was already PS — `LINE SEQUENTIAL OUTPUT`.
- Current-date construction in `1000-INITIALIZE` replaced with the
  same `-DTEST_FIXED_TS` switch.

---

## 4. Step-by-step migration steps

| # | Step | Acceptance |
| - | ---- | ---------- |
| 1 | Tabulate `CVEXPORT.cpy` from `app/cpy/`. List every field in `EXP-CUSTOMER`, `EXP-ACCOUNT`, `EXP-XREF`, `EXP-TRANSACTION`, `EXP-CARD` with: offset, length, PIC, storage (DISPLAY/COMP/COMP-3), source-file field. Verify each shape is exactly 460 bytes. | Tables added as appendix to this plan |
| 2 | Implement `BinaryIntegerCodec` (read+write for 16, 32, 64-bit big-endian unsigned). | Unit test covers boundary values, max ranges |
| 3 | Define `ExportHeader` (40 bytes: 1 type + 26 ts + 4 seq + 4 branch + 5 region). | Round-trip test |
| 4 | Define `CustomerPayload` (460 bytes from `CVCUS01Y` mapping). | Round-trip with synthetic record |
| 5 | Define `AccountPayload` (460 bytes; mixed zoned + COMP-3 from `CVACT01Y`). | Round-trip; specifically verify COMP-3 amounts encode correctly |
| 6 | Define `XrefPayload`, `TransactionPayload`, `CardPayload` (similar). | Round-trips |
| 7 | Define `ExportRecord` — 500-byte container with `getHeader()`, `asCustomer()`, `asAccount()`, …, `asCard()` and a `recordType()` getter. | Test that `record.asCustomer().toBytes()` round-trips through `ExportRecord.fromBytes(bytes)` byte-identically |
| 8 | Implement `ExportFileWriter` (500-byte append) and `ExportFileReader` (prime-read + sequential next). | Smoke tests |
| 9 | Implement `DataExporter.main()` — opens 5 inputs, walks each in source order, builds payloads, increments sequence, writes to `EXPFILE`. | Runs end-to-end on fixtures |
| 10 | Implement the 5 output writers + `ErrorOutputWriter`. | Smoke tests |
| 11 | Implement `DataImporter.main()` — opens `EXPFILE`, dispatches per record type, INITIALIZEs+maps+writes destination, increments counters. Unknown type → error line. | Runs end-to-end |
| 12 | Write `cobol-reference/CBEXPORT-P.cbl` and `CBIMPORT-P.cbl` (sequential ports as in §3.4) plus build/run/regen scripts (`-fsign=EBCDIC`, `COB_LS_FIXED=Y`, `-DTEST_FIXED_TS`). | `bash regen-golden.sh` produces `fixtures/expected/{expfile.dat, custout.dat, acctout.dat, xrefout.dat, trnxout.dat, cardout.dat, errout.dat}` |
| 13 | Write the unit tests in §6. | All pass |
| 14 | Write `ExportGoldenFileEquivalenceIT` and `ImportGoldenFileEquivalenceIT`. | Both pass |
| 15 | Write `DataExportImportRoundTripTest` (Java→Java pipeline; reconstructs originals modulo INITIALIZE artifacts). | Passes |
| 16 | Update `docs/cobol/batch-programs.md` `CBEXPORT` and `CBIMPORT` section pointers. | Pointers added |

**Estimated effort:** ~3–4 engineering days. The bulk is in step 1 (tabulation; ~150 fields across 5 payload shapes) and steps 4–7 (codec mapping). Step 2 is small (~50 lines plus tests).

---

## 5. Logic equivalency safeguards

### 5.1 `EXPORT-SEQUENCE-NUM` byte order

`PIC 9(09) COMP` is a 4-byte signed big-endian integer on z/OS. **Big-endian, not little-endian.** `BinaryIntegerCodec.writeUnsignedInt32BE(buf, 31, value)` writes the high byte first. A test must assert `seq=1 → bytes [0x00, 0x00, 0x00, 0x01]`, not `[0x01, 0x00, 0x00, 0x00]`.

### 5.2 Sequence counter spans all five record types

The counter increments once per output record, not once per source file. Customer 1 → seq 1; customer 2 → seq 2; …; first account → seq (custCount + 1); etc. After all 5 source files, the maximum seq equals the total record count.

### 5.3 COMP-3 storage in the export

`EXP-ACCT-CURR-BAL PIC S9(10)V99 COMP-3` → 7 bytes packed decimal. Reuse `PackedDecimalCodec` from the `CBACT01C` plan. A round-trip test on a known account record must produce identical bytes through the export pipeline.

### 5.4 `INITIALIZE` semantics on output records in `CBIMPORT`

Each handler does `INITIALIZE CUSTOMER-RECORD` before mapping fields. COBOL `INITIALIZE` zeros all numeric fields (zoned and packed) and spaces all alphanumeric fields. **Then** the field-by-field MOVE overwrites only the fields carried by the export.

Java equivalent for the customer write:

```java
byte[] custBuf = new byte[500];
Arrays.fill(custBuf, (byte) ' ');                              // alphanumeric initialize
// (numeric INITIALIZE: zeros at known offsets — handled by MOVE below)
// Field-by-field map from EXP-CUSTOMER to CVCUS01Y:
FixedWidthFormat.writeUnsignedNumeric(custBuf, 0, 9, exp.getCustId());
FixedWidthFormat.writeText(custBuf, 9, 25, exp.getFirstName());
// ... etc
out.write(custBuf);
```

Bytes that aren't in the EXP shape (e.g. fields the export elected not to carry) keep their `INITIALIZE` defaults — typically zeros and spaces. **The Java port must zero numerics that aren't overwritten**, since `Arrays.fill(buf, (byte) ' ')` doesn't zero them.

### 5.5 Multi-record `REDEFINES` byte-fidelity

When `ExportRecord.fromBytes(bytes)` is called, the 460-byte payload is preserved as-is regardless of the record type. `asCustomer()` etc. return typed views over the same bytes. **The container does not interpret the payload during reads — only the destination view does.** This means a malformed record (e.g. `EXPORT-REC-TYPE='C'` but the payload bytes are an account shape) round-trips without rejection on read. The error path is in `CBIMPORT.2200-PROCESS-RECORD-BY-TYPE` `WHEN OTHER`.

### 5.6 Unknown record-type pipe-delimited error line

```
seq=NNN | type='?' | timestamp=YYYY-MM-DD-HH.MI.SS.MIL0000 | (raw payload, ASCII-printable subset, truncated to 132 bytes)
```

Exact format must be lifted from `2700-PROCESS-UNKNOWN-RECORD` in source. The 132-byte hard length includes all delimiters and any truncation marker.

### 5.7 Five output FB record-length enforcement

After each handler, the byte count written to the destination must equal that file's LRECL exactly:
- 500 bytes per CUSTOUT record
- 300 per ACCTOUT
- 50 per XREFOUT
- 350 per TRNXOUT
- 150 per CARDOUT

A unit test per handler asserts the exact length. The IT then catches any drift.

### 5.8 `RECORD SEQUENTIAL` vs `LINE SEQUENTIAL` — binary content

The `EXPFILE` contains binary `COMP` + `COMP-3` bytes that may include `0x0A` or `0x0D`. `LINE SEQUENTIAL` would interpret these as record terminators and corrupt the file. The portable oracle uses `RECORD SEQUENTIAL` with `RECORD CONTAINS 500 CHARACTERS`. Java's writer / reader work on raw bytes (no LF handling) — this is automatic.

### 5.9 Round-trip property (non-blocking)

`Java CBEXPORT → Java CBIMPORT → reconstructed master files`:

- Fields *carried* by the export should round-trip byte-identically.
- Fields *not carried* (FILLER, fields the export omits) end up as INITIALIZEd defaults in the reconstructed files — likely **different** from the originals.

The `DataExportImportRoundTripTest` documents which fields drift and asserts the carried ones are stable. The hard acceptance criterion is the GnuCOBOL byte-equivalence (§2), not the round-trip.

---

## 6. Test strategy

### 6.1 Unit tests

| Test class | Concern |
| ---------- | ------- |
| `BinaryIntegerCodecTest` | 16/32/64-bit big-endian round-trips; max boundaries; zero |
| `ExportRecordRoundTripTest` | All 5 record types; encode → decode → compare bytes |
| `CustomerPayloadTest` | Field offsets and lengths match `CVEXPORT.cpy` |
| `AccountPayloadTest` | Same; specifically COMP-3 amounts |
| `XrefPayloadTest` | Same |
| `TransactionPayloadTest` | Same; COMP-3 amounts |
| `CardPayloadTest` | Same |
| `ErrorLineBuilderTest` | Byte-exact 132-byte error line for known unknown-type input |

### 6.2 Smoke tests

| Test class | Concern |
| ---------- | ------- |
| `DataExporterTest` | 5-file synthetic input → 5-record `EXPFILE` (1 of each type) |
| `DataImporterTest` | 5-record `EXPFILE` → 5 output files (1 record each), `ERROUT` empty |
| `DataImporterUnknownTypeTest` | 1 record with `EXPORT-REC-TYPE='Z'` → 0 output records, 1 ERROUT line |

### 6.3 Round-trip property test

`DataExportImportRoundTripTest`: pipes Java `CBEXPORT` → Java `CBIMPORT`; asserts the carried fields equal the originals. Lists the drift fields explicitly so future maintainers don't think the test is broken.

### 6.4 Golden-file integration tests

```
1. cd cobol-reference && bash tools/regen-export-golden.sh
   → builds CBEXPORT-P
   → runs against the same source fixtures used by CBACT0[1234]C oracles
   → cp expfile.dat → fixtures/expected/

2. mvn verify
   → ExportGoldenFileEquivalenceIT
       Files.mismatch(expected/expfile.dat, temp/expfile.dat) == -1L

3. cd cobol-reference && bash tools/regen-import-golden.sh
   → builds CBIMPORT-P
   → runs against the COBOL-produced expfile.dat
   → cp the 6 outputs → fixtures/expected/

4. mvn verify
   → ImportGoldenFileEquivalenceIT
       Files.mismatch(expected, temp) == -1L for all 6 outputs
```

### 6.5 Synthetic edge-case fixtures

| Fixture | Purpose |
| ------- | ------- |
| `single-of-each/` | 1 customer, 1 account, 1 xref, 1 transaction, 1 card → 5-record `EXPFILE` (sequence 1–5) |
| `large-set/` | 100 customers → exercise sequence number 100 (`COMP` 4-byte big-endian `0x00 0x00 0x00 0x64`) |
| `unknown-type/` | manually crafted `EXPFILE` with `EXPORT-REC-TYPE='Z'` → tests `WHEN OTHER` branch |
| `comp3-boundary/` | Account with `ACCT-CURR-BAL = -9999999999.99` → exercises sign nibble + max-magnitude packed decimal |

---

## 7. Documents to be produced during implementation

1. Append "CBEXPORT" and "CBIMPORT" sections to
   `app/java/batch_processing_workflow/README.md`.
2. Append a CBEXPORT/CBIMPORT section to `HOW-TO-TEST.md` covering the
   two-stage golden flow (export then import).
3. Add `docs/migration/CBEXPORT-CBIMPORT-java-architecture.md`.
4. Update `docs/cobol/batch-programs.md` `CBEXPORT` and `CBIMPORT`
   sections with pointers.
5. Document the new `BinaryIntegerCodec` API in shared `io` javadoc.
6. The full `CVEXPORT` field-by-field tabulation (from step 1 of §4)
   lands as an appendix to this plan.

---

## 8. Risks and open decisions

### 8.1 Risks

| Risk | Mitigation |
| ---- | ---------- |
| `BinaryIntegerCodec` byte order wrong (little- vs big-endian) → silent diff | Unit test asserts `seq=1 → [0x00, 0x00, 0x00, 0x01]` |
| `LINE SEQUENTIAL OUTPUT` mishandling binary content in `EXPFILE` | `RECORD SEQUENTIAL` + explicit `RECORD CONTAINS 500` in oracle |
| `INITIALIZE` semantics partially replicated in Java (alphanumerics OK, numerics missed) | `DataImporterTest` round-trip plus a unit test that asserts byte 0 of every numeric field is `0x00` (or `0x30` for zoned) before the field-by-field copy |
| 460-byte payload allocation drift between record types (a `REDEFINES` shape that's only 459 bytes) | Step 1 tabulation enforces 460-byte invariant; per-payload tests catch a 1-byte miscount |
| Round-trip property fails because export drops a field; user reads it as a regression | `DataExportImportRoundTripTest` documents drift fields explicitly; assertion only on carried fields |
| `EXPORT-TIMESTAMP` injection forgotten → IT shows random 26-byte drift in every record | `Db2Timestamp.useFixedClock` in `@BeforeAll`; CLI exposes `--ts=` flag |

### 8.2 Open decisions to confirm

1. **Pair migrated together** — yes (recommended) or split.
2. **`EXPFILE` write order strict** (customers → accounts → xrefs → transactions → cards) — accept (matches source).
3. **`INITIALIZE` numerics zeroing** — accept the explicit zero-fill before field-by-field copy.
4. **`3000-VALIDATE-IMPORT` stub** — leave as no-op (faithful) or implement post-port. Recommend leave as no-op with `// TODO`.
5. **Defaults in §3.2** — accept or override.
6. **Scope locked** to the pair only — yes/no.

---

## 9. Out of scope for this migration

- `CBEXPORT`'s `OPEN OUTPUT INDEXED` semantics for VSAM (real VSAM
  ABENDs on out-of-order keys; the portable oracle and Java port use
  sequential output).
- Cross-version export-format compatibility. The export format is
  treated as a snapshot; older versions of `CVEXPORT` aren't
  supported.
- `3000-VALIDATE-IMPORT` — the source treats this as a placeholder
  that always succeeds; the Java port matches.

---

## 10. Approval gate

Confirm before implementation:

1. **Pair migrated together** — yes/no.
2. **`BinaryIntegerCodec` promoted** to shared `io` — yes (recommended).
3. **`INITIALIZE` semantics** — explicit zero-fill numerics + space-fill
   alphanumerics — yes/no.
4. **`3000-VALIDATE-IMPORT`** — leave as stub or implement.
5. **Defaults in §3.2** — accept or override.
6. **Scope locked** to the pair only — yes/no.

Once confirmed, work proceeds through steps 1–16 in §4.
