# Migration Plan: `CBTRN01C.cbl` → Java

**Source program:** `app/cbl/CBTRN01C.cbl` — the **daily-transaction reader / scaffold** of CardDemo. Reads each daily transaction, looks up its card on `XREFFILE`, and looks up the corresponding account on `ACCTFILE`. Emits SYSOUT only — does **not** post transactions or update balances. Effectively a reference / scaffold for the production poster `CBTRN02C`.

**Driving JCL:** None in `app/jcl/`. The compiled load module is built but no nightly job invokes it. Treat this port as the low-risk testbed for the random-read store before `CBTRN02C` puts it under load.

**Target location (mandatory):** `app/java/batch_processing_workflow/`

**Goal:** A Java port that produces a SYSOUT capture **byte-for-byte identical** to the COBOL `DISPLAY` output on the same set of input fixtures, verified by an automated test that diffs against a GnuCOBOL-runnable reference build.

This plan is purely a plan. **No code is written until approved.**

---

## 1. Source contract — what `CBTRN01C` does

### 1.1 Inputs

| DD name | COBOL `SELECT` | Org / Access | Record (copybook) | Notes |
| ------- | -------------- | ------------ | ----------------- | ----- |
| `DALYTRAN` | `DALYTRAN-FILE` | SEQUENTIAL | 350 bytes (`CVTRA06Y` — `DALYTRAN-RECORD`) | Driving file. Walked sequentially. |
| `XREFFILE` | `XREF-FILE` | INDEXED / RANDOM | 50 bytes (`CVACT03Y` — `CARD-XREF-RECORD`) | Lookup keyed on `DALYTRAN-CARD-NUM`. |
| `ACCTFILE` | `ACCOUNT-FILE` | INDEXED / RANDOM | 300 bytes (`CVACT01Y` — `ACCOUNT-RECORD`) | Lookup keyed on `XREF-ACCT-ID` from the previous read. |
| `CUSTFILE` | `CUSTOMER-FILE` | INDEXED / RANDOM | 500 bytes (`CVCUS01Y`) | Opened but not referenced in the main loop in the current source — preserve OPEN/CLOSE for byte-equivalent SYSOUT. |
| `CARDFILE` | `CARD-FILE` | INDEXED / RANDOM | 150 bytes (`CVACT02Y`) | Same — opened but not actively read in the main loop. |
| `TRANFILE` | `TRANSACT-FILE` | INDEXED / RANDOM | 350 bytes (`CVTRA05Y`) | Same — opened but unused. |

### 1.2 Outputs

| Stream | COBOL verb | Format |
| ------ | ---------- | ------ |
| `SYSOUT` | Multiple `DISPLAY` | Banner + per-transaction lookup messages + status lines + final banner |

**No file output.** The acceptance check is the SYSOUT capture.

### 1.3 Algorithm (paragraph map)

```
PROCEDURE DIVISION:
    DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN01C'
    PERFORM 0000-DALYTRAN-OPEN          OPEN INPUT  DALYTRAN-FILE
    PERFORM 0100-CUSTFILE-OPEN          OPEN INPUT  CUSTFILE
    PERFORM 0200-XREFFILE-OPEN          OPEN INPUT  XREF-FILE
    PERFORM 0300-CARDFILE-OPEN          OPEN INPUT  CARD-FILE
    PERFORM 0400-ACCTFILE-OPEN          OPEN INPUT  ACCOUNT-FILE
    PERFORM 0500-TRANFILE-OPEN          OPEN INPUT  TRANSACT-FILE

    LOOP UNTIL END-OF-FILE = 'Y':
        PERFORM 1000-DALYTRAN-GET-NEXT
        IF END-OF-FILE = 'N':
            PERFORM 2000-LOOKUP-XREF
                MOVE DALYTRAN-CARD-NUM TO FD-XREF-CARD-NUM
                READ XREF-FILE INTO CARD-XREF-RECORD
                    INVALID KEY: WS-XREF-READ-STATUS = 'N'; DISPLAY '*** invalid card '
                    NOT INVALID KEY: DISPLAY 'XREF FOUND ' CARD-XREF-RECORD
            IF WS-XREF-READ-STATUS = 'Y':
                PERFORM 3000-READ-ACCOUNT
                    MOVE XREF-ACCT-ID TO FD-ACCT-ID
                    READ ACCOUNT-FILE INTO ACCOUNT-RECORD
                        INVALID KEY: WS-ACCT-READ-STATUS = 'N'; DISPLAY '*** acct not found '
                        NOT INVALID KEY: DISPLAY 'ACCOUNT FOUND ' ACCOUNT-RECORD

    PERFORM 9000-9500-…-CLOSE          CLOSE all six files
    DISPLAY 'END OF EXECUTION OF PROGRAM CBTRN01C'
    GOBACK
```

