# Migration Plan: `CBACT03C.cbl` → Java

**Source program:** `app/cbl/CBACT03C.cbl` — the **card-account cross-reference processor** of CardDemo. Driven by `app/jcl/READXREF.jcl`. Sequentially reads `XREFFILE` (KSDS, 50-byte records) and `DISPLAY`s every record to `SYSOUT`.

**Target location (mandatory):** `app/java/batch_processing_workflow/`

**Goal:** A Java port that produces a SYSOUT capture **byte-for-byte identical** to the COBOL `DISPLAY` output on the same input fixture, verified by an automated test that diffs against a GnuCOBOL-runnable reference build.

This plan is purely a plan. **No code is written until approved.**

---

## 1. Source contract — what `CBACT03C` does

### 1.1 Inputs

| DD name | COBOL `SELECT` | Org / Access | Record (copybook) | Notes |
| ------- | -------------- | ------------ | ----------------- | ----- |
| `XREFFILE` | `XREFFILE-FILE` | INDEXED / SEQUENTIAL | 50 bytes (`CVACT03Y` — `CARD-XREF-RECORD`) | Driving file. Walked sequentially in key order. |

### 1.2 Outputs

| Stream | COBOL verb | Format |
| ------ | ---------- | ------ |
| `SYSOUT` | `DISPLAY CARD-XREF-RECORD` | One COBOL `DISPLAY` line per input record + two banners |

No file output; SYSOUT capture is the only artifact.

### 1.3 Algorithm (paragraph map)

```
PROCEDURE DIVISION:
    DISPLAY 'START OF EXECUTION OF PROGRAM CBACT03C'
    PERFORM 0000-XREFFILE-OPEN              — OPEN INPUT XREFFILE-FILE; ABEND on non-'00'

    LOOP UNTIL END-OF-FILE = 'Y':
        PERFORM 1000-XREFFILE-GET-NEXT
            READ XREFFILE-FILE INTO CARD-XREF-RECORD
            status '00': MOVE 0 TO APPL-RESULT, DISPLAY CARD-XREF-RECORD   ← duplicate-display bug
            status '10' (EOF): MOVE 16 TO APPL-RESULT
            other: ABEND
            APPL-EOF (16) → MOVE 'Y' TO END-OF-FILE
        IF END-OF-FILE = 'N': DISPLAY CARD-XREF-RECORD                       ← second display

    PERFORM 9000-XREFFILE-CLOSE
    DISPLAY 'END OF EXECUTION OF PROGRAM CBACT03C'
    GOBACK
```

**Notable quirk:** `CBACT03C` `DISPLAY`s the record **twice** — once inside `1000-XREFFILE-GET-NEXT` (status `'00'` branch) and once in the mainline `IF END-OF-FILE = 'N'`. This is the canonical example of the duplicate-display pattern noted in `docs/cobol/batch-programs.md` for `CBACT03C` and `CBCUS01C`. **The Java port must replicate it.** SYSOUT will have two consecutive identical lines per record.

### 1.4 Record layout — `CARD-XREF-RECORD` (`CVACT03Y`, 50 bytes)

| Offset | Len | COBOL name | PIC | Storage | Java type |
| ------ | --- | ---------- | --- | ------- | --------- |
| 0 | 16 | `XREF-CARD-NUM` | `PIC X(16)` | text (primary key) | `String` |
| 16 | 11 | `XREF-CUST-ID` | `PIC 9(11)` | unsigned numeric | `long` |
| 27 | 11 | `XREF-ACCT-ID` | `PIC 9(11)` | unsigned numeric | `long` |
| 38 | 12 | `FILLER` | `PIC X(12)` | spaces | n/a (preserve raw bytes) |

**Total: 50 bytes** — matches JCL `LRECL=50`. No signed fields. Identical structural simplicity to `CBACT02C`. The xref record is also used by `CBACT04C` (where it is read via the `XREF-ACCT-ID` AIX); the `interest/` Java port already has `CardXrefRecord` for that purpose.

### 1.5 SYSOUT format

Each `DISPLAY CARD-XREF-RECORD` emits the 50-byte buffer + `\n` (51 bytes per line). With the duplicate-display, each input record produces **two** consecutive identical lines. Plus two banners.

For a fixture of N input records, expected SYSOUT:

```
START OF EXECUTION OF PROGRAM CBACT03C\n
<50 bytes record 1>\n        ← from inside 1000-…-GET-NEXT
<50 bytes record 1>\n        ← from mainline IF
<50 bytes record 2>\n
<50 bytes record 2>\n
…
END OF EXECUTION OF PROGRAM CBACT03C\n
```

