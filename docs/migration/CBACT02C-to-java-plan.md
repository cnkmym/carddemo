# Migration Plan: `CBACT02C.cbl` → Java

**Source program:** `app/cbl/CBACT02C.cbl` — the **card master file processor** of CardDemo. Driven by `app/jcl/READCARD.jcl`. Sequentially reads `CARDFILE` (KSDS, 150-byte records) and `DISPLAY`s every record to `SYSOUT`.

**Target location (mandatory):** `app/java/batch_processing_workflow/`

**Goal:** A Java port that produces a SYSOUT capture **byte-for-byte identical** to the COBOL `DISPLAY` output on the same input fixture, verified by an automated test that diffs against a GnuCOBOL-runnable reference build.

This plan is purely a plan. **No code is written until approved.**

---

## 1. Source contract — what `CBACT02C` does

### 1.1 Inputs

| DD name | COBOL `SELECT` | Org / Access | Record (copybook) | Notes |
| ------- | -------------- | ------------ | ----------------- | ----- |
| `CARDFILE` | `CARDFILE-FILE` | INDEXED / SEQUENTIAL | 150 bytes (`CVACT02Y` — `CARD-RECORD`) | Driving file. Walked sequentially in key order. |

### 1.2 Outputs

| Stream | COBOL verb | Format | Notes |
| ------ | ---------- | ------ | ----- |
| `SYSOUT` | `DISPLAY CARD-RECORD` | One COBOL `DISPLAY` line per input record | Trailing newline added by COBOL `DISPLAY`. Plus boilerplate `START` / `END` banners and any error-path lines. |

There is **no file output**. The acceptance check is the SYSOUT capture.

### 1.3 Algorithm (paragraph map)

```
PROCEDURE DIVISION:
    DISPLAY 'START OF EXECUTION OF PROGRAM CBACT02C'
    PERFORM 0000-CARDFILE-OPEN              — OPEN INPUT CARDFILE-FILE; ABEND on non-'00'

    LOOP UNTIL END-OF-FILE = 'Y':
        PERFORM 1000-CARDFILE-GET-NEXT
            READ CARDFILE-FILE INTO CARD-RECORD
            status '00': MOVE 0 TO APPL-RESULT
            status '10' (EOF): MOVE 16 TO APPL-RESULT
            other status: MOVE 12 → ABEND via 9999-ABEND-PROGRAM
            APPL-EOF (16) → MOVE 'Y' TO END-OF-FILE
        IF END-OF-FILE = 'N': DISPLAY CARD-RECORD

    PERFORM 9000-CARDFILE-CLOSE             — CLOSE CARDFILE-FILE; ABEND on non-'00'
    DISPLAY 'END OF EXECUTION OF PROGRAM CBACT02C'
    GOBACK
```

### 1.4 Record layout — `CARD-RECORD` (`CVACT02Y`, 150 bytes)

| Offset | Len | COBOL name | PIC | Storage | Java type |
| ------ | --- | ---------- | --- | ------- | --------- |
| 0 | 16 | `CARD-NUM` | `PIC X(16)` | text (primary key) | `String` |
| 16 | 11 | `CARD-ACCT-ID` | `PIC 9(11)` | unsigned numeric | `long` |
| 27 | 3 | `CARD-CVV-CD` | `PIC 9(03)` | unsigned numeric | `int` |
| 30 | 50 | `CARD-EMBOSSED-NAME` | `PIC X(50)` | text | `String` |
| 80 | 10 | `CARD-EXPIRAION-DATE` | `PIC X(10)` | text | `String` |
| 90 | 1 | `CARD-ACTIVE-STATUS` | `PIC X(01)` | text | `String` |
| 91 | 59 | `FILLER` | `PIC X(59)` | spaces | n/a (preserve raw bytes) |

**Total: 150 bytes** — matches JCL `LRECL=150`. No signed fields, no zoned-decimal sign overpunch, no packed decimal. The simplest record in the catalog.

### 1.5 SYSOUT format — what `DISPLAY CARD-RECORD` actually emits

COBOL `DISPLAY` of a level-01 group emits the underlying byte buffer with no formatting, then appends a newline. For `CARD-RECORD` this means:

