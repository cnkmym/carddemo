# Migration Plan: `CBCUS01C.cbl` → Java

**Source program:** `app/cbl/CBCUS01C.cbl` — the **customer master file processor** of CardDemo. Driven by `app/jcl/READCUST.jcl`. Sequentially reads `CUSTFILE` (KSDS, 500-byte records) and `DISPLAY`s every record to `SYSOUT`.

**Target location (mandatory):** `app/java/batch_processing_workflow/`

**Goal:** A Java port that produces a SYSOUT capture **byte-for-byte identical** to the COBOL `DISPLAY` output on the same input fixture, verified by an automated test that diffs against a GnuCOBOL-runnable reference build.

This plan is purely a plan. **No code is written until approved.**

---

## 1. Source contract — what `CBCUS01C` does

### 1.1 Inputs

| DD name | COBOL `SELECT` | Org / Access | Record (copybook) | Notes |
| ------- | -------------- | ------------ | ----------------- | ----- |
| `CUSTFILE` | `CUSTFILE-FILE` | INDEXED / SEQUENTIAL | 500 bytes (`CVCUS01Y` — `CUSTOMER-RECORD`) | Driving file. Walked sequentially in key order. |

### 1.2 Outputs

| Stream | COBOL verb | Format |
| ------ | ---------- | ------ |
| `SYSOUT` | `DISPLAY CUSTOMER-RECORD` | One COBOL `DISPLAY` line per input record + two banners + duplicate-display |

### 1.3 Algorithm

Identical to `CBACT02C` and `CBACT03C` — open, loop with `1000-CUSTFILE-GET-NEXT`, close. Like `CBACT03C`, the success branch `DISPLAY`s the record both **inside** the GET-NEXT paragraph and again in the **mainline** loop. Two SYSOUT lines per input record.

The cosmetic difference: `CBCUS01C` renames the abend / status helpers from `9999-ABEND-PROGRAM` / `9910-DISPLAY-IO-STATUS` to `Z-ABEND-PROGRAM` / `Z-DISPLAY-IO-STATUS`. This is a label-only change with no behaviour impact.

### 1.4 Record layout — `CUSTOMER-RECORD` (`CVCUS01Y`, 500 bytes)

The full layout is in `app/cpy/CVCUS01Y.cpy`. A summary:

| Offset (decimal) | Len | COBOL name | PIC | Notes |
| ---------------- | --- | ---------- | --- | ----- |
| 0 | 9 | `CUST-ID` | `PIC 9(09)` | Primary key (zoned) |
| 9 | 25 | `CUST-FIRST-NAME` | `PIC X(25)` | text |
| 34 | 25 | `CUST-MIDDLE-NAME` | `PIC X(25)` | text |
| 59 | 25 | `CUST-LAST-NAME` | `PIC X(25)` | text |
| 84 | 50 | `CUST-ADDR-LINE-1` | `PIC X(50)` | text |
| 134 | 50 | `CUST-ADDR-LINE-2` | `PIC X(50)` | text |
| 184 | 50 | `CUST-ADDR-LINE-3` | `PIC X(50)` | text |
| 234 | 2 | `CUST-ADDR-STATE-CD` | `PIC X(02)` | text |
| 236 | 3 | `CUST-ADDR-COUNTRY-CD` | `PIC X(03)` | text |
| 239 | 10 | `CUST-ADDR-ZIP` | `PIC X(10)` | text |
| 249 | 15 | `CUST-PHONE-NUM-1` | `PIC X(15)` | text |
| 264 | 15 | `CUST-PHONE-NUM-2` | `PIC X(15)` | text |
| 279 | 9 | `CUST-SSN` | `PIC 9(09)` | numeric |
| 288 | 4 | `CUST-GOVT-ISSUED-ID-N` | `PIC X(04)` (variant) | text |
| 292 | 10 | `CUST-DOB-YYYY-MM-DD` | `PIC X(10)` | text |
| 302 | 10 | `CUST-EFT-ACCOUNT-ID` | `PIC X(10)` | text |
| 312 | 1 | `CUST-PRI-CARD-HOLDER-IND` | `PIC X(01)` | flag |
| 313 | 3 | `CUST-FICO-CREDIT-SCORE` | `PIC 9(03)` | numeric |
| 316 | 184 | `FILLER` | `PIC X(184)` | spaces |

**Total: 500 bytes** — matches JCL `LRECL=500`. No signed numerics in this record — all numeric fields are unsigned zoned.

