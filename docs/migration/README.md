# Batch COBOL → Java Migration Plans

This directory holds one migration plan per COBOL batch program in
`app/cbl/`. Each plan follows the template established by
[`CBACT04C-to-java-plan.md`](./CBACT04C-to-java-plan.md) — the first
program ported and the gold standard for byte-for-byte equivalence
proven by a portable GnuCOBOL oracle.

The plans assume the foundation laid in
[`app/java/batch_processing_workflow/`](../../app/java/batch_processing_workflow/)
(Maven module, Java 17, JUnit 5 + AssertJ, `ZonedDecimalCodec`,
`FixedWidthFormat`, `Db2Timestamp`, `BatchAbendException`) is reused.
Each plan calls out the **new** primitives it adds (e.g.
`PackedDecimalCodec` in `CBACT01C`, `IndexedFileStore` in `CBTRN02C`).

## Status matrix

| # | Program | Plan | Implementation | Driver JCL | Complexity |
|---|---------|------|----------------|------------|-----------|
| 1 | `CBACT04C` | [plan](./CBACT04C-to-java-plan.md) · [arch](./CBACT04C-java-architecture.md) | **Done** — `interest/` package, 49 unit tests + golden-file IT | `INTCALC.jcl` | High (5 files, AIX, REWRITE, DB2 timestamp, decimal arithmetic) |
| 2 | `CBACT01C` | [plan](./CBACT01C-to-java-plan.md) · [arch](./CBACT01C-java-architecture.md) | Plan only | `READACCT.jcl` | Medium (FB+VB outputs, COMP-3, OCCURS, COBDATFT) |
| 3 | `CBACT02C` | [plan](./CBACT02C-to-java-plan.md) | Pending | `READCARD.jcl` | Low (read+display) |
| 4 | `CBACT03C` | [plan](./CBACT03C-to-java-plan.md) | Pending | `READXREF.jcl` | Low (read+display) |
| 5 | `CBCUS01C` | [plan](./CBCUS01C-to-java-plan.md) | Pending | `READCUST.jcl` | Low (read+display) |
| 6 | `CBTRN01C` | [plan](./CBTRN01C-to-java-plan.md) | Pending | none (orphan) | Low (read+lookup, no writes) |
| 7 | `CBTRN02C` | [plan](./CBTRN02C-to-java-plan.md) | Pending | `POSTTRAN.jcl` | High (6 files, multiple I-O, validation/rejects, DB2 timestamp) |
| 8 | `CBTRN03C` | [plan](./CBTRN03C-to-java-plan.md) | Pending | `TRANREPT.jcl` | Medium (paginated FB 133 report, three KSDS lookups) |
| 9 | `CBSTM03A` + `CBSTM03B` | [plan](./CBSTM03A-CBSTM03B-to-java-plan.md) | Pending | `CREASTMT.JCL` | High (paired; `ALTER … GO TO`; 2D OCCURS table; HTML output) |
| 10 | `CBEXPORT` + `CBIMPORT` | [plan](./CBEXPORT-CBIMPORT-to-java-plan.md) | Pending | `CBEXPORT.jcl`, `CBIMPORT.jcl` | High (multi-record `REDEFINES`, `COMP` + `COMP-3`) |
| 11 | `CSUTLDTC` | [plan](./CSUTLDTC-to-java-plan.md) | Pending | none (subroutine) | Low (LE `CEEDAYS` wrapper) |

> "Driver JCL" is the JCL step that runs the program in production.
> Programs without a driver are subroutines (`CBSTM03B`, `CSUTLDTC`)
> or scaffolds (`CBTRN01C`).

## Recommended sequencing

The order below maximises foundation reuse and minimises rework. Once
a phase is complete, the utilities listed under "Promote on completion"
should be lifted into a shared package
(`com.carddemo.batch.io` / `.util`) for the next phase.

### Phase 1 — Validate the foundation against simple readers