(Exact `DISPLAY` strings must be lifted verbatim from the source — they go into the SYSOUT golden file.)

### 1.4 Record layouts

- `DALYTRAN-RECORD` (350 bytes) — `CVTRA06Y` is structurally a clone
  of `CVTRA05Y` (`TRAN-RECORD`) used by `CBACT04C`. Field names are
  prefixed `DALYTRAN-` instead of `TRAN-`. **Already covered** by the
  `interest/` port's `TransactionRecord`.
- `CARD-XREF-RECORD` (50 bytes) — already wrapped by
  `interest.domain.CardXrefRecord`.
- `ACCOUNT-RECORD` (300 bytes) — already wrapped by
  `interest.domain.AccountRecord` for the 5 fields `interest`
  needs; `account.domain.ExtractAccountRecord` (per the `CBACT01C`
  plan) wraps all 11 fields. **Reuse `ExtractAccountRecord`** here.
- `CUSTOMER-RECORD`, `CARD-RECORD`, `TRAN-RECORD` — opened but unused
  in the main loop. The Java port mirrors that: open the file
  (`Files.newInputStream`), do nothing with it, close. Or skip the
  open entirely and document the deviation if it doesn't affect
  SYSOUT — see §5.4.

### 1.5 SYSOUT format

The exact strings emitted by `CBTRN01C` are not as well-trodden as the readers' `DISPLAY <record>` pattern. Phase 1 of the plan-execution work is to dump the source's `DISPLAY` statements and tabulate every literal:

```
START OF EXECUTION OF PROGRAM CBTRN01C
... per-record (success path) ...
XREF FOUND <50-byte CARD-XREF-RECORD>
ACCOUNT FOUND <300-byte ACCOUNT-RECORD>
... per-record (xref miss) ...
*** INVALID CARD NUMBER FOUND
... per-record (acct miss) ...
*** ACCOUNT RECORD NOT FOUND
END OF EXECUTION OF PROGRAM CBTRN01C
```

A precise byte-by-byte transcription of every `DISPLAY` in `CBTRN01C.cbl` is the first deliverable of step 2 below — it is the contract the Java port must match.

---

## 2. Equivalence requirements (acceptance criteria)

1. **`SYSOUT` capture** matches byte-for-byte against the GnuCOBOL oracle. The fixture must include at least one transaction that succeeds, one with an invalid card, and one with a missing account, so all three SYSOUT branches are exercised.

**Acceptance test:** `diff -q sysout.cobol.txt sysout.java.txt` returns silently.

**No timestamp injection needed** (no `FUNCTION CURRENT-DATE` calls in `CBTRN01C`).

---

## 3. Target architecture

### 3.1 Layout

```
app/java/batch_processing_workflow/
└── src/
    ├── main/java/com/carddemo/batch/
    │   ├── trnref/                                    ← new subpackage for CBTRN01C
    │   │   ├── DailyTransactionReferenceReader.java   main(); mirrors PROCEDURE DIVISION
    │   │   └── domain/
    │   │       └── DalytranRecord.java                wraps CVTRA06Y (350 bytes)
    │   ├── interest/                                  ← UNCHANGED; provides CardXrefRecord
    │   ├── account/                                   ← UNCHANGED; provides ExtractAccountRecord
    │   └── …                                          ← other packages unchanged
    └── test/java/com/carddemo/batch/trnref/
        ├── DalytranRecordRoundTripTest.java
        ├── DailyTransactionReferenceReaderTest.java
        └── DailyTransactionReferenceGoldenFileEquivalenceIT.java
```

