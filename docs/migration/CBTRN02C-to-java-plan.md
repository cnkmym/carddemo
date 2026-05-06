# Migration Plan: `CBTRN02C.cbl` → Java

**Source program:** `app/cbl/CBTRN02C.cbl` — the **daily transaction poster** of CardDemo. The production-grade counterpart to the `CBTRN01C` scaffold. Validates each row of the daily-transaction PS against the cross-reference and account masters; for valid rows, updates the running transaction-category balance, updates the account current balance and cycle counters, and writes the transaction into the master `TRANFILE`. Validation failures go to `DALYREJS` (a GDG) with a structured 80-byte trailer. Sets `RETURN-CODE = 4` if any rejects were produced.

**Driving JCL:** `app/jcl/POSTTRAN.jcl` (single step `STEP15`).

**Target location (mandatory):** `app/java/batch_processing_workflow/`

**Goal:** A Java port that produces **byte-for-byte identical output** to the COBOL run — across all four output artifacts (`TRANFILE`, the `ACCTFILE` rewrite, the `TCATBALF` rewrite, `DALYREJS`) plus matching `RETURN-CODE` — verified by an automated test that diffs against a GnuCOBOL-runnable reference build.

This plan is purely a plan. **No code is written until approved.**

---

## 1. Source contract — what `CBTRN02C` does

### 1.1 Inputs

| DD name | COBOL `SELECT` | Org / Access | Record (copybook) | Notes |
| ------- | -------------- | ------------ | ----------------- | ----- |
| `DALYTRAN` | `DALYTRAN-FILE` | SEQUENTIAL | 350 bytes (`CVTRA06Y`) | Driving file. |
| `XREFFILE` | `XREF-FILE` | INDEXED / RANDOM | 50 bytes (`CVACT03Y`) | Lookup keyed on `DALYTRAN-CARD-NUM`. |

### 1.2 Outputs (multiple paths, one program)

| DD name | COBOL `SELECT` | Org / Access | Record | Notes |
| ------- | -------------- | ------------ | ------ | ----- |
| `TRANFILE` | `TRANSACT-FILE` | INDEXED / RANDOM, OPENED `OUTPUT` | 350 bytes (`CVTRA05Y`) | Wholesale rewrite of master. Note: opened `OUTPUT` not `I-O` — the existing TRANFILE is replaced. |
| `ACCTFILE` | `ACCOUNT-FILE` | INDEXED / RANDOM, OPENED `I-O` | 300 bytes (`CVACT01Y`) | Random `READ` + `REWRITE`-in-place. |
| `TCATBALF` | `TCATBAL-FILE` | INDEXED / RANDOM, OPENED `I-O` | 50 bytes (`CVTRA01Y`) | Random `READ`; `WRITE` (new key) or `REWRITE` (existing key). |
| `DALYREJS` | `DALYREJS-FILE` | SEQUENTIAL OUTPUT (GDG `+1`) | 430 bytes (350-byte rejected `DALYTRAN` + 80-byte trailer) | One per validation-failed transaction. |
| `RETURN-CODE` | (special register) | n/a | int | `0` if no rejects, `4` if any rejects. |
| `SYSOUT` | n/a | text | various | Banners, "TCATBAL not found, creating" message, transaction/reject counts. |

### 1.3 Algorithm (paragraph map)