**Goal.** Prove that the codecs and I/O helpers from `interest/` are
correct on every read-only program before tackling write paths.

1. **`CBACT02C`** — card master reader. Same template as `CBACT04C`'s
   open/loop/close skeleton; output is the COBOL `DISPLAY` stream
   (text). Validates `ZonedDecimalCodec` against `CARDFILE` records and
   exercises the SYSOUT capture pattern for the first time.
2. **`CBACT03C`** — xref reader. Identical structure to `CBACT02C`.
   Adds the 50-byte `CARD-XREF-RECORD` (already known from `CBACT04C`)
   to the SYSOUT-equivalence harness.
3. **`CBCUS01C`** — customer master reader. Adds the 500-byte
   `CUSTOMER-RECORD` layout (`CVCUS01Y`) to the codec test surface.
4. **`CSUTLDTC`** — date validator. Stand-alone utility; the Java port
   replaces `CEEDAYS` with `LocalDate.parse()` and pattern-matches LE
   feedback codes to the same 15-byte severity strings.

**Promote on completion.** `ZonedDecimalCodec`, `FixedWidthFormat`,
`BatchAbendException` move out of `interest/` into a shared
`com.carddemo.batch.io` / `.util`. SYSOUT capture helper lands in the
shared `test-support` source set.

### Phase 2 — Round out the read paths

**Goal.** Cover every shape of input file before adding write paths.

5. **`CBACT01C`** — already planned. Adds `PackedDecimalCodec`
   (COMP-3) and the `DateConverter` `COBDATFT` replacement. First port
   to write multiple output files in one run (FB 107, FB 110, two VBR
   streams).
6. **`CBTRN01C`** — daily transaction *scaffold* (no writes). First
   port to use multiple KSDS files in `RANDOM` access with `INVALID
   KEY` branches. No production load; serves as a low-risk test bed
   for the random-read store before `CBTRN02C` puts it under load.

**Promote on completion.** `PackedDecimalCodec` joins the shared
`io` package. A reusable `IndexedFileStore<K, V>` (load → in-memory
`Map<K, V>` → ABEND on miss) is extracted; this is the abstraction
both `CBTRN02C` and `CBSTM03B` need.

### Phase 3 — Production write paths

**Goal.** Tackle the programs that update master files.

7. **`CBTRN02C`** — daily transaction poster. Hardest single program
   in the catalog: 6 files, three of them I-O (`ACCTFILE`, `TCATBALF`,
   plus the `TRANFILE` `OUTPUT` rewrite), validation flow with reject
   trailer, DB2 timestamp injection. Reuses `Db2Timestamp` from
   `interest/`. Sets `RETURN-CODE = 4` on rejects → Java `System.exit(4)`.
8. **`CBTRN03C`** — transaction detail report. New format territory:
   FB 133 paginated report with edited PIC clauses (`PIC -ZZZ,ZZZ,ZZZ.ZZ`),
   page totals every 20 lines via `FUNCTION MOD`, account-break
   subtotals. The new primitive is `EditedNumericFormat` for COBOL
   PIC-edit emulation.

**Promote on completion.** `EditedNumericFormat` joins the shared `io`
package. The reject-record-with-trailer pattern (350 + 80 = 430 bytes)
is captured in `RejectWriter` if a third caller emerges.

### Phase 4 — Multi-record formats

**Goal.** Handle the export/import pair, then the statement printer.

9. **`CBEXPORT`** + **`CBIMPORT`** — the only programs that use COMP
   (binary integer) *and* COMP-3 (packed decimal) in the same record,
   layered with five-way `REDEFINES`. Best done as a pair to share the
   `EXPORT-RECORD` (500-byte) layout class. Adds `BinaryIntegerCodec`
   for COMP fields.