```
<16 bytes CARD-NUM><11 digits CARD-ACCT-ID><3 digits CARD-CVV-CD><50 bytes CARD-EMBOSSED-NAME><10 bytes CARD-EXPIRAION-DATE><1 byte CARD-ACTIVE-STATUS><59 bytes spaces>\n
```

Total per line: **151 bytes** (150 record bytes + newline). Plus two banner lines:

```
START OF EXECUTION OF PROGRAM CBACT02C\n
... 150-byte CARD-RECORD lines, one per record ...
END OF EXECUTION OF PROGRAM CBACT02C\n
```

The `END-OF-FILE = 'N'` guard inside `1000-CARDFILE-GET-NEXT` and the outer mainline loop together mean: **on the read that returns status `'10'`, no `DISPLAY` happens**. Only successful reads emit a record line. The Java port must replicate this — emitting one extra blank line on EOF is a regression.

---

## 2. Equivalence requirements (acceptance criteria)

One artifact must match byte-for-byte between the COBOL run and the Java run on the same input fixture:

1. **`SYSOUT` capture** — every byte of the captured stdout. Includes:
   - The `START` banner.
   - One 150-byte record line + `\n` per input record.
   - The `END` banner.

**Acceptance test:** `diff -q sysout.cobol.txt sysout.java.txt` returns silently.

**No timestamp injection needed.** `CBACT02C` calls neither `FUNCTION CURRENT-DATE` nor any DB2 timestamp builder; SYSOUT is fully deterministic from the fixture.

---

## 3. Target architecture

### 3.1 Layout

```
app/java/batch_processing_workflow/
└── src/
    ├── main/java/com/carddemo/batch/
    │   ├── card/                                       ← new subpackage for CBACT02C
    │   │   ├── CardFileProcessor.java                  main(); mirrors PROCEDURE DIVISION
    │   │   ├── domain/
    │   │   │   └── CardRecord.java                     wraps CVACT02Y (150 bytes)
    │   │   └── io/
    │   │       └── CardFileReader.java                 sequential 150-byte reader
    │   ├── interest/                                   ← UNCHANGED
    │   └── account/                                    ← from CBACT01C plan, UNCHANGED
    └── test/java/com/carddemo/batch/card/
        ├── CardRecordRoundTripTest.java
        ├── CardFileProcessorTest.java
        └── CardFileGoldenFileEquivalenceIT.java
```

**Reused unchanged:**

| Class | Source package | Reuse purpose |
| ----- | -------------- | ------------- |
| `FixedWidthFormat` | `interest.io` | `text()`, `unsignedNumeric()` for record parsing |
| `BatchAbendException` | `interest.util` | ABEND path |

> **Package-debt note.** This is the third batch program to depend on
> `interest.io.FixedWidthFormat` and `interest.util.BatchAbendException`.
> Per the plan in `CBACT01C-to-java-plan.md` §3.1, this is the right
> moment to promote them into shared `com.carddemo.batch.io` /
> `.util` packages. **Recommend adding the promotion as step 0 of
> this plan.**

### 3.2 Decisions

| Decision | Default | Why |
| -------- | ------- | --- |
| Java version | **Java 17** | Same module |
| SYSOUT capture mechanism | **Redirect `System.out` to a `PrintStream` over `Files.newOutputStream(...)` in `main()`** | Simplest deterministic capture; byte-identical to a shell `> sysout.java.txt` redirect |
| Line terminator | **`\n`** (single byte, ASCII LF) | Matches GnuCOBOL `DISPLAY` default on Linux; matches the existing `interest/` test fixtures |
| Reader strategy | **Pure sequential** — no Map, no random read | Matches `CARDFILE-FILE` `ACCESS MODE IS SEQUENTIAL` |

### 3.3 Why a sequential-file GnuCOBOL port

`CBACT02C.cbl` declares `CARDFILE-FILE` as `INDEXED SEQUENTIAL` — a KSDS in production. The portable oracle (`CBACT02P.cbl`) changes this to `LINE SEQUENTIAL INPUT` so GnuCOBOL can read the ASCII fixture without Berkeley DB. **All `DISPLAY`, OPEN, READ, CLOSE, and ABEND paragraphs are copied verbatim** — the only edit is the `SELECT` clause.

