# Migration Plan: `CSUTLDTC.cbl` → Java

**Source program:** `app/cbl/CSUTLDTC.cbl` — the **date-validation utility** of CardDemo. A subroutine wrapping the LE callable service `CEEDAYS`. Returns a 15-character English message describing the validation result (`Date is valid`, `Invalid month`, etc.) plus an LE severity in `RETURN-CODE`. Called from `CSUTLDPY` (a copybook glue) and from online programs `CORPT00C` / `COTRN02C`.

**Driving JCL:** None — invoked as a dynamic-call subprogram via `CALL 'CSUTLDTC' USING LS-DATE, LS-DATE-FORMAT, LS-RESULT`.

**Target location (mandatory):** `app/java/batch_processing_workflow/`

**Goal:** A Java port that produces a **byte-for-byte identical** 80-byte `WS-MESSAGE` and the same severity code as the COBOL source on every supported (date, format) pair, verified by a parameterised test against a GnuCOBOL-runnable reference build.

This plan is purely a plan. **No code is written until approved.**

---

## 1. Source contract — what `CSUTLDTC` does

### 1.1 Inputs (LINKAGE SECTION)

| Linkage name | PIC | Notes |
| ------------ | --- | ----- |
| `LS-DATE` | `PIC X(10)` | Date string under test, e.g. `"2025-04-29"` or `"04/29/2025"` |
| `LS-DATE-FORMAT` | `PIC X(10)` | LE picture string, e.g. `"YYYY-MM-DD"`, `"MM/DD/YYYY"` |
| `LS-RESULT` | `PIC X(80)` | Output buffer for the formatted message |

### 1.2 Outputs

| Output | Format | Notes |
| ------ | ------ | ----- |
| `LS-RESULT` (`PIC X(80)`) | `WS-MESSAGE` formatted layout (see §1.4) | Filled by the routine before `EXIT PROGRAM` |
| `RETURN-CODE` | LE severity (0/1/2/3) | Set from `WS-SEVERITY-N` after `CEEDAYS` returns |

### 1.3 Algorithm

```
PROCEDURE DIVISION USING LS-DATE, LS-DATE-FORMAT, LS-RESULT:
    INITIALIZE WS-MESSAGE; MOVE SPACES TO WS-DATE
    PERFORM A000-MAIN THRU A000-MAIN-EXIT:
        Build VString-shaped WS-DATE-TO-TEST from LS-DATE
        Build VString-shaped WS-DATE-FORMAT from LS-DATE-FORMAT
        MOVE 0 TO OUTPUT-LILLIAN
        CALL "CEEDAYS" USING WS-DATE-TO-TEST, WS-DATE-FORMAT,
                              OUTPUT-LILLIAN, FEEDBACK-CODE
        MOVE WS-DATE-TO-TEST            TO WS-DATE
        MOVE SEVERITY OF FEEDBACK-CODE  TO WS-SEVERITY-N
        MOVE MSG-NO OF FEEDBACK-CODE    TO WS-MSG-NO-N
        EVALUATE TRUE
            WHEN FC-INVALID-DATE        MOVE 'Date is valid'   TO WS-RESULT
            WHEN FC-INSUFFICIENT-DATA   MOVE 'Insufficient'    TO WS-RESULT
            WHEN FC-BAD-DATE-VALUE      MOVE 'Datevalue error' TO WS-RESULT
            WHEN FC-INVALID-ERA         MOVE 'Invalid Era    ' TO WS-RESULT
            WHEN FC-UNSUPP-RANGE        MOVE 'Unsupp. Range  ' TO WS-RESULT
            WHEN FC-INVALID-MONTH       MOVE 'Invalid month  ' TO WS-RESULT
            WHEN FC-BAD-PIC-STRING      MOVE 'Bad Pic String ' TO WS-RESULT
            WHEN FC-NON-NUMERIC-DATA    MOVE 'Nonnumeric data' TO WS-RESULT
            WHEN FC-YEAR-IN-ERA-ZERO    MOVE 'YearInEra is 0 ' TO WS-RESULT
            WHEN OTHER                  MOVE 'Date is invalid' TO WS-RESULT
        END-EVALUATE
    MOVE WS-MESSAGE TO LS-RESULT
    MOVE WS-SEVERITY-N TO RETURN-CODE
    EXIT PROGRAM
```

### 1.4 `WS-MESSAGE` layout — 80 bytes total