```
PROCEDURE DIVISION:
    DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN02C'
    OPEN all 6 files (paragraphs 0000–0500). ABEND on non-'00'.

    LOOP UNTIL END-OF-FILE = 'Y':
        PERFORM 1000-DALYTRAN-GET-NEXT
        IF END-OF-FILE = 'N':
            ADD 1 TO WS-TRANSACTION-COUNT
            MOVE 0 TO WS-VALIDATION-FAIL-REASON
            MOVE SPACES TO WS-VALIDATION-FAIL-REASON-DESC
            PERFORM 1500-VALIDATE-TRAN:
                1500-A-LOOKUP-XREF (READ XREF; INVALID KEY → reason=100)
                IF reason=0: 1500-B-LOOKUP-ACCT
                    READ ACCOUNT
                    INVALID KEY → reason=101
                    NOT INVALID KEY:
                        WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT
                        IF ACCT-CREDIT-LIMIT < WS-TEMP-BAL: reason=102 'OVERLIMIT TRANSACTION'
                        IF ACCT-EXPIRAION-DATE < DALYTRAN-ORIG-TS(1:10): reason=103 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
            IF reason = 0: PERFORM 2000-POST-TRANSACTION
            ELSE:           ADD 1 TO WS-REJECT-COUNT; PERFORM 2500-WRITE-REJECT-REC

    CLOSE all 6 files (9000–9500). ABEND on non-'00'.
    DISPLAY 'TRANSACTIONS PROCESSED :' WS-TRANSACTION-COUNT
    DISPLAY 'TRANSACTIONS REJECTED  :' WS-REJECT-COUNT
    IF WS-REJECT-COUNT > 0: MOVE 4 TO RETURN-CODE
    DISPLAY 'END OF EXECUTION OF PROGRAM CBTRN02C'
    GOBACK
```

**`2000-POST-TRANSACTION`** — for each valid transaction:

```
MOVE every DALYTRAN-* field to corresponding TRAN-* field
PERFORM Z-GET-DB2-FORMAT-TIMESTAMP    → TRAN-PROC-TS = current DB2 timestamp
PERFORM 2700-UPDATE-TCATBAL:
    READ TCATBAL by (acct, type, cat).
    INVALID KEY:
        2700-A-CREATE-TCATBAL-REC: INITIALIZE; populate; ADD DALYTRAN-AMT; WRITE
    NOT INVALID KEY (status '00'):
        2700-B-UPDATE-TCATBAL-REC: ADD DALYTRAN-AMT; REWRITE
PERFORM 2800-UPDATE-ACCOUNT-REC:
    ADD DALYTRAN-AMT TO ACCT-CURR-BAL
    IF DALYTRAN-AMT >= 0: ADD TO ACCT-CURR-CYC-CREDIT
    ELSE:                  ADD TO ACCT-CURR-CYC-DEBIT
    REWRITE ACCOUNT-RECORD
        INVALID KEY → reason=109 'ACCOUNT RECORD NOT FOUND'   (note: silent — does not write reject)
PERFORM 2900-WRITE-TRANSACTION-FILE: WRITE TRAN-RECORD to TRANFILE
```

### 1.4 `Z-GET-DB2-FORMAT-TIMESTAMP`

Identical to `CBACT04C`'s helper — produces `YYYY-MM-DD-HH.MI.SS.MIL0000` (26 chars) from `FUNCTION CURRENT-DATE`. **Reuse `interest.util.Db2Timestamp` directly.**

### 1.5 Validation reasons → trailer reason codes

| Reason | Description (`PIC X(76)` truncated/padded) | Source |
| ------ | ------------------------------------------- | ------ |
| 100 | `INVALID CARD NUMBER FOUND` | XREF lookup miss |
| 101 | `ACCOUNT RECORD NOT FOUND` | ACCT lookup miss |
| 102 | `OVERLIMIT TRANSACTION` | Credit limit exceeded |
| 103 | `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` | Expiration check |
| 109 | `ACCOUNT RECORD NOT FOUND` | REWRITE failure (silent — never reaches `WRITE-REJECT-REC`) |

### 1.6 Reject record layout — 430 bytes total

```
+------------------+  +-----------------------+
| 350-byte         |  | 80-byte trailer       |
| original DALYTRAN|  | reason (4 digits) +   |
| record           |  | description (76 X)    |
+------------------+  +-----------------------+
```