> **Important note on `CVCUS01Y` vs `CUSTREC`.** `CBSTM03A` uses an
> alternate copybook called `CUSTREC` whose `CUST-DOB` field is named
> `CUST-DOB-YYYYMMDD` (no dashes), with different downstream slicing.
> **Stick with `CVCUS01Y` for this plan** — it is what `CBCUS01C` and
> `CBTRN01C` use. The `CBSTM03A` plan covers the variant separately.

### 1.5 SYSOUT format

Each `DISPLAY CUSTOMER-RECORD` emits the 500-byte buffer + `\n` (501 bytes per line). With duplicate-display, each input record produces **two** consecutive identical lines. Plus two banners.

For N input records, expected SYSOUT total: `2 + 2*N` lines, `~501 * 2 * N` bytes for record content alone.

---

## 2. Equivalence requirements (acceptance criteria)

1. **`SYSOUT` capture** matches byte-for-byte. Duplicate-display semantics preserved.

**Acceptance test:** `diff -q sysout.cobol.txt sysout.java.txt` returns silently.

**No timestamp injection needed.**

---

## 3. Target architecture

### 3.1 Layout

```
app/java/batch_processing_workflow/
└── src/
    ├── main/java/com/carddemo/batch/
    │   ├── customer/                                   ← new subpackage for CBCUS01C
    │   │   ├── CustomerFileProcessor.java              main(); mirrors PROCEDURE DIVISION
    │   │   ├── domain/
    │   │   │   └── CustomerRecord.java                 wraps CVCUS01Y (500 bytes)
    │   │   └── io/
    │   │       └── CustomerFileReader.java             sequential 500-byte reader
    │   ├── interest/                                   ← UNCHANGED
    │   ├── account/                                    ← UNCHANGED
    │   ├── card/                                       ← UNCHANGED
    │   └── xref/                                       ← UNCHANGED
    └── test/java/com/carddemo/batch/customer/
        ├── CustomerRecordRoundTripTest.java
        ├── CustomerFileProcessorTest.java
        └── CustomerFileGoldenFileEquivalenceIT.java
```

**Reused unchanged:**

| Class | Source package | Reuse purpose |
| ----- | -------------- | ------------- |
| `FixedWidthFormat` | shared `io` (post-promotion) | Record parsing |
| `BatchAbendException` | shared `util` (post-promotion) | ABEND path |

### 3.2 Decisions

| Decision | Default | Why |
| -------- | ------- | --- |
| Java version | **Java 17** | Same module |
| SYSOUT capture | **`System.out` redirection** | Same pattern as CBACT02C / CBACT03C |
| Domain record | **New `CustomerRecord`** in `customer.domain` | First migration to handle `CVCUS01Y` directly; this class becomes reusable for `CBTRN01C` and `CBEXPORT`/`CBIMPORT` plans |
| `CUST-PRI-CARD-HOLDER-IND` semantics | **Treat as `PIC X(01)`** | Matches the copybook; do not coerce to `boolean` (some records may carry non-`Y`/`N` values from imports) |

### 3.3 Why a sequential-file GnuCOBOL port

Same pattern as `CBACT02C` / `CBACT03C`: `INDEXED SEQUENTIAL` → `LINE SEQUENTIAL INPUT` in the portable oracle (`CBCUS01P.cbl`). All other paragraphs verbatim.

---

## 4. Step-by-step migration steps

| # | Step | Acceptance |
| - | ---- | ---------- |
| 1 | Define `CustomerRecord` (500-byte wrapper; ~16 typed accessors + `rawBytes()`). | Round-trip test parses `custdata.txt`, re-emits, byte-identical |
| 2 | Implement `CustomerFileReader` (sequential 500-byte reader; pads short input lines). | Reads fixture; record count matches `wc -c / 500` |
| 3 | Implement `CustomerFileProcessor.main()` — banners + dual-write loop + close + final banner. | Runs end-to-end |
| 4 | Write `cobol-reference/CBCUS01P.cbl` (one-line `SELECT` change vs `CBCUS01C.cbl`). | Compiles; runs |
| 5 | Extend `cobol-reference/tools/regen-golden.sh` to capture `CBCUS01P` SYSOUT. | Output captured |
| 6 | Write `CustomerRecordRoundTripTest`, smoke test, and `CustomerFileGoldenFileEquivalenceIT`. | All pass |
| 7 | Update `docs/cobol/batch-programs.md` `CBCUS01C` section pointer. | Pointer added |

**Estimated effort:** ~5–7 hours. Slightly more than `CBACT02C` / `CBACT03C` because of the larger record (16 accessors vs 6) and the `CVCUS01Y`-vs-`CUSTREC` ambiguity that needs to be flagged in the domain class doc-comment.