| Offset | Len | Content |
| ------ | --- | ------- |
| 0 | 4 | `WS-SEVERITY` — `'%04d' % severity` |
| 4 | 11 | Literal `'Mesg Code: '` (11 chars) — note copybook says `PIC X(11) VALUE 'Mesg Code:'` so offset depends on whether the colon-space is part of the literal or padding |
| 15 | 4 | `WS-MSG-NO` — `'%04d' % msg-no` |
| 19 | 1 | space |
| 20 | 15 | `WS-RESULT` (the 15-char English message above) |
| 35 | 1 | space |
| 36 | 9 | Literal `'TstDate: '` (note 8-char literal + 1 space, but the copybook declares it `PIC X(09) VALUE 'TstDate:'` so total is 9 bytes including trailing pad) |
| 45 | 10 | `WS-DATE` — copy of `LS-DATE` |
| 55 | 1 | space |
| 56 | 10 | Literal `'Mask used:'` |
| 66 | 10 | `WS-DATE-FMT` — copy of `LS-DATE-FORMAT` |
| 76 | 1 | space |
| 77 | 3 | spaces (final FILLER) |

**Total: 80 bytes** — matches `LS-RESULT PIC X(80)`. Exact byte offsets must be re-verified against `app/cbl/CSUTLDTC.cbl` line 42–58 during implementation; the COBOL `02 FILLER PIC X(11) VALUE 'Mesg Code:'` declares an 11-byte field whose `VALUE` is a 10-byte string, so byte 14 is space-padded by COBOL's value-shorter-than-PIC rule. The Java port must replicate this padding exactly.

### 1.5 LE feedback-token mapping (CEEDAYS)

| 88-level | 8-byte hex VALUE | Severity (typical) | English message (15 chars exact) |
| -------- | ---------------- | ------------------ | --------------------------------- |
| `FC-INVALID-DATE` | `X'0000000000000000'` | 0 | `Date is valid` (13 chars + 2 trailing spaces) |
| `FC-INSUFFICIENT-DATA` | `X'000309CB59C3C5C5'` | 3 | `Insufficient` (12 chars + 3 trailing spaces) |
| `FC-BAD-DATE-VALUE` | `X'000309CC59C3C5C5'` | 3 | `Datevalue error` (15 chars exact) |
| `FC-INVALID-ERA` | `X'000309CD59C3C5C5'` | 3 | `Invalid Era    ` (12 chars + 3 trailing spaces, hard-coded by `MOVE 'Invalid Era    '`) |
| `FC-UNSUPP-RANGE` | `X'000309D159C3C5C5'` | 3 | `Unsupp. Range  ` (13 + 2 trailing) |
| `FC-INVALID-MONTH` | `X'000309D559C3C5C5'` | 3 | `Invalid month  ` (13 + 2) |
| `FC-BAD-PIC-STRING` | `X'000309D659C3C5C5'` | 3 | `Bad Pic String ` (14 + 1) |
| `FC-NON-NUMERIC-DATA` | `X'000309D859C3C5C5'` | 3 | `Nonnumeric data` (15 exact) |
| `FC-YEAR-IN-ERA-ZERO` | `X'000309D959C3C5C5'` | 3 | `YearInEra is 0 ` (14 + 1) |
| (OTHER / unmatched) | (any) | 3 | `Date is invalid` (15 exact) |

The `MOVE` literals in the source preserve trailing spaces inside the
quotes, so a `'Invalid Era    '` literal is 12 visible chars + 4
trailing spaces = 16 chars *but* the destination `WS-RESULT` is
`PIC X(15)`, so the last char is dropped on the move. Java must do the
same: format each constant to exactly 15 bytes, padded with ASCII
spaces on the right, truncated on the left if too long.

---

## 2. Equivalence requirements (acceptance criteria)

For every (date, format) pair in the test matrix:

1. **`LS-RESULT`** — the 80 bytes returned must match byte-for-byte
   between the GnuCOBOL run (with a stub `CEEDAYS`) and the Java port.
2. **`RETURN-CODE`** — the integer severity returned must match.

**Acceptance test:** `mvn test` runs a parameterised JUnit test that:
1. Invokes `CsutldtcUtil.validate(date, format)` and captures
   `(messageBytes, severity)`.
2. Asserts both equal the expected values from the matrix.