---

## 4. Step-by-step migration steps

| # | Step | Acceptance |
| - | ---- | ---------- |
| 0 | (Optional but recommended) Promote `FixedWidthFormat` and `BatchAbendException` from `interest.io`/`.util` to shared `com.carddemo.batch.io`/`.util`. Update existing `interest` and `account` callers to import from the new location. | `mvn test` passes for all existing tests with no behavioural change |
| 1 | Define `CardRecord` (150-byte wrapper; six typed accessors + `rawBytes()`). | Round-trip test parses `carddata.txt`, re-emits, byte-identical |
| 2 | Implement `CardFileReader` (sequential 150-byte reader, ISO-8859-1, pads short input lines to 150). | Reads fixture; record count matches `wc -c / 150` |
| 3 | Implement `CardFileProcessor.main()` — opens a `PrintStream` to a configurable target (default `System.out`), emits banners, loops over reader, writes `record.rawBytes()` + `\n` per record. | Runs end-to-end on fixtures; output is non-empty |
| 4 | Write `cobol-reference/CBACT02P.cbl` (sequential-only portable oracle: `INDEXED SEQUENTIAL` → `LINE SEQUENTIAL INPUT`; everything else verbatim) plus the matching `tools/build.sh` and `tools/run.sh` patches. | `bash regen-golden.sh` produces `fixtures/expected/sysout.txt` |
| 5 | Extend `cobol-reference/tools/regen-golden.sh` to build, run, and capture `CBACT02P` SYSOUT to `fixtures/expected/sysout.txt`. | Output captured |
| 6 | Write `CardRecordRoundTripTest`, `CardFileProcessorTest` (smoke), and `CardFileGoldenFileEquivalenceIT`. | All pass |
| 7 | Update `docs/cobol/batch-programs.md` `CBACT02C` section with pointer to this plan and the Java port. | Pointer added |

**Estimated effort:** ~4–6 hours. Less work than `CBACT01C` (no COMP-3, no `OCCURS`, no `COBDATFT`, no VBR splitting).

---

## 5. Logic equivalency safeguards

### 5.1 EOF means "no display"

In COBOL the loop body is:

```cobol
PERFORM 1000-CARDFILE-GET-NEXT
IF END-OF-FILE = 'N'
    DISPLAY CARD-RECORD
END-IF
```

So on the read that returns status `'10'`, the read sets `END-OF-FILE = 'Y'` *inside* `1000-CARDFILE-GET-NEXT` (via `APPL-EOF`), and the outer `IF` blocks the `DISPLAY`. The Java port must check the EOF flag *after* the read, before emitting:

```java
while (!eof) {
    Optional<CardRecord> next = reader.readNext();
    if (next.isEmpty()) { eof = true; continue; }    // EOF → no print
    out.write(next.get().rawBytes());
    out.write('\n');
}
```

A test fixture with exactly one record must produce exactly one record line + the two banners — three lines total, not four.

### 5.2 Trailing FILLER preservation

`CARD-RECORD` ends in 59 bytes of `FILLER` (`PIC X(59)`). The fixture stores them as ASCII spaces. Java must preserve them on output — do not `trim()` the record before emitting. The 59 bytes are part of the COBOL `DISPLAY`'s output.

### 5.3 GnuCOBOL `LINE SEQUENTIAL` strips trailing spaces on *input*

This is the inverse of the well-known output-side issue. When `CBACT02P.cbl` reads `carddata.txt` with `LINE SEQUENTIAL INPUT`, GnuCOBOL pads short lines with spaces up to the FD's record length — but only if `COB_LS_FIXED=Y` is exported at run time. Without it, short lines decode as truncated records and the `DISPLAY` lines are short. **Always export `COB_LS_FIXED=Y` in `tools/run.sh`.** This is silent killer #12 from the playbook.

### 5.4 `DISPLAY` newline conventions