| Trailer offset | Len | Field | PIC | Notes |
| -------------- | --- | ----- | --- | ----- |
| 0 | 4 | `WS-VALIDATION-FAIL-REASON` | `PIC 9(04)` | Zero-padded, e.g. `0102` |
| 4 | 76 | `WS-VALIDATION-FAIL-REASON-DESC` | `PIC X(76)` | Right-padded with spaces |

### 1.7 Numeric formats

All decimal fields are zoned `S9(n)V99` — the same shape used by `CBACT04C`. **Reuse `interest.io.ZonedDecimalCodec`** as-is.

| Field | PIC | Notes |
| ----- | --- | ----- |
| `DALYTRAN-AMT` | `S9(09)V99` | 11 chars zoned |
| `ACCT-CURR-BAL` | `S9(10)V99` | 12 chars zoned |
| `ACCT-CREDIT-LIMIT` | `S9(10)V99` | 12 chars zoned |
| `ACCT-CURR-CYC-CREDIT` | `S9(10)V99` | 12 chars zoned |
| `ACCT-CURR-CYC-DEBIT` | `S9(10)V99` | 12 chars zoned |
| `WS-TEMP-BAL` | `S9(09)V99` | working-storage; intermediate for the limit check |
| `TRAN-CAT-BAL` | `S9(09)V99` | 11 chars zoned in TCATBAL |

### 1.8 Compute behaviour

`COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT` has **no `ROUNDED` clause** → result truncates toward zero on the destination scale of 2. Same as `CBACT04C` rule. Java equivalent: `BigDecimal.add` + `subtract` then `setScale(2, RoundingMode.DOWN)`.

`ADD DALYTRAN-AMT TO ACCT-CURR-BAL` and the cycle-credit/debit additions are also unrounded. The destination scales are 2 already; truncation only matters if amounts are introduced at a different scale. Defensively use `.setScale(2, RoundingMode.DOWN)` after every add.

---

## 2. Equivalence requirements (acceptance criteria)

Five artifacts must match byte-for-byte between the GnuCOBOL oracle run and the Java port run on the same input fixture:

1. **`TRANFILE` output** (350-byte records).
2. **Final state of `ACCTFILE`** (300-byte records).
3. **Final state of `TCATBALF`** (50-byte records).
4. **`DALYREJS` output** (430-byte records).
5. **`RETURN-CODE`** (integer).

Plus a sixth, informational, equivalence:

6. **SYSOUT** — banners, the per-create `TCATBAL record not found...` notes, transaction count, reject count, and final banner. **Treat as best-effort byte equivalence**, not a hard fail (timestamp text is timestamp-injected; counts are deterministic).

**Acceptance test:** five `Files.mismatch(expected, actual) == -1L` checks plus an `assertEquals(expectedReturnCode, actualReturnCode)`.

**Two known sources of divergence to neutralize:**

- **`TRAN-PROC-TS` timestamps** — pull from `FUNCTION CURRENT-DATE` per
  posted transaction. Must inject a fixed `Clock` on both sides.
  COBOL side: compile-time `-DTEST_FIXED_TS=…` switch in
  `Z-GET-DB2-FORMAT-TIMESTAMP`. Java side:
  `Db2Timestamp.useFixedClock(...)` from `interest/util`, called in
  `@BeforeAll`.
- **`DALYTRAN-ORIG-TS`** — read from input fixture verbatim. Already
  deterministic.

**No PARM-driven divergence** — `CBTRN02C` does not consume a JCL `PARM`.

---

## 3. Target architecture

### 3.1 Layout