A separate IT runs the GnuCOBOL oracle (with a stubbed `CEEDAYS`)
over the same matrix and compares its outputs to the Java outputs.

---

## 3. Target architecture

### 3.1 Layout

```
app/java/batch_processing_workflow/
└── src/
    ├── main/java/com/carddemo/batch/
    │   └── dateutil/                             ← new subpackage for CSUTLDTC
    │       ├── CsutldtcUtil.java                 public-static validate(date, format) → (bytes, severity)
    │       ├── DateValidationResult.java         record { byte[] message80; int severity; }
    │       ├── LeFeedbackCode.java               enum INVALID_DATE, INSUFFICIENT_DATA, … with hex token + 15-byte message + severity
    │       └── DatePictureParser.java            translates LE picture strings to Java DateTimeFormatter
    └── test/java/com/carddemo/batch/dateutil/
        ├── CsutldtcUtilTest.java                 parameterised; covers every LE feedback branch
        ├── DatePictureParserTest.java
        └── CsutldtcGoldenEquivalenceIT.java      diff against GnuCOBOL oracle outputs
```

### 3.2 Decisions

| Decision | Default | Why |
| -------- | ------- | --- |
| Replace `CEEDAYS` with | **`LocalDate.parse(date, formatter)`** wrapped in try/catch | The Lillian-day output is unused; only the validity result matters |
| Picture-string translation | **`DatePictureParser`** mapping `YYYY-MM-DD` → `yyyy-MM-dd`, `MM/DD/YYYY` → `MM/dd/yyyy`, etc. | LE picture strings differ slightly from `DateTimeFormatter` patterns |
| Severity mapping | **0 for valid, 3 for any parse failure** | Matches the dominant LE convention; hand-mapped per branch (no LE library dependency) |
| Output encoding | **ISO-8859-1 byte-identity** | Same as every other batch port |
| GnuCOBOL oracle | **Stub `CEEDAYS`** | The real `CEEDAYS` is mainframe-only; the oracle uses a `cobc -x` build with a hand-written `CEEDAYS.c` stub that matches LE feedback codes for the test matrix |

### 3.3 New shared utility (none needed)

This plan introduces **no** new shared utilities. It uses
`FixedWidthFormat.writeText` / `padToLength` from the shared `io`
package for the 80-byte message construction, and
`java.time.LocalDate` for the parse.

### 3.4 Why a stubbed-CEEDAYS GnuCOBOL oracle

`CEEDAYS` is part of the IBM Language Environment runtime and is not
available in GnuCOBOL. The portable oracle (`CSUTLDTP.cbl`) keeps
`CSUTLDTC.cbl` byte-identical except for an extra `CALL 'CEEDAYS'`
linkage to a small `cobol-reference/CEEDAYS-stub.c` file that returns
hard-coded feedback codes for the test matrix:

```c
// CEEDAYS-stub.c — for the CSUTLDTP oracle only
void CEEDAYS(char *date_vstring, char *fmt_vstring,
             int *out_lillian, char *fb_code) {
    // Match a small set of (date, format) inputs to the LE feedback
    // tokens listed in CSUTLDTC.cbl §1.5.
}
```

This is a thin shim. The oracle is not for testing every
`CEEDAYS`-supported format — it's for proving Java≡COBOL on the
specific feedback-handling branches.

---

## 4. Step-by-step migration steps

| # | Step | Acceptance |
| - | ---- | ---------- |
| 1 | Define `LeFeedbackCode` enum with one constant per `FC-*` 88-level (§1.5). Each constant carries the 8-byte hex token, the 15-byte ASCII message, and the severity. | Unit test asserts each constant's message is exactly 15 bytes and severity matches the matrix |
| 2 | Implement `DatePictureParser.translate(String pictureString)` returning a `DateTimeFormatter`. Cover at least: `YYYY-MM-DD`, `MM/DD/YYYY`, `DD/MM/YYYY`, `YYYYMMDD`. | Unit test maps each known input format to a parseable `DateTimeFormatter` |
| 3 | Implement `CsutldtcUtil.validate(date, format) → DateValidationResult` that:<br>1. parses `format` via `DatePictureParser`,<br>2. tries `LocalDate.parse(date, formatter)`,<br>3. on success returns `INVALID_DATE` (severity 0, message `"Date is valid"`),<br>4. on `DateTimeParseException` maps to one of the LE constants (best-effort: `month` failures → `INVALID_MONTH`, etc.). | Parameterised test passes for the matrix |
| 4 | Implement the 80-byte `WS-MESSAGE` builder using `FixedWidthFormat.writeText` for each literal/field. | Test for a known-valid date asserts byte 0–3 = `"0000"`, bytes 20–34 = `"Date is valid  "`, etc. |
| 5 | Write `cobol-reference/CSUTLDTP.cbl` (verbatim copy of `CSUTLDTC.cbl`; no source change) plus `CEEDAYS-stub.c` and a `tools/build-cdate.sh`. | Compiles; runs against the test matrix |
| 6 | Write `CsutldtcGoldenEquivalenceIT` that calls both the GnuCOBOL binary (via `Runtime.exec`) and the Java port for each test case in the matrix and asserts byte-equivalence on `LS-RESULT` + severity. | Passes |
| 7 | Update `docs/cobol/batch-programs.md` `CSUTLDTC` section pointer. | Pointer added |