Total lines: `2 + 2*N`.

---

## 2. Equivalence requirements (acceptance criteria)

1. **`SYSOUT` capture** matches byte-for-byte. The duplicate-display means twice as many record lines as input records.

**Acceptance test:** `diff -q sysout.cobol.txt sysout.java.txt` returns silently.

**No timestamp injection needed.**

---

## 3. Target architecture

### 3.1 Layout

```
app/java/batch_processing_workflow/
└── src/
    ├── main/java/com/carddemo/batch/
    │   ├── xref/                                       ← new subpackage for CBACT03C
    │   │   ├── XrefFileProcessor.java                  main(); mirrors PROCEDURE DIVISION
    │   │   └── io/
    │   │       └── XrefFileReader.java                 sequential 50-byte reader
    │   ├── interest/                                   ← UNCHANGED; already has CardXrefRecord
    │   ├── account/                                    ← UNCHANGED
    │   └── card/                                       ← from CBACT02C plan, UNCHANGED
    └── test/java/com/carddemo/batch/xref/
        ├── XrefFileProcessorTest.java
        └── XrefFileGoldenFileEquivalenceIT.java
```

**Reused unchanged:**

| Class | Source package | Reuse purpose |
| ----- | -------------- | ------------- |
| `CardXrefRecord` | `interest.domain` | Already wraps `CVACT03Y` (50 bytes); usable as-is |
| `FixedWidthFormat` | shared `io` (post-promotion) | Record parsing |
| `BatchAbendException` | shared `util` (post-promotion) | ABEND path |