10. **`CBSTM03A`** + **`CBSTM03B`** — paired statement printer. The
    only program that uses `ALTER … TO PROCEED TO …` + `GO TO` (a
    self-modifying jump table); the Java port replaces it with a
    `Map<String, Runnable>` dispatch. Two output formats (FB 80 plain
    text, FB 100 HTML). Two-dimensional `OCCURS` table maps to
    `Map<String, List<TrnxRow>>`. `CBSTM03B`'s switch-by-DD-name
    dispatcher becomes the planned `IndexedFileStore` from Phase 2 but
    with the operation register (`OPER`) preserved as a façade for
    byte-for-byte trace equivalence.

**Promote on completion.** `BinaryIntegerCodec` and the
`MultiRecordReader` discriminator pattern join the shared `io`
package.

## Cross-cutting work that applies to every plan

The plans share a common shape, summarised here so each plan can stay
focused on what's *different* about its program:

### Common acceptance criteria

For every program, byte-for-byte equivalence is established by:

1. A **portable GnuCOBOL oracle** (`<PROGRAM>P.cbl` under
   `cobol-reference/`) that swaps INDEXED VSAM access for `LINE
   SEQUENTIAL` + in-memory tables, keeping all business logic
   verbatim.
2. A `tools/regen-golden.sh` that compiles the oracle (`cobc -x -free
   -fsign=EBCDIC`), runs it (with `COB_LS_FIXED=Y`), and copies the
   outputs to `fixtures/expected/`.
3. A `*GoldenFileEquivalenceIT` integration test that runs the Java
   port against the same `fixtures/input/` and asserts
   `Files.mismatch(expected, actual) == -1L` for every output.

The two known sources of divergence neutralised in `CBACT04C` —
timestamps from `FUNCTION CURRENT-DATE` and JCL `PARM` strings —
recur in `CBTRN02C` and the export pair. Every plan that hits them
re-uses `Db2Timestamp.useFixedClock(...)` from `interest/util`.

### Common error-handling translation

Every COBOL program in `app/cbl/` emits the same boilerplate around
file I/O:

```cobol
PERFORM 9999-ABEND-PROGRAM        →  throw new BatchAbendException(999)
PERFORM 9910-DISPLAY-IO-STATUS    →  log("FILE STATUS IS: NNNN<…>")
APPL-RESULT / APPL-AOK / APPL-EOF →  enum AppResult { OK, EOF, ERROR }
```

This translation is identical across plans and isn't repeated in each
plan's section 1.

### Common GnuCOBOL oracle pitfalls

The 14 silent killers from
[`docs/playbook-cobol-to-java-with-claude.md`](../playbook-cobol-to-java-with-claude.md#common-pitfalls-the-silent-killers)
apply across every port. The two that bite every COBOL→GnuCOBOL
oracle are:

- **`-fsign=EBCDIC`** in `cobc` — without it, GnuCOBOL writes plain
  `0` instead of `{` overpunch on positive zero.
- **`COB_LS_FIXED=Y`** at run time — without it, `LINE SEQUENTIAL`
  strips trailing FILLER spaces.

Both are baked into every `tools/build.sh` and `tools/run.sh` template.

## How to use a plan

1. Read the relevant `<PROGRAM>-to-java-plan.md` end-to-end.
2. Confirm the open decisions in §8 with the project owner.
3. Work through the steps in §4, one commit per step.
4. After the golden-file IT passes, write the companion
   `<PROGRAM>-java-architecture.md` (Mermaid graph) — see
   [`CBACT04C-java-architecture.md`](./CBACT04C-java-architecture.md)
   for the template.
5. Append a one-line pointer to the program's section in
   [`docs/cobol/batch-programs.md`](../cobol/batch-programs.md).

## Out of scope for this directory

- Online (`CO*`) programs and BMS maps.
- The three extension modules (`app-transaction-type-db2`,
  `app-vsam-mq`, `app-authorization-ims-db2-mq`).
- CICS-driven programs and EXEC SQL host-variable layouts.
- CI/CD wiring; every plan is local-developer-runnable as written.