**Estimated effort:** ~1–1.5 engineering days. Most of the work is in the picture-string translator (step 2) and the byte-exact message builder (step 4). The CEEDAYS stub is small.

---

## 5. Logic equivalency safeguards

### 5.1 15-byte fixed-width result strings

Every `WS-RESULT` literal is exactly 15 bytes. COBOL truncates a 16-byte literal moved into a 15-byte field on the right (left-justified). Java must format every constant to exactly 15 bytes and never emit a 14- or 16-byte string. A unit test asserts each constant byte length.

### 5.2 Severity mapping

The COBOL source maps LE feedback tokens to severity values inside `CEEDAYS` itself; the program reads them from `SEVERITY OF FEEDBACK-CODE`. The Java port doesn't have access to LE — it must hard-code severity per branch. The matrix in §1.5 is the source of truth.

### 5.3 Trailing-FILLER preservation in `LS-RESULT`

Bytes 76–79 are spaces from the `02 FILLER PIC X(03) VALUE SPACES` plus a trailing space. The `INITIALIZE WS-MESSAGE` at the start of A000-MAIN guarantees this, regardless of the previous call. Java must `Arrays.fill(buf, (byte) ' ')` first, then overwrite specific offsets.

### 5.4 `WS-DATE` and `WS-DATE-FMT` echo the inputs verbatim

`MOVE LS-DATE TO WS-DATE` (line 122 in source) copies the input date string into the message buffer. If the input is not 10 chars (e.g. a caller passes `"2025-4-29 "`), COBOL right-pads with spaces. Java must do the same — call `FixedWidthFormat.writeText(buf, offset, 10, input)`.

### 5.5 Severity-string formatting

`WS-SEVERITY` is `PIC X(04)` redefined as `PIC 9(04)`. Setting it via `MOVE WS-SEVERITY-N TO ...` zero-pads to 4 digits. Java must use `String.format("%04d", severity)`.

### 5.6 ISO-8859-1 byte identity

All `MOVE`s in COBOL are byte copies. Java's String operations risk introducing UTF-8 multi-byte sequences if a customer passed in a non-ASCII date format string. Use `byte[]` throughout the message builder; do not go through `String` for the buffer construction.

---

## 6. Test strategy

### 6.1 Unit tests — the test matrix

| Date | Format | Expected severity | Expected message (15 bytes) |
| ---- | ------ | ------------------ | ---------------------------- |
| `2025-04-29` | `YYYY-MM-DD` | 0 | `Date is valid ` |
| `2025-13-01` | `YYYY-MM-DD` | 3 | `Invalid month  ` |
| `2025-04-32` | `YYYY-MM-DD` | 3 | `Datevalue error` |
| `04/29/2025` | `YYYY-MM-DD` | 3 | `Bad Pic String ` (or `Date is invalid`, depending on interpretation) |
| `XXXX-XX-XX` | `YYYY-MM-DD` | 3 | `Nonnumeric data` |
| `0000-04-29` | `YYYY-MM-DD` | 3 | `YearInEra is 0 ` |
| `2025` | `YYYY-MM-DD` | 3 | `Insufficient   ` |
| `2025-04-29` | `XXXXXXXXXX` | 3 | `Bad Pic String ` |
| (empty) | `YYYY-MM-DD` | 3 | `Date is invalid` (`OTHER` branch) |