The shared package promotion (per `CBACT02C`-plan §3.1) is assumed
already done.  The new primitive this plan introduces is
**`IndexedFileStore<K, V>`** in the shared `io` package — see §3.2.

### 3.2 Decisions

| Decision | Default | Why |
| -------- | ------- | --- |
| Java version | **Java 17** | Same module |
| `XREFFILE` random read | **`IndexedFileStore<String, CardXrefRecord>` keyed on `XREF-CARD-NUM`** | First port to need a primary-key random read of `XREFFILE`; `CBACT04C` uses the AIX path instead. The store is loaded into a `Map<String, CardXrefRecord>` from `LINE SEQUENTIAL INPUT` — same pattern as `CBACT04C`'s `CardXrefFile` but keyed on a different field |
| `ACCTFILE` random read | **`IndexedFileStore<Long, ExtractAccountRecord>` keyed on `ACCT-ID`** | Reuses the `account.domain.ExtractAccountRecord` from the `CBACT01C` plan (all 11 fields needed for `DISPLAY`) |
| Lookup-miss handling | **`Optional.empty()`** + emit the same `*** INVALID …` line | `INVALID KEY` in COBOL is recoverable; do not throw |
| SYSOUT capture | **`System.out` redirection** | Same pattern as readers |
| Unused file opens (`CUSTFILE`, `CARDFILE`, `TRANFILE`) | **`Files.newByteChannel(... READ)` then immediate `close()`** | Faithful to the COBOL OPEN/CLOSE pair. Verify by inspecting SYSOUT — if no `DISPLAY` is tied to those opens, the deviation is invisible and we can collapse them; see §5.4 |

### 3.3 New shared utility — `IndexedFileStore<K, V>`

**Promote from `interest.io`'s `AccountFile` / `CardXrefFile` / `DisclosureGroupFile`** into a generic class:

```java
public final class IndexedFileStore<K, V> {
    public static <K, V> IndexedFileStore<K, V> load(
        Path inputPath,
        int recordSize,
        Function<byte[], V> recordCodec,
        Function<V, K> keyExtractor
    ) throws IOException;

    public Optional<V> lookup(K key);

    public V lookupOrAbend(K key, int abendCode);
}
```

This becomes the canonical primary-key random-read primitive used by
`CBTRN01C`, `CBTRN02C`, `CBTRN03C`, `CBSTM03B`, and the `CBEXPORT`
input side. Its first caller is this plan; promotion happens here.

### 3.4 Why a sequential-file GnuCOBOL port

Same pattern as before. `CBTRN01P.cbl`:
- All six SELECTs change from `INDEXED RANDOM` to `LINE SEQUENTIAL INPUT`.
- Random reads (`READ … KEY IS … INVALID KEY`) become `SEARCH ALL` over in-memory tables loaded at OPEN time. The `INVALID KEY` block fires when `SEARCH ALL` exits via `WHEN OTHER`.
- `DALYTRAN-FILE` was already sequential — no change.
- All `DISPLAY` and ABEND paragraphs verbatim.

---

## 4. Step-by-step migration steps

| # | Step | Acceptance |
| - | ---- | ---------- |
| 1 | Promote `interest/`'s `AccountFile`-style loaders to `IndexedFileStore<K, V>` in shared `io`. Refactor `interest/`'s callers to use the generic. | All existing `interest/` tests pass; behaviour unchanged |
| 2 | Transcribe every `DISPLAY` literal in `CBTRN01C.cbl` to a Java string-constants class `Cbtrn01CMessages`. | Byte-by-byte match with the source `DISPLAY` literals |
| 3 | Define `DalytranRecord` (350-byte wrapper; field accessors mirror `CVTRA06Y`). | Round-trip test parses a synthetic `dailytran.txt` fixture, byte-identical |
| 4 | Implement `DailyTransactionReferenceReader.main()` — opens fixture inputs, loads xref+account stores, emits banners, loops over `DALYTRAN`, performs lookups, emits one of three SYSOUT lines per record. | Runs end-to-end on fixtures |
| 5 | Write `cobol-reference/CBTRN01P.cbl` (sequential-only oracle as described in §3.4) plus the matching `tools/build.sh` and `tools/run.sh` patches. | `bash regen-golden.sh` produces `fixtures/expected/sysout.txt` |
| 6 | Extend `tools/regen-golden.sh` to run `CBTRN01P` and capture SYSOUT. | Output captured |
| 7 | Write unit tests for `DalytranRecord` round-trip and `IndexedFileStore` (lookup hit, lookup miss, lookup-miss-with-abend). | All pass |
| 8 | Write `DailyTransactionReferenceGoldenFileEquivalenceIT`. | Passes |
| 9 | Update `docs/cobol/batch-programs.md` `CBTRN01C` section pointer. | Pointer added |