---

## 5. Logic equivalency safeguards

### 5.1 Duplicate-display

Same as `CBACT03C` — both the inner `DISPLAY` (status `'00'` branch) and the outer mainline `DISPLAY` fire. Java must emit each record twice.

### 5.2 Trailing FILLER preservation

184 bytes of FILLER at offset 316. Preserve raw bytes; the COBOL `DISPLAY` includes them.

### 5.3 Renamed helper paragraphs

`CBCUS01C` uses `Z-ABEND-PROGRAM` and `Z-DISPLAY-IO-STATUS` (instead of `9999-` / `9910-`). The Java port doesn't care — same `BatchAbendException` and same logging behaviour. The portable oracle keeps the COBOL `Z-` names verbatim.

### 5.4 GnuCOBOL `LINE SEQUENTIAL` quirks

`COB_LS_FIXED=Y` required at run time so that 500-byte records read fully padded.

### 5.5 Numeric field display

`CUST-FICO-CREDIT-SCORE PIC 9(03)`, `CUST-SSN PIC 9(09)`, `CUST-ID PIC 9(09)` are all unsigned zoned — they `DISPLAY` as plain ASCII digits. No sign overpunch involved. The simpler case relative to `interest/`.

---

## 6. Test strategy

### 6.1 Unit tests

| Test class | Concern |
| ---------- | ------- |
| `CustomerRecordRoundTripTest` | parse → `rawBytes()` byte-identical for every record in `custdata.txt`; spot-check accessor values |
| `CustomerFileReaderTest` | Reads N records from a 2-record synthetic fixture |

### 6.2 Smoke test

`CustomerFileProcessorTest` against a 1-record synthetic fixture; asserts exactly 4 SYSOUT lines.

### 6.3 Golden-file integration test

```
1. cd cobol-reference && bash tools/regen-golden.sh
2. mvn verify
   → CustomerFileGoldenFileEquivalenceIT:
       • runs CustomerFileProcessor.main(...)
       • Files.mismatch(expected/sysout.txt, temp/sysout.txt) == -1L
```

### 6.4 Synthetic edge-case fixtures

| Fixture | Purpose |
| ------- | ------- |
| `single-customer/` | 1 record → 4 SYSOUT lines |
| `empty/` | 0 records → 2 SYSOUT banners only |
| `unicode-ascii-boundary/` | Customer name with high-bit ASCII bytes (e.g. `ñ`) → ISO-8859-1 round-trips them as single bytes |

---

## 7. Documents to be produced during implementation

1. Append a "CBCUS01C — Customer File Processor" section to
   `app/java/batch_processing_workflow/README.md`.
2. Append a CBCUS01C section to `HOW-TO-TEST.md`.
3. After implementation, add the architecture companion
   `docs/migration/CBCUS01C-java-architecture.md`.
4. Add a doc-comment on `CustomerRecord` flagging the
   `CVCUS01Y` vs `CUSTREC` distinction so future maintainers don't
   conflate them.

---

## 8. Risks and open decisions

### 8.1 Risks

| Risk | Mitigation |
| ---- | ---------- |
| `CustomerRecord` is later mistakenly reused for `CBSTM03A` (which uses `CUSTREC`, not `CVCUS01Y`) | Doc-comment + a separate `Stm03CustomerRecord` class in the `CBSTM03A` plan |
| Same duplicate-display oversight as in `CBACT03C` | Smoke test asserts line count; IT catches byte-level drift |
| Trailing 184-byte FILLER stripped by JVM `String` operations | `CustomerRecord` works on `byte[]` only; `rawBytes()` returns the buffer; no `String` conversion at write time |
| Unicode mishandling on customer names with non-ASCII bytes | ISO-8859-1 single-byte identity I/O (silent killer prevention) |

### 8.2 Open decisions to confirm

- **`CustomerRecord` vs. extending an `interest`-package class** —
  defaulting to a new `customer.domain.CustomerRecord`. No existing
  class wraps `CVCUS01Y`, so this is non-controversial.
- **Promotion of shared codecs** — assumed already done by the time
  `CBCUS01C` is migrated (per the README sequencing). If not, do it
  here.

---

## 9. Out of scope for this migration

Same as `CBACT02C`/`CBACT03C` — this plan is `CBCUS01C` only.

---

## 10. Approval gate

Confirm before implementation:

1. **Defaults in §3.2** — accept or override.
2. **Duplicate-display fidelity** — yes/no.
3. **Scope locked** to `CBCUS01C` only — yes/no.

Once confirmed, work proceeds through steps 1–7 in §4.