Note: the exact mapping of Java parse failures to LE feedback codes is a judgment call — `LocalDate.parse` doesn't distinguish "invalid month" from "datevalue error" the same way LE does. The matrix above is the **expected** mapping; if the implementation needs to deviate (e.g. all parse failures collapse to `Date is invalid`), the matrix should be amended in the plan revision before code is written. **This is open decision §8.2 #1.**

### 6.2 Round-trip integration test

```
For each (date, format) in matrix:
    java_result = CsutldtcUtil.validate(date, format)
    cobol_result = exec("./csutldtp", date, format)        # via the oracle binary
    assert java_result.message80 == cobol_result.bytes
    assert java_result.severity == cobol_result.severity
```

### 6.3 Edge-case fixtures

Covered inline by the matrix above; no separate fixture files needed since the inputs are literal strings.

---

## 7. Documents to be produced during implementation

1. Append a "CSUTLDTC — Date Validation Utility" section to
   `app/java/batch_processing_workflow/README.md`. Note that this is
   a library, not a runnable batch — it has no `main()`.
2. Append a CSUTLDTC section to `HOW-TO-TEST.md`, including how to
   build the CEEDAYS stub.
3. Add `docs/migration/CSUTLDTC-java-architecture.md` after
   implementation (very small graph: `CsutldtcUtil → DatePictureParser
   + LeFeedbackCode → java.time.LocalDate`).
4. Update `docs/cobol/batch-programs.md` `CSUTLDTC` section pointer.
5. **Caller migration note** — `CSUTLDTC` is called from
   `CSUTLDPY` (a copybook glue used by `CORPT00C` and `COTRN02C`).
   When those callers are eventually migrated, they import
   `CsutldtcUtil`. Document that in this plan's section 7 so the
   future caller-migration plans don't re-derive the wrapper.

---

## 8. Risks and open decisions

### 8.1 Risks

| Risk | Mitigation |
| ---- | ---------- |
| Java's `LocalDate.parse` doesn't surface the same level of detail as LE's feedback tokens → some matrix rows can't be exactly matched | Document the divergences in `HOW-TO-TEST.md`; collapse non-distinguishable cases to `Date is invalid` on the Java side. **Discuss before implementation** (open decision below). |
| `WS-MESSAGE` byte offsets re-derived from copybook differ by 1 from a careful reading of source — silent diff | Step 4's first unit test asserts exact bytes for one known-good case before any other test runs |
| GnuCOBOL `CEEDAYS` stub diverges from real LE behaviour | Acceptable. The stub is for the oracle test, not production. Document scope. |
| COBOL `MOVE` literal-shorter-than-PIC right-pads with space; Java `FixedWidthFormat.writeText` may pad differently | Lock the helper's contract: `writeText(buf, offset, len, src)` MUST right-pad with `0x20` and truncate from the right. Add a test |

### 8.2 Open decisions to confirm

1. **Java→LE feedback mapping** — Java's `DateTimeParseException`
   message doesn't reliably distinguish `INVALID_MONTH` from
   `BAD_DATE_VALUE`. Should the Java port preserve that distinction
   (regex on the exception message, fragile) or collapse all parse
   errors to `Date is invalid` (simpler, slightly less faithful)?
   Recommend the collapse; flag if you want exact LE fidelity.
2. **GnuCOBOL stub coverage** — the `CEEDAYS` stub only needs to
   handle the matrix in §6.1. If you want coverage of more LE
   feedback codes, expand the matrix and the stub together.
3. **Defaults in §3.2** — accept or override.
4. **Library-only delivery** — `CsutldtcUtil` is a static-method
   utility; no `main()`. OK?

---

## 9. Out of scope for this migration

- Other LE callable services (`CEEDATM`, `CEELOCT`, etc.).
- The `CSUTLDPY` copybook glue and its caller programs (`CORPT00C`,
  `COTRN02C`).
- Online programs in general.
- The `COBDATFT` assembler routine — that's covered by the
  `CBACT01C` plan via `DateConverter`.

---

## 10. Approval gate

Confirm before implementation:

1. **Java→LE feedback mapping policy** — preserve exact LE
   distinctions (riskier) or collapse parse failures to
   `Date is invalid` (recommended).
2. **CEEDAYS stub scope** — minimum-matrix coverage as listed in §6.1.
3. **Defaults in §3.2** — accept or override.
4. **Scope locked** to `CSUTLDTC` only — yes/no.

Once confirmed, work proceeds through steps 1–7 in §4.