GnuCOBOL on Linux emits `\n` (LF) at the end of every `DISPLAY`. z/OS would emit a record boundary instead (no LF). The Java port matches the GnuCOBOL behaviour — single `\n` per display, no leading or trailing whitespace beyond what's in the record buffer.

---

## 6. Test strategy

### 6.1 Unit tests

| Test class | Concern |
| ---------- | ------- |
| `CardRecordRoundTripTest` | parse → `rawBytes()` is byte-identical for every record in `carddata.txt`; six typed accessors return expected values for a hand-picked record |
| `CardFileReaderTest` | Reads N records from a synthetic 2-record fixture; pads short lines |

### 6.2 Round-trip integration test

```
parse(fixtures/input/carddata.txt) → CardRecord[]
records.forEach(r -> r.rawBytes()) → assert bytes == original file bytes
```

### 6.3 Golden-file integration test

```
1. cd cobol-reference && bash tools/regen-golden.sh
   → cobc -x -free -fsign=EBCDIC CBACT02P.cbl
   → COB_LS_FIXED=Y ./CBACT02P > fixtures/expected/sysout.txt
2. mvn verify
   → CardFileGoldenFileEquivalenceIT:
       • runs CardFileProcessor.main(...) with stdout redirected to temp/sysout.txt
       • Files.mismatch(expected/sysout.txt, temp/sysout.txt) == -1L
```

### 6.4 Synthetic edge-case fixtures

| Fixture | Purpose |
| ------- | ------- |
| `single-card/` | 1 card record → 1 record line + 2 banners = 3 lines |
| `empty/` | 0 records → 2 banner lines only, no record line |
| `trailing-filler/` | Record with non-space bytes in the FILLER → bytes preserved verbatim in `DISPLAY` |

---

## 7. Documents to be produced during implementation

1. Append a "CBACT02C — Card File Processor" section to
   `app/java/batch_processing_workflow/README.md` (build + run command).
2. Append a "CBACT02C" section to
   `app/java/batch_processing_workflow/HOW-TO-TEST.md` with the
   `regen-golden.sh` extension steps and `mvn verify` invocation.
3. After implementation, add the architecture companion
   `docs/migration/CBACT02C-java-architecture.md` (very small Mermaid
   graph: `CardFileProcessor → CardFileReader → CardRecord →
   FixedWidthFormat`).

---

## 8. Risks and open decisions

### 8.1 Risks

| Risk | Mitigation |
| ---- | ---------- |
| `DISPLAY` line endings differ between GnuCOBOL and Java (`\r\n` vs `\n`) | `CardFileProcessor` writes `'\n'` literal byte; `tools/run.sh` runs the binary on Linux; `HOW-TO-TEST.md` warns Windows users |
| GnuCOBOL strips trailing spaces from input → record short → `DISPLAY` short | `COB_LS_FIXED=Y` in `run.sh` (silent killer #12) |
| `System.out.flush()` not called → Java IT sees a truncated capture | `CardFileProcessor` uses try-with-resources on the `PrintStream`; flushes on close |
| Premature promotion of `FixedWidthFormat`/`BatchAbendException` could conflict with the still-being-merged `account/` package debt | Coordinate with the `CBACT01C` implementation; if `CBACT01C` lands first, this plan's step 0 is already done |

### 8.2 Open decisions to confirm

- **Step 0 promotion** — accept (recommended) or defer (still allowed; plan works either way).
- **SYSOUT target** — defaulting to `System.out` for CLI runs and a configurable `Path` for tests. OK?
- **Java version** — 17 (same module). OK?

---

## 9. Out of scope for this migration

- Other batch programs (covered by their own plans in this directory).
- Online (`CO*`) programs.
- Production VSAM KSDS access — the portable oracle reads ASCII fixtures, not real VSAM clusters.

---

## 10. Approval gate

Confirm before implementation:

1. **Step 0 promotion** of `FixedWidthFormat` and `BatchAbendException`
   to shared packages — accept or defer.
2. **SYSOUT capture** via `System.out` redirection — accept or specify
   a different mechanism.
3. **Defaults** in §3.2 — accept or override.
4. **Scope locked** to `CBACT02C` only — yes/no.

Once confirmed, work proceeds through steps 0–7 in §4, one commit per step.