```
app/java/batch_processing_workflow/
└── src/
    ├── main/java/com/carddemo/batch/
    │   ├── posting/                                         ← new subpackage for CBTRN02C
    │   │   ├── DailyTransactionPoster.java                  main(); mirrors PROCEDURE DIVISION
    │   │   ├── domain/
    │   │   │   ├── DalytranRecord.java                      (reuse from CBTRN01C plan if available; else introduced here)
    │   │   │   └── ValidationFailure.java                   record { int reason; String description; }
    │   │   └── io/
    │   │       ├── DalytranReader.java                      sequential 350-byte reader
    │   │       ├── TransactFileWriter.java                  writes to TRANFILE.dat (sorted on TRAN-ID before flush)
    │   │       ├── DailyRejectsWriter.java                  appends 430-byte reject records
    │   │       └── (uses shared) MutableIndexedFileStore    reads + REWRITE-equivalent flush
    │   ├── interest/                                        ← UNCHANGED; provides Db2Timestamp + ZonedDecimalCodec
    │   ├── account/                                         ← UNCHANGED
    │   └── …                                                ← other packages unchanged
    └── test/java/com/carddemo/batch/posting/
        ├── DalytranRecordRoundTripTest.java
        ├── ValidationLogicTest.java                         covers the three reason-code branches deterministically
        ├── PostingArithmeticTest.java                       BigDecimal RoundingMode.DOWN
        ├── DailyTransactionPosterTest.java                  end-to-end on a synthetic fixture
        └── PostingGoldenFileEquivalenceIT.java              diff vs. GnuCOBOL oracle for all 4 output files
```

### 3.2 Decisions

| Decision | Default | Why |
| -------- | ------- | --- |
| Java version | **Java 17** | Same module |
| `XREFFILE` | **`IndexedFileStore<String, CardXrefRecord>`** (read-only, from `CBTRN01C` plan) | Random read by `XREF-CARD-NUM`. Already loaded. |
| `ACCTFILE` (I-O) | **`MutableIndexedFileStore<Long, ExtractAccountRecord>`** | NEW: extends `IndexedFileStore` with mutable values + ordered flush-back to `LINE SEQUENTIAL OUTPUT`. The Java equivalent of `OPEN I-O` + `REWRITE`. |
| `TCATBALF` (I-O) | **`MutableIndexedFileStore<TcatBalKey, TransactionCategoryBalanceRecord>`** | Same pattern. The composite key (acct, type, cat) is a small `record` value type. |
| `TRANFILE` | **`OPEN OUTPUT` simulated** — Java buffers writes in-memory, then writes them sorted on `TRAN-ID` to `TRANFILE.dat` | Real VSAM `OPEN OUTPUT INDEXED` requires keys in ascending order; the COBOL code writes in DALYTRAN order, which means real VSAM would ABEND on out-of-order keys. The portable oracle and Java port both write sequentially; the IT compares them at the byte level, so any sort divergence is caught |
| `DALYREJS` | **Sequential append-only writer** | 430-byte records (350 + 80) |
| Decimal arithmetic | **`BigDecimal` with `setScale(2, RoundingMode.DOWN)`** | Same as `CBACT04C` |
| Timestamp injection | **`Db2Timestamp.useFixedClock(Instant)`** from `interest.util` | Reuse |
| `RETURN-CODE = 4` | **`System.exit(4)` from `main()` if rejectCount > 0** | Mirrors COBOL convention |
| Map ordering | **`LinkedHashMap`** for ACCTFILE + TCATBAL | Preserves insertion order on flush, mimicking VSAM's key-order rewrite (silent killer #5/#8) |

### 3.3 New shared utility: `MutableIndexedFileStore<K, V>`

Promote from `interest.io.AccountFile` (which already implements this
pattern for `OPEN I-O` of `ACCOUNT-FILE` in `CBACT04C`). The
generalised contract:

```java
public final class MutableIndexedFileStore<K, V> {
    public static <K, V> MutableIndexedFileStore<K, V> load(...);

    public Optional<V> lookup(K key);
    public V lookupOrAbend(K key, int abendCode);

    public void put(K key, V value);     // covers WRITE (new key) and REWRITE (existing key)

    public void flush(Path outputPath);  // dump the LinkedHashMap in insertion order
}
```

This becomes the canonical `OPEN I-O` primitive used by `CBTRN02C` and
later `CBSTM03B`.