> **No new domain class needed.** This is the first migration to fully
> reuse a domain record from a previous port (`interest.domain.CardXrefRecord`
> introduced for `CBACT04C`'s AIX lookup). Validates the package debt
> argument: shared records belong in a shared package once they have
> two callers.

### 3.2 Decisions

| Decision | Default | Why |
| -------- | ------- | --- |
| Java version | **Java 17** | Same module |
| SYSOUT capture | **`System.out` redirection (same pattern as CBACT02C plan)** | Consistency across reader-style ports |
| Reader strategy | **Pure sequential** | Matches `XREFFILE-FILE` `ACCESS MODE IS SEQUENTIAL` |
| Domain record | **Reuse `interest.domain.CardXrefRecord`** | Already wraps the same copybook |

### 3.3 Why a sequential-file GnuCOBOL port

Same pattern as `CBACT02C`: change the `SELECT` from `INDEXED SEQUENTIAL` to `LINE SEQUENTIAL INPUT` in the portable oracle (`CBACT03P.cbl`); copy every other line verbatim. The duplicate-`DISPLAY` lines are preserved as-is in the oracle.

---

## 4. Step-by-step migration steps

| # | Step | Acceptance |
| - | ---- | ---------- |
| 1 | Implement `XrefFileReader` (sequential 50-byte reader; emits `CardXrefRecord` from `interest.domain`). | Reads `cardxref.txt` fixture; record count matches `wc -c / 50` |
| 2 | Implement `XrefFileProcessor.main()` — banners + dual-write loop + close + final banner. The dual-write replicates the COBOL duplicate-display. | Runs end-to-end on fixtures; output line count = 2 + 2*record_count |
| 3 | Write `cobol-reference/CBACT03P.cbl` (sequential-only oracle; one-line `SELECT` change vs `CBACT03C.cbl`). | Compiles; runs |
| 4 | Extend `cobol-reference/tools/regen-golden.sh` to capture `CBACT03P` SYSOUT to `fixtures/expected/sysout.txt`. | Output captured |
| 5 | Write `XrefFileProcessorTest` (smoke + line-count check) and `XrefFileGoldenFileEquivalenceIT`. | All pass |
| 6 | Update `docs/cobol/batch-programs.md` `CBACT03C` section with pointer to this plan. | Pointer added |

**Estimated effort:** ~3–4 hours. Smaller than `CBACT02C` because no new domain class is introduced (full reuse of `CardXrefRecord`).

---

## 5. Logic equivalency safeguards

### 5.1 Duplicate-display fidelity

This is the central correctness concern. The Java mainline must emit each record **twice**, mirroring:

```cobol
PERFORM 1000-XREFFILE-GET-NEXT          ← if status='00', this DISPLAYs once
IF END-OF-FILE = 'N'
    DISPLAY CARD-XREF-RECORD            ← then mainline DISPLAYs again
END-IF
```

Java equivalent:

```java
while (!eof) {
    Optional<CardXrefRecord> next = reader.readNext();
    if (next.isEmpty()) { eof = true; continue; }
    out.write(next.get().rawBytes());     // mirrors inner DISPLAY (status '00' branch)
    out.write('\n');
    out.write(next.get().rawBytes());     // mirrors mainline DISPLAY
    out.write('\n');
}
```

A test fixture with one record must produce exactly 4 lines (`START`, record, record, `END`), not 3.

### 5.2 EOF still suppresses both displays

When the read returns status `'10'`, neither display fires. The inner `DISPLAY CARD-XREF-RECORD` is in the `status = '00'` branch only, and the outer one is gated by `END-OF-FILE = 'N'`. Two-record fixture → 6 record lines (3 pairs of duplicates), not 7 or 8.

### 5.3 Padding semantics

The 12-byte trailing FILLER (offsets 38–49) is preserved verbatim, same as `CBACT02C`'s 59-byte FILLER.

### 5.4 GnuCOBOL `LINE SEQUENTIAL` quirks

Same as `CBACT02C` — `COB_LS_FIXED=Y` required at run time.

---

## 6. Test strategy

### 6.1 Unit tests

| Test class | Concern |
| ---------- | ------- |
| `XrefFileReaderTest` | Reads N records from a 2-record synthetic fixture; pads short lines |

(`CardXrefRecord` already has its own round-trip tests in the `interest` test package — no need to duplicate.)

### 6.2 Smoke test

`XrefFileProcessorTest` runs the processor against a 1-record synthetic fixture and asserts the output is exactly 4 lines (`START`, record, record, `END`), each ending with `\n`, with the record line equal to the 50 input bytes.

### 6.3 Golden-file integration test

```
1. cd cobol-reference && bash tools/regen-golden.sh
   → cobc -x -free -fsign=EBCDIC CBACT03P.cbl
   → COB_LS_FIXED=Y ./CBACT03P > fixtures/expected/sysout.txt
2. mvn verify
   → XrefFileGoldenFileEquivalenceIT
       • runs XrefFileProcessor.main(...)
       • Files.mismatch(expected/sysout.txt, temp/sysout.txt) == -1L
```

### 6.4 Synthetic edge-case fixtures

| Fixture | Purpose |
| ------- | ------- |
| `single-xref/` | 1 record → 4 SYSOUT lines (validates duplicate-display) |
| `empty/` | 0 records → 2 SYSOUT lines (banners only) |

---

## 7. Documents to be produced during implementation

1. Append a "CBACT03C — Cross-Reference Processor" section to
   `app/java/batch_processing_workflow/README.md`.
2. Append a CBACT03C section to `HOW-TO-TEST.md`.
3. After implementation, add the architecture companion
   `docs/migration/CBACT03C-java-architecture.md` (small Mermaid:
   `XrefFileProcessor → XrefFileReader → CardXrefRecord (reused from
   interest)`).

---

## 8. Risks and open decisions

### 8.1 Risks

| Risk | Mitigation |
| ---- | ---------- |
| Forgetting the duplicate-display → SYSOUT line count off by N | Smoke test asserts exact line count for a 1-record fixture; golden-file IT catches any byte-level drift |
| Reusing `interest.domain.CardXrefRecord` couples the `xref` package to `interest` until promotion | Acceptable; either move `CardXrefRecord` to a shared `domain` package as part of this work, or accept the dep until a fourth caller emerges |
| GnuCOBOL `LINE SEQUENTIAL` strips trailing spaces → records short → both displays short | `COB_LS_FIXED=Y` in `run.sh` |

### 8.2 Open decisions to confirm

- **Domain-record reuse vs. new class** — defaulting to reuse
  `interest.domain.CardXrefRecord`. If the team prefers a new
  `xref.domain.CrossReferenceRecord` (for symmetry with `account` and
  `card`), flag it; the change is a one-line constructor call.
- **Promote `CardXrefRecord` to a shared package** — recommended
  alongside this work. Defer if the package promotion was already done
  for `CBACT02C`.

---

## 9. Out of scope for this migration

Same as `CBACT02C` — this plan is `CBACT03C` only.

---

## 10. Approval gate

Confirm before implementation:

1. **Reuse of `interest.domain.CardXrefRecord`** rather than a new
   class — accept or override.
2. **Duplicate-display fidelity** is the desired behaviour (this is a
   faithful port of an apparent COBOL bug) — yes/no.
3. **Defaults in §3.2** — accept or override.
4. **Scope locked** to `CBACT03C` only — yes/no.

Once confirmed, work proceeds through steps 1–6 in §4, one commit per step.