**Estimated effort:** ~1.5 engineering days. Most of the new work is in step 1 (promotion) and step 5 (portable oracle). Step 1's investment pays back across `CBTRN02C`, `CBTRN03C`, `CBSTM03B`, `CBEXPORT`, `CBIMPORT`.

---

## 5. Logic equivalency safeguards

### 5.1 SYSOUT message strings

Every COBOL `DISPLAY` string is part of the contract. A byte-for-byte mismatch on a banner literal is a regression. The `Cbtrn01CMessages` class lifts every literal verbatim and is the canonical source for the Java port. **Don't paraphrase.**

### 5.2 Lookup-miss handling

```cobol
READ XREF-FILE INTO CARD-XREF-RECORD
   INVALID KEY
     MOVE 'N' TO WS-XREF-READ-STATUS
     DISPLAY '*** INVALID CARD NUMBER FOUND'
   NOT INVALID KEY
     MOVE 'Y' TO WS-XREF-READ-STATUS
     DISPLAY 'XREF FOUND ' CARD-XREF-RECORD
END-READ
```

Java equivalent:

```java
Optional<CardXrefRecord> xref = xrefStore.lookup(cardNum);
if (xref.isEmpty()) {
    out.println(Cbtrn01CMessages.INVALID_CARD);
} else {
    out.write(Cbtrn01CMessages.XREF_FOUND_PREFIX);
    out.write(xref.get().rawBytes());
    out.write('\n');
}
```

The Java port must **not** throw on lookup miss. `INVALID KEY` is the
recoverable case. ABEND is reserved for unexpected file-status codes
(open failure, I/O error), not for "not found".

### 5.3 SYSOUT inheritance from sub-records

`DISPLAY 'XREF FOUND ' CARD-XREF-RECORD` concatenates the literal `XREF FOUND ` (11 chars) with the 50-byte `CARD-XREF-RECORD` and appends `\n`. Total line: 11 + 50 + 1 = 62 bytes. Java emission must match this exactly — no extra space, no `toString()` formatting.

### 5.4 Unused file opens

The COBOL source opens `CUSTFILE`, `CARDFILE`, `TRANFILE` but the
main loop never reads them. The Java port can either:

(a) Open and close empty `IndexedFileStore`s for those files (faithful
to source intent, but adds dead code).
(b) Skip the opens entirely and **only** if no SYSOUT lines reference
them (e.g. an OPEN failure would `DISPLAY '... OPEN FAILED ...'` then
ABEND — but on the success path, OPEN/CLOSE are silent in COBOL).

**Default to (b)**: skip the unused opens. The integration test
proves SYSOUT-equivalence; if no `DISPLAY` mentions those files on
the success path, the deviation is invisible. Document this in the
plan and the architecture doc as the deliberate simplification.

### 5.5 GnuCOBOL `SEARCH ALL` not-found fall-through

The portable oracle uses `SEARCH ALL` over an in-memory table for the
random-read replacement. `SEARCH ALL` falls through `WHEN OTHER` on a
miss, with no built-in mapping to `INVALID KEY`. The portable
program must explicitly set the file-status moral-equivalent on miss
and execute the `INVALID KEY` body. This pattern is established in
the `CBACT04C` portable oracle.

---

## 6. Test strategy

### 6.1 Unit tests

| Test class | Concern |
| ---------- | ------- |
| `DalytranRecordRoundTripTest` | parse → `rawBytes()` byte-identical for `dailytran.txt` |
| `IndexedFileStoreTest` | hit, miss-with-`Optional.empty()`, miss-with-abend |
| `Cbtrn01CMessagesTest` | every literal is exactly N bytes (no accidental whitespace) |

### 6.2 Smoke test