### 3.4 Why a sequential-file GnuCOBOL port

Following the established pattern, `CBTRN02P.cbl`:

- All four INDEXED files (`XREF-FILE`, `ACCOUNT-FILE`, `TCATBAL-FILE`,
  `TRANSACT-FILE`) change from `INDEXED RANDOM` to:
  - On open: read entire file into a `WORKING-STORAGE` table via
    `LINE SEQUENTIAL INPUT`, store with `OCCURS DEPENDING ON`.
  - Random reads (`READ … KEY IS … INVALID KEY`) become `SEARCH ALL`
    on the in-memory table; `WHEN OTHER` simulates `INVALID KEY`.
  - `REWRITE` becomes a table update.
  - `WRITE` (for `2700-A-CREATE-TCATBAL-REC`) becomes a table append.
  - On close: dump the table back as `LINE SEQUENTIAL OUTPUT`.
- For `TRANSACT-FILE`, "OPEN OUTPUT" is naturally sequential — no change
  beyond `INDEXED` → `LINE SEQUENTIAL OUTPUT`.
- `DALYTRAN-FILE` was already sequential — `LINE SEQUENTIAL INPUT`.
- `DALYREJS-FILE` was already sequential — `LINE SEQUENTIAL OUTPUT`.
- All business logic paragraphs (`1500*`, `2000`, `2500`, `2700*`,
  `2800`, `2900`, `Z-GET-DB2-FORMAT-TIMESTAMP`, `9999`) **copied
  verbatim**.
- `Z-GET-DB2-FORMAT-TIMESTAMP` is replaced for tests by a
  `-DTEST_FIXED_TS=…` switch (same pattern as the `CBACT04C`
  portable oracle).

---

## 4. Step-by-step migration steps

| # | Step | Acceptance |
| - | ---- | ---------- |
| 1 | Promote `MutableIndexedFileStore<K, V>` into shared `io` (lift from `interest.io.AccountFile`). Refactor `interest/`'s `AccountFile` to be a thin wrapper. | All existing `interest/` tests pass; behaviour unchanged |
| 2 | Define `DalytranRecord` (350-byte wrapper, 18 fields) — reuse from `CBTRN01C` plan if already implemented; else implement here. | Round-trip parses `dailytran.txt`, byte-identical |
| 3 | Define a `TcatBalKey` record `{ long acctId; String typeCd; int catCd; }` for the composite key. | `equals`/`hashCode` test |
| 4 | Define a `ValidationFailure` record `{ int reason; String description; }` and a `Validator` class with `validate(DalytranRecord, IndexedFileStore<String, CardXrefRecord>, MutableIndexedFileStore<Long, ExtractAccountRecord>) → Optional<ValidationFailure>`. Cover reasons 100, 101, 102, 103. | `ValidationLogicTest` passes for all four branches |
| 5 | Implement `TransactFileWriter` (sequential 350-byte writer; mirrors `2900-WRITE-TRANSACTION-FILE`). | Smoke test |
| 6 | Implement `DailyRejectsWriter` (sequential 430-byte writer; mirrors `2500-WRITE-REJECT-REC`). | Smoke test |
| 7 | Implement `DailyTransactionPoster.main()`. Wire the validator, the timestamp injection, the four files, the SYSOUT counters, the RETURN-CODE. | Runs end-to-end on the project fixtures |
| 8 | Write `cobol-reference/CBTRN02P.cbl` (sequential-only oracle as in §3.4) plus the build/run/regen scripts including `-DTEST_FIXED_TS=…`. | `bash regen-golden.sh` produces all 4 expected files |
| 9 | Write the unit tests in §6. | All pass |
| 10 | Write `PostingGoldenFileEquivalenceIT` — runs `DailyTransactionPoster.main()` then asserts byte-equivalence on all 4 output files plus `RETURN-CODE`. | Passes |
| 11 | Update `docs/cobol/batch-programs.md` `CBTRN02C` section pointer. | Pointer added |

**Estimated effort:** ~3–4 focused engineering days. The biggest unknowns are step 1 (promotion + refactor of `AccountFile`) and step 8 (sequential portable oracle for the I-O files).

---

## 5. Logic equivalency safeguards

### 5.1 LinkedHashMap insertion-order on REWRITE

`ACCTFILE` and `TCATBALF` are opened `I-O`. The COBOL code writes them back in **VSAM key order** at flush. The portable oracle reads them via `LINE SEQUENTIAL` and writes them back via `LINE SEQUENTIAL OUTPUT` — preserving **input order**. The Java port must use `LinkedHashMap` (or an explicit insertion-order list) and flush in that order. Any HashMap regression silently re-orders the output and breaks the IT (silent killer #5/#8 from the playbook).

### 5.2 New-record ordering for TCATBAL

`2700-A-CREATE-TCATBAL-REC` writes a brand-new record with a key that didn't previously exist. The portable oracle appends it to the end of the in-memory table. The Java `MutableIndexedFileStore.put(newKey, value)` must also append (which `LinkedHashMap.put` does). On flush, the new record appears at the end of `TCATBAL.dat`.

### 5.3 Validation-fail-reason zero-pad

`WS-VALIDATION-FAIL-REASON PIC 9(04)` zero-pads to 4 digits when written into the trailer. Java: `String.format("%04d", reason)`.

### 5.4 Validation description right-pad

`WS-VALIDATION-FAIL-REASON-DESC PIC X(76)` right-pads with spaces if the literal is shorter. The 4 known reason descriptions:

| Reason | Description literal | Bytes in literal | Trailing spaces to 76 |
| ------ | ------------------- | ----------------- | --------------------- |
| 100 | `INVALID CARD NUMBER FOUND` | 25 | 51 |
| 101 | `ACCOUNT RECORD NOT FOUND` | 24 | 52 |
| 102 | `OVERLIMIT TRANSACTION` | 21 | 55 |
| 103 | `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` | 42 | 34 |

Java: use `FixedWidthFormat.writeText(buf, 4, 76, description)` with right-padding.

### 5.5 `ACCT-EXPIRAION-DATE` string compare

```cobol
IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)
```

This is a **string** comparison (not a date comparison) — both sides are `PIC X(10)`. As long as both follow `YYYY-MM-DD`, lexicographic order matches chronological order. Java: `expirationDate.compareTo(origTs.substring(0, 10)) >= 0`. **Do not parse to `LocalDate`** — that would risk silent format normalisation.

### 5.6 Timestamp injection

```java
@BeforeAll
static void freezeTime() {
    Db2Timestamp.useFixedClock(
        Clock.fixed(Instant.parse("2025-04-29T12:00:00Z"), ZoneOffset.UTC));
}
```

For the COBOL oracle, `tools/build.sh` compiles with
`-DTEST_FIXED_TS='2025-04-29-12.00.00.000000'`. Both sides write the
same 26-byte string to `TRAN-PROC-TS`.

### 5.7 First-record / EOF flush guards

Unlike `CBACT04C`, `CBTRN02C` has no first-record guard or EOF-final-flush. The mainline loop does not run `2700-UPDATE-TCATBAL` on EOF — it stops when the read returns status `'10'`. No special handling required.

### 5.8 Silent reason-109 path (REWRITE failure)

`2800-UPDATE-ACCOUNT-REC` sets `WS-VALIDATION-FAIL-REASON=109` on `INVALID KEY` from `REWRITE` — but the surrounding mainline never re-checks the reason after `2000-POST-TRANSACTION` returns. Reason 109 is therefore **silent**: no reject record, no count increment. Java must replicate this: log a warning, do not write to `DALYREJS`, do not increment the reject counter. (This is almost certainly a bug in the original, but it's the contract.)

### 5.9 Cycle credit / cycle debit signedness

```cobol
IF DALYTRAN-AMT >= 0
    ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT
ELSE
    ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT
END-IF
```

For a negative amount, COBOL adds the negative value to `ACCT-CURR-CYC-DEBIT`, so the debit field accumulates **negatively**. This is unintuitive (one might expect `ABS(DALYTRAN-AMT)` to be added to the debit), but it is the program's behaviour. The Java port must replicate it: `acctCurrCycDebit = acctCurrCycDebit.add(dalytranAmt)` (no abs).

---

## 6. Test strategy

### 6.1 Unit tests

| Test class | Concern |
| ---------- | ------- |
| `DalytranRecordRoundTripTest` | parse → `rawBytes()` byte-identical for `dailytran.txt` |
| `MutableIndexedFileStoreTest` | load, lookup, put-as-update, put-as-insert, flush preserves insertion order |
| `ValidationLogicTest` | All 4 reason codes (100, 101, 102, 103); valid pass-through |
| `PostingArithmeticTest` | Decimal `setScale(2, DOWN)` truncation; cycle credit/debit signedness; over-limit boundary (`==` vs `>`) |
| `RejectRecordLayoutTest` | 430-byte exact layout: 350 (verbatim DALYTRAN) + 4 (zero-padded reason) + 76 (right-padded description) |

### 6.2 Synthetic edge-case fixtures

| Fixture | Purpose |
| ------- | ------- |
| `all-pass/` | 10 valid transactions; 0 rejects; `RETURN-CODE = 0` |
| `xref-miss/` | 1 transaction with unknown card → reason 100, 1 reject |
| `acct-miss/` | 1 with known card but unknown account → reason 101 |
| `over-limit/` | 1 that pushes balance over `ACCT-CREDIT-LIMIT` → reason 102 |
| `expired/` | 1 where `DALYTRAN-ORIG-TS(1:10) > ACCT-EXPIRAION-DATE` → reason 103 |
| `tcatbal-create/` | Valid transaction whose `(acct, type, cat)` key is not in `TCATBAL` → exercises `2700-A-CREATE` |
| `tcatbal-update/` | Valid transaction whose key already exists → exercises `2700-B-UPDATE` |
| `negative-amount/` | Valid negative-amount transaction → exercises `ACCT-CURR-CYC-DEBIT` branch |
| `mixed/` | 5 valid + 3 reject + 2 silent-109 → 5 in `TRANFILE`, 3 in `DALYREJS`, `RETURN-CODE = 4`, `WS-REJECT-COUNT = 3` |

### 6.3 Round-trip integration tests

For each input file (`DALYTRAN`, `XREFFILE`, `ACCTFILE` initial,
`TCATBALF` initial):

```
parse(fixtures/input/foo.txt) → record[]
records.forEach(format) → bytes[]
assert bytes equals original file bytes
```

This catches encoding bugs without running business logic.

### 6.4 Golden-file integration test

```
1. cd cobol-reference && bash tools/regen-golden.sh
   → cobc -x -free -fsign=EBCDIC -DTEST_FIXED_TS='…' CBTRN02P.cbl
   → COB_LS_FIXED=Y ./CBTRN02P
   → copy {transact.dat, acctdata.out, tcatbal.out, dalyrejs.dat} → fixtures/expected/

2. mvn verify
   → PostingGoldenFileEquivalenceIT:
       • setup: copy fixtures/input/ to temp dir
       • Db2Timestamp.useFixedClock(...)
       • DailyTransactionPoster.run(temp)            // returns int returnCode
       • assertReturnCodeMatches(expected)
       • Files.mismatch(expected/transact.dat,  temp/transact.dat)  == -1L
       • Files.mismatch(expected/acctdata.out,  temp/acctdata.out)  == -1L
       • Files.mismatch(expected/tcatbal.out,   temp/tcatbal.out)   == -1L
       • Files.mismatch(expected/dalyrejs.dat,  temp/dalyrejs.dat)  == -1L
```

---

## 7. Documents to be produced during implementation

1. Append a "CBTRN02C — Daily Transaction Poster" section to
   `app/java/batch_processing_workflow/README.md`.
2. Append a CBTRN02C section to `HOW-TO-TEST.md`.
3. Add `docs/migration/CBTRN02C-java-architecture.md` after
   implementation.
4. Update `docs/cobol/batch-programs.md` `CBTRN02C` section pointer.
5. Document the new `MutableIndexedFileStore<K, V>` API alongside
   `IndexedFileStore<K, V>` (introduced in `CBTRN01C` plan) in the
   shared `io` package's javadoc.
6. Document the silent-reason-109 quirk in the
   `DailyTransactionPoster` class doc-comment so future maintainers
   don't "fix" it.

---

## 8. Risks and open decisions

### 8.1 Risks

| Risk | Mitigation |
| ---- | ---------- |
| `LinkedHashMap` regression silently reorders REWRITEs | Both `MutableIndexedFileStoreTest` and the IT catch this. Add a doc-comment on `MutableIndexedFileStore` warning against `HashMap`. |
| Silent reason-109 path obscures failures in production | Document as known. Add a `WARN`-level log so operators see it even if no reject is written. The log doesn't affect byte-equivalence (it goes to a separate stream). |
| `RoundingMode.HALF_UP` slips into the temp-balance computation | `PostingArithmeticTest` covers boundary values |
| Timestamp injection forgotten in CLI mode | Expose `--ts=YYYY-MM-DD-HH.MI.SS.MIL000000` flag on `DailyTransactionPoster.main()` |
| `WRITE OUTPUT` to `TRANFILE` for an INDEXED VSAM file would normally require ascending keys; out-of-order writes ABEND | Both the portable oracle and the Java port write sequentially in DALYTRAN order. The IT proves Java≡oracle, not Java≡real-VSAM. Document this is a known divergence from production VSAM. |
| `TCATBAL` create-vs-update file-status handling is brittle (`'00' OR '23'`) | `MutableIndexedFileStoreTest` covers both branches |
| `DALYREJS` GDG semantics not modelled | Plain sequential append. The mainframe creates `+1` GDG generation in JCL; in Java the file path is just `dalyrejs.dat`. Document the deviation. |

### 8.2 Open decisions to confirm

1. **`MutableIndexedFileStore` promotion** as part of step 1 — accept
   or defer.
2. **Silent reason-109 fidelity** — replicate verbatim (no reject
   written, no count increment) or treat as a bug to be fixed
   (write a 109-reject). Recommend faithful replication; flag if you
   want different behaviour.
3. **`TRAN-PROC-TS` timestamp source** — `Db2Timestamp.useFixedClock`
   in tests, wall clock in CLI runs (with `--ts=` override) — accept?
4. **`RETURN-CODE = 4` mapping** — `System.exit(4)` from `main`. OK?
5. **Defaults in §3.2** — accept or override.
6. **Scope locked** to `CBTRN02C` only — yes/no.

---

## 9. Out of scope for this migration

- Real VSAM `OPEN OUTPUT` semantics (out-of-order key ABEND).
- GDG generation increment for `DALYREJS`.
- The `CSUTLDPY`/`CSUTLDTC` date validation flow (this program doesn't
  use it).
- Online transaction-entry (`COTRN0xC`).
- The `app/scheduler/` automation triggers.

---

## 10. Approval gate

Confirm before implementation:

1. **`MutableIndexedFileStore<K, V>` promotion** in step 1 — accept
   or defer.
2. **Silent-109 fidelity** — accept (recommended) or fix.
3. **Defaults in §3.2** — accept or override.
4. **Scope locked** to `CBTRN02C` only — yes/no.

Once confirmed, work proceeds through steps 1–11 in §4, one commit per step.