`DailyTransactionReferenceReaderTest` against a 3-record synthetic fixture:
- record 1: card present in xref, account present → `XREF FOUND` + `ACCOUNT FOUND`
- record 2: card not present → `INVALID CARD`
- record 3: card present, account missing → `XREF FOUND` + `ACCOUNT NOT FOUND`

Asserts the SYSOUT line count and the line ordering.

### 6.3 Golden-file integration test

```
1. cd cobol-reference && bash tools/regen-golden.sh
   → cobc -x -free -fsign=EBCDIC CBTRN01P.cbl
   → COB_LS_FIXED=Y ./CBTRN01P > fixtures/expected/sysout.txt
2. mvn verify
   → DailyTransactionReferenceGoldenFileEquivalenceIT
       Files.mismatch(expected/sysout.txt, temp/sysout.txt) == -1L
```

### 6.4 Synthetic edge-case fixtures

| Fixture | Purpose |
| ------- | ------- |
| `all-success/` | All cards and accounts present; only `XREF FOUND` + `ACCOUNT FOUND` lines |
| `all-xref-miss/` | All cards missing; only `INVALID CARD` lines |
| `all-acct-miss/` | All cards present, all accounts missing; `XREF FOUND` + `ACCOUNT NOT FOUND` lines |
| `mixed/` | One of each branch |
| `empty/` | 0 daily transactions; banners only |

---

## 7. Documents to be produced during implementation

1. Append a "CBTRN01C — Daily Transaction Reference Reader" section
   to `app/java/batch_processing_workflow/README.md`.
2. Append a CBTRN01C section to `HOW-TO-TEST.md`.
3. Add `docs/migration/CBTRN01C-java-architecture.md` after
   implementation.
4. Update `docs/cobol/batch-programs.md` with the pointer.
5. **Promotion changelog** — a new section in
   `app/java/batch_processing_workflow/README.md` documenting the
   `IndexedFileStore<K, V>` API, since this plan introduces it as a
   shared utility.

---

## 8. Risks and open decisions

### 8.1 Risks

| Risk | Mitigation |
| ---- | ---------- |
| `IndexedFileStore` API doesn't fit `CBTRN02C`'s `I-O` semantics → second refactor needed | Plan `IndexedFileStore` to handle both read-only and updatable cases; `CBTRN02C` plan §3 calls out the extension required (mutable `Map` + flush-back) |
| SYSOUT literals drift between COBOL source and Java port → byte diff at unexpected offset | `Cbtrn01CMessages` test asserts every string length; review against `CBTRN01C.cbl` line by line |
| Unused file opens deviation invalidates the SYSOUT golden file | The portable oracle keeps the OPEN/CLOSE for those files (so SYSOUT side-effects are matched); only the Java port drops them. The IT validates this is invisible |
| `INVALID KEY` semantics misimplemented as exception → SYSOUT shows ABEND text | Cover with `IndexedFileStoreTest.lookupMissReturnsEmpty` and a synthetic fixture that exercises the miss path |

### 8.2 Open decisions to confirm

- **`IndexedFileStore` promotion** — accept (recommended) or build
  this plan's stores ad-hoc (defer the promotion to `CBTRN02C`).
- **Skip unused file opens** — default (b). Confirm.
- **`DalytranRecord` vs reuse `interest.domain.TransactionRecord`** —
  `CVTRA06Y` and `CVTRA05Y` are the same shape but different field
  names. Default: new `DalytranRecord` (the prefixes matter for
  readability and consistency with the source). If you'd rather alias
  `TransactionRecord`, flag it.

---

## 9. Out of scope for this migration

- Production posting (that's `CBTRN02C`).
- Validation logic (also `CBTRN02C`).
- File output of any kind.

---

## 10. Approval gate

Confirm before implementation:

1. **`IndexedFileStore<K, V>` promotion** as part of step 1 — accept
   or defer.
2. **Skipping unused file opens** — accept or require faithful OPEN/CLOSE.
3. **New `DalytranRecord`** vs aliasing `TransactionRecord` — accept
   or override.
4. **Defaults in §3.2** — accept or override.
5. **Scope locked** to `CBTRN01C` only — yes/no.

Once confirmed, work proceeds through steps 1–9 in §4.
